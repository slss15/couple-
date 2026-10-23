<#
.SYNOPSIS
  Installs or upgrades the Couple server on Windows (no Docker/WSL/Hyper-V needed).

.DESCRIPTION
  Run from the extracted couple-server-windows-x64-<version> folder in an elevated PowerShell (5.1 or 7):

    powershell -ExecutionPolicy Bypass -File .\install.ps1
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -TunnelId <UUID> -TunnelHostname couple.example.com -TunnelJsonFile C:\Users\me\.cloudflared\<UUID>.json

  What it does (idempotent; safe to re-run, which is also how the updater itself is upgraded):
    1. creates <InstallDir>\{config,data,logs,releases,services,updater,cloudflared}
    2. creates config\couple.env from the example if missing and fills JWT_SECRET, MQTT_HOOK_SECRET and
       DEPLOY_HOOK_SECRET with random values when they are empty. Existing values are never overwritten.
    3. copies this release to releases\<version>, renders services\couple-backend.xml and sets releases\CURRENT
    4. refreshes the updater runtime (updater\jre, updater\lib) and its service config
    5. registers the WinSW services couple-backend and couple-updater (and couple-cloudflared when tunnel
       parameters are given: downloads the pinned cloudflared, sha256-verified, and installs config\cloudflared\config.yml)
    6. starts the services (unless -NoStart) and waits for GET http://127.0.0.1:<PORT>/api/v1/health
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    [switch]$NoStart,
    # Locally-managed Cloudflare Tunnel (cloudflared tunnel create <name>): tunnel UUID, public hostname, credentials JSON.
    [string]$TunnelId,
    [string]$TunnelHostname,
    [string]$TunnelJsonFile,
    # Re-render config\cloudflared\config.yml even if it exists (the previous one is kept as config.yml.bak).
    [switch]$ForceTunnelConfig,
    # Only download the pinned, sha256-verified cloudflared.exe to <InstallDir>\cloudflared\ and exit
    # (needed once before `cloudflared tunnel login/create`).
    [switch]$CloudflaredOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-RandomHex([int]$Bytes = 32) {
    $buf = New-Object byte[] $Bytes
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    return -join ($buf | ForEach-Object { $_.ToString('x2') })
}

function Read-KeyValueFile([string]$Path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -le 0) { continue }
        $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $map
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding $false))
}

# Sets KEY=value only when the key is missing or empty. Never overwrites an existing non-empty value.
function Set-EnvIfEmpty([string]$Path, [string]$Key, [string]$Value) {
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.AddRange([string[]][IO.File]::ReadAllLines($Path))
    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*='
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $current = $lines[$i].Substring($lines[$i].IndexOf('=') + 1).Trim()
            if ($current -ne '') { return $false }
            $lines[$i] = "$Key=$Value"
            Write-Utf8NoBom $Path (($lines -join "`r`n") + "`r`n")
            return $true
        }
    }
    $lines.Add("$Key=$Value")
    Write-Utf8NoBom $Path (($lines -join "`r`n") + "`r`n")
    return $true
}

function Protect-File([string]$Path) {
    # Administrators + SYSTEM only (well-known SIDs, so this works on localized Windows too).
    & icacls.exe $Path /inheritance:r /grant:r '*S-1-5-32-544:(F)' '*S-1-5-18:(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls failed on $Path" }
}

function Get-ServiceState([string]$Name) {
    $s = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $s) { return 'Missing' }
    return [string]$s.Status
}

function Stop-CoupleService([string]$Name) {
    if ((Get-ServiceState $Name) -eq 'Running') {
        Write-Host "    stopping $Name"
        Stop-Service -Name $Name -Force
        (Get-Service -Name $Name).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
    }
}

function Expand-Template([string]$Source, [string]$Dest, [hashtable]$Values) {
    $text = [IO.File]::ReadAllText($Source)
    foreach ($k in $Values.Keys) {
        $v = [Security.SecurityElement]::Escape([string]$Values[$k])
        if ($Dest -like '*.yml') { $v = [string]$Values[$k] }
        $text = $text.Replace("@$k@", $v)
    }
    Write-Utf8NoBom $Dest $text
}

function Install-Cloudflared([string]$Dir, [hashtable]$Pins, [string]$Proxy) {
    $exe = Join-Path $Dir 'cloudflared.exe'
    $want = $Pins['CLOUDFLARED_WINDOWS_AMD64_SHA256'].ToLowerInvariant()
    $have = if (Test-Path -LiteralPath $exe) { (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash.ToLowerInvariant() } else { '' }
    if ($have -eq $want) { return $exe }
    Stop-CoupleService 'couple-cloudflared'
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $url = "https://github.com/cloudflare/cloudflared/releases/download/$($Pins['CLOUDFLARED_VERSION'])/cloudflared-windows-amd64.exe"
    $tmp = "$exe.download"
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $iwr = @{ Uri = $url; OutFile = $tmp; UseBasicParsing = $true }
    if ($Proxy) { $iwr['Proxy'] = $Proxy }
    Write-Host "    downloading $url"
    Invoke-WebRequest @iwr
    $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $tmp).Hash.ToLowerInvariant()
    if ($got -ne $want) { Remove-Item -LiteralPath $tmp -Force; throw "cloudflared sha256 mismatch: $got" }
    Move-Item -LiteralPath $tmp -Destination $exe -Force
    return $exe
}

function Install-WinswService([string]$Name, [string]$WinswExe) {
    $exe = Join-Path $servicesDir "$Name.exe"
    Stop-CoupleService $Name
    Copy-Item -LiteralPath $WinswExe -Destination $exe -Force
    if ((Get-ServiceState $Name) -eq 'Missing') {
        Write-Host "    registering service $Name"
        & $exe install
        if ($LASTEXITCODE -ne 0) { throw "WinSW install of $Name failed ($LASTEXITCODE)" }
    }
}

# ---------------------------------------------------------------------------------------------------------------
if (-not (Test-Admin)) { throw 'Run this script from an elevated PowerShell (Run as Administrator).' }

$pkg = $PSScriptRoot
$version = ([IO.File]::ReadAllText((Join-Path $pkg 'VERSION'))).Trim()
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$') { throw "bad VERSION '$version'" }
$pins = Read-KeyValueFile (Join-Path $pkg 'versions.env')
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$servicesDir = Join-Path $InstallDir 'services'
$configDir = Join-Path $InstallDir 'config'
$envFile = Join-Path $configDir 'couple.env'
$releaseDir = Join-Path $InstallDir "releases\$version"
$tunnelRequested = [bool]($TunnelId -or $TunnelHostname -or $TunnelJsonFile)
if ($tunnelRequested -and -not ($TunnelId -and $TunnelHostname -and $TunnelJsonFile)) {
    throw 'Tunnel setup needs all of -TunnelId, -TunnelHostname and -TunnelJsonFile.'
}

Write-Host "Couple server $version -> $InstallDir"

if ($CloudflaredOnly) {
    $proxy = (Read-KeyValueFile $envFile)['HTTPS_PROXY']
    $exe = Install-Cloudflared (Join-Path $InstallDir 'cloudflared') $pins $proxy
    Write-Host "cloudflared $($pins['CLOUDFLARED_VERSION']) ready: $exe"
    return
}

Write-Step 'Creating directories'
foreach ($d in @('config', 'config\cloudflared', 'data\releases', 'data\mqtt', 'data\updater', 'logs', 'releases', 'services', 'updater', 'cloudflared')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir $d) | Out-Null
}

Write-Step 'Configuration (config\couple.env)'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $pkg 'config\couple.env.example') -Destination $envFile
    Write-Host '    created from couple.env.example'
}
foreach ($k in @('JWT_SECRET', 'MQTT_HOOK_SECRET', 'DEPLOY_HOOK_SECRET')) {
    if (Set-EnvIfEmpty $envFile $k (Get-RandomHex 32)) { Write-Host "    generated $k" } else { Write-Host "    kept existing $k" }
}
Protect-File $envFile
$cfg = Read-KeyValueFile $envFile
if (-not $cfg['DB_PASSWORD']) {
    Write-Warning "DB_PASSWORD is empty in $envFile - set it (see SETUP-windows.md section 4) before starting couple-backend."
}
$port = if ($cfg['PORT']) { $cfg['PORT'] } else { '8080' }

Write-Step "Release $version"
Stop-CoupleService 'couple-updater'
if (Test-Path -LiteralPath $releaseDir) {
    Write-Host "    releases\$version already present (kept)"
} else {
    $staging = "$releaseDir.installing"
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    New-Item -ItemType Directory -Path $staging | Out-Null
    foreach ($item in @('jre', 'app', 'admin', 'service', 'cloudflared', 'config', 'winsw', 'VERSION', 'versions.env', 'install.ps1', 'uninstall.ps1')) {
        Copy-Item -LiteralPath (Join-Path $pkg $item) -Destination $staging -Recurse -Force
    }
    Rename-Item -LiteralPath $staging -NewName $version
    Write-Host "    copied to $releaseDir"
}

Write-Step 'Updater runtime (updater\jre, updater\lib)'
foreach ($sub in @('jre', 'lib')) {
    $target = Join-Path $InstallDir "updater\$sub"
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
Copy-Item -LiteralPath (Join-Path $pkg 'jre') -Destination (Join-Path $InstallDir 'updater\jre') -Recurse
Copy-Item -LiteralPath (Join-Path $pkg 'updater\lib') -Destination (Join-Path $InstallDir 'updater\lib') -Recurse
Set-Content -LiteralPath (Join-Path $InstallDir 'updater\VERSION') -Value $version -Encoding ASCII

$winsw = Join-Path $pkg 'winsw\WinSW.exe'
$javaUpd = Join-Path $InstallDir 'updater\jre\bin\java.exe'
$updCp = (Join-Path $InstallDir 'updater\lib') + '\*'

Write-Step 'Service couple-backend'
Install-WinswService 'couple-backend' $winsw
$env:COUPLE_HOME = $InstallDir
$env:COUPLE_ENV_FILE = $envFile
& $javaUpd -cp $updCp com.qimingnan.couple.updater.MainKt render-service $releaseDir
if ($LASTEXITCODE -ne 0) { throw "render-service failed ($LASTEXITCODE)" }

Write-Step 'Service couple-updater'
Install-WinswService 'couple-updater' $winsw
Expand-Template (Join-Path $pkg 'service\couple-updater.xml.in') (Join-Path $servicesDir 'couple-updater.xml') @{ INSTALL_DIR = $InstallDir }

if ($tunnelRequested) {
    Write-Step "Cloudflare Tunnel (cloudflared $($pins['CLOUDFLARED_VERSION']), locally managed)"
    if ($TunnelId -notmatch '^[0-9a-fA-F-]{36}$') { throw "-TunnelId must be the tunnel UUID, got '$TunnelId'" }
    $cfExe = Install-Cloudflared (Join-Path $InstallDir 'cloudflared') $pins $cfg['HTTPS_PROXY']
    $credDest = Join-Path $configDir "cloudflared\$TunnelId.json"
    Copy-Item -LiteralPath $TunnelJsonFile -Destination $credDest -Force
    Protect-File $credDest
    $cfConfig = Join-Path $configDir 'cloudflared\config.yml'
    if ((Test-Path -LiteralPath $cfConfig) -and -not $ForceTunnelConfig) {
        Write-Host '    config\cloudflared\config.yml exists (kept; use -ForceTunnelConfig to re-render from the package)'
    } else {
        if (Test-Path -LiteralPath $cfConfig) { Copy-Item -LiteralPath $cfConfig -Destination "$cfConfig.bak" -Force }
        Expand-Template (Join-Path $pkg 'cloudflared\config.yml.in') $cfConfig @{ TUNNEL_ID = $TunnelId; HOSTNAME = $TunnelHostname; CREDENTIALS_FILE = $credDest }
        Write-Host "    wrote $cfConfig"
    }
    & $cfExe tunnel --config $cfConfig ingress validate
    if ($LASTEXITCODE -ne 0) { throw 'cloudflared ingress validate failed' }
    Install-WinswService 'couple-cloudflared' $winsw
    Expand-Template (Join-Path $pkg 'service\couple-cloudflared.xml.in') (Join-Path $servicesDir 'couple-cloudflared.xml') @{ INSTALL_DIR = $InstallDir; CLOUDFLARED_VERSION = $pins['CLOUDFLARED_VERSION'] }
}

if ($NoStart) {
    Write-Step 'Done (-NoStart: services registered but not started)'
    return
}

Write-Step 'Starting services'
Stop-CoupleService 'couple-backend'
Start-Service -Name 'couple-backend'
Start-Service -Name 'couple-updater'
if ((Get-ServiceState 'couple-cloudflared') -ne 'Missing') {
    if ((Get-ServiceState 'couple-cloudflared') -eq 'Running') { Restart-Service -Name 'couple-cloudflared' } else { Start-Service -Name 'couple-cloudflared' }
}

$healthUrl = "http://127.0.0.1:$port/api/v1/health"
Write-Host "    waiting for $healthUrl (up to 120 s)"
$deadline = (Get-Date).AddSeconds(120)
$ok = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { Start-Sleep -Seconds 3 }
}
if ($ok) {
    Write-Host "    backend healthy: $($r.Content)" -ForegroundColor Green
} else {
    Write-Warning "backend not healthy yet - check $InstallDir\logs\couple-backend.out.log / .err.log"
}
Write-Host ''
Write-Host 'Next: copy DEPLOY_HOOK_SECRET into the GitHub secret of the same name:'
Write-Host "    Select-String -Path '$envFile' -Pattern '^DEPLOY_HOOK_SECRET='"
