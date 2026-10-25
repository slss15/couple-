<#
.SYNOPSIS
  Installs or upgrades the Couple server on Windows (no Docker/WSL/Hyper-V needed).

.DESCRIPTION
  First-time setup: use setup.ps1 (wizard: MySQL, secrets, Cloudflare Tunnel, then calls this script).
  Run from the extracted couple-server-windows-x64-<version> folder in an elevated PowerShell (5.1 or 7):

    powershell -ExecutionPolicy Bypass -File .\install.ps1
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -TunnelId <UUID> -TunnelHostname couple.example.com -TunnelJsonFile C:\Users\me\.cloudflared\<UUID>.json

  Refuses to install a package older than the running release (releases\CURRENT; X.Y.Z by semver, other names by
  BUILD_INFO commit time) unless -AllowDowngrade is given. Manual rollback:
    C:\couple\releases\<older-version>\install.ps1 -AllowDowngrade

  What it does (idempotent; safe to re-run, which is also how the updater itself is upgraded):
    1. creates <InstallDir>\{config,data,logs,releases,services,updater,cloudflared}
    2. creates config\couple.env from the example if missing and fills JWT_SECRET, MQTT_HOOK_SECRET and
       DEPLOY_HOOK_SECRET with random values when they are empty. Existing values are never overwritten.
    3. copies this release to releases\<version>, renders services\couple-backend.xml and sets releases\CURRENT
    4. refreshes the updater runtime (updater\jre, updater\lib) and its service config
    5. registers the WinSW services couple-backend and couple-updater (and couple-cloudflared when tunnel
       parameters are given: downloads the pinned cloudflared, sha256-verified, and installs config\cloudflared\config.yml)
    6. installs <InstallDir>\watchdog\ and registers the scheduled task \Couple\couple-watchdog (every 2 min as
       SYSTEM; skipped with -NoWatchdog, and with -NoStart unless the task already exists)
    7. starts the services (unless -NoStart) and waits for GET http://127.0.0.1:<PORT>/api/v1/health
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
    [switch]$CloudflaredOnly,
    # Do not register the watchdog scheduled task (\Couple\couple-watchdog). Implied by -NoStart for a fresh install.
    [switch]$NoWatchdog,
    # Install this package even when it is older than the release in releases\CURRENT (manual rollback).
    [switch]$AllowDowngrade
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

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

$script:WatchdogTask = '\Couple\couple-watchdog'

function Install-Watchdog([string]$Pkg, [string]$Dir, [bool]$Register) {
    $wd = Join-Path $Dir 'watchdog'
    New-Item -ItemType Directory -Force -Path (Join-Path $wd 'lib') | Out-Null
    Copy-Item -LiteralPath (Join-Path $Pkg 'watchdog.ps1') -Destination (Join-Path $wd 'watchdog.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $Pkg 'lib\common.ps1') -Destination (Join-Path $wd 'lib\common.ps1') -Force
    # The task runs these files as SYSTEM: only Administrators / SYSTEM may change them.
    Protect-Directory $wd
    $exists = (Invoke-NativeCapture 'schtasks.exe' @('/Query', '/TN', $script:WatchdogTask)).ExitCode -eq 0
    if (-not $Register -and -not $exists) { Write-Host '    watchdog task not registered (-NoWatchdog / -NoStart)'; return }
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $taskArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$wd\watchdog.ps1`" -InstallDir `"$Dir`""
    $xml = New-WatchdogTaskXml $ps $taskArgs ((Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')) 2
    $xmlFile = Join-Path $wd 'couple-watchdog.task.xml'
    [IO.File]::WriteAllText($xmlFile, $xml, [Text.Encoding]::Unicode)
    $r = Invoke-NativeCapture 'schtasks.exe' @('/Create', '/TN', $script:WatchdogTask, '/XML', $xmlFile, '/F')
    Remove-Item -LiteralPath $xmlFile -Force -ErrorAction SilentlyContinue
    if ($r.ExitCode -ne 0) { throw "schtasks /Create $script:WatchdogTask failed: $($r.Output)" }
    Write-Host "    scheduled task $script:WatchdogTask (every 2 min as SYSTEM, log logs\watchdog.log)"
}

# ---------------------------------------------------------------------------------------------------------------
Enable-Tls12
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

# Anti-downgrade (same rule as the updater): nothing has been changed yet.
$currentVersion = ''
$currentFile = Join-Path $InstallDir 'releases\CURRENT'
if (Test-Path -LiteralPath $currentFile) { $currentVersion = ([IO.File]::ReadAllText($currentFile)).Trim() }
if ($currentVersion) {
    $relation = Get-VersionRelation $version (Get-BuildEpoch $pkg) $currentVersion (Get-BuildEpoch (Join-Path $InstallDir "releases\$currentVersion"))
    if ($relation -eq 'downgrade') {
        if (-not $AllowDowngrade) {
            throw "This package ($version) is older than the installed release $currentVersion. Use the newer package, or pass -AllowDowngrade to roll back on purpose."
        }
        Write-Warning "downgrade $currentVersion -> $version (-AllowDowngrade)"
    } else {
        Write-Host "    installed: $currentVersion -> $version ($relation)"
    }
}

Write-Step 'Creating directories'
Initialize-CoupleLayout $InstallDir
# Services run as LocalSystem and load code from here: by default C:\ lets every user modify new subfolders.
Protect-Directory $InstallDir
# Tells the watchdog to keep its hands off the services while this script stops and starts them.
$watchdogLock = Join-Path $InstallDir 'data\watchdog\install.lock'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $watchdogLock) | Out-Null
Set-Content -LiteralPath $watchdogLock -Value "$PID $(Get-Date -Format o)" -Encoding ASCII
try {

    Write-Step 'Configuration (config\couple.env)'
    Initialize-CoupleEnv $envFile (Join-Path $pkg 'config\couple.env.example') @('JWT_SECRET', 'MQTT_HOOK_SECRET', 'DEPLOY_HOOK_SECRET')
    $cfg = Read-KeyValueFile $envFile
    if (-not $cfg['DB_PASSWORD']) {
        Write-Warning "DB_PASSWORD is empty in $envFile - run setup.ps1 (creates the MySQL database/user) or set it by hand (SETUP-windows.md appendix) before starting couple-backend."
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
        foreach ($item in @('jre', 'app', 'admin', 'updater', 'service', 'cloudflared', 'config', 'lib', 'winsw', 'VERSION', 'BUILD_INFO', 'versions.env', 'install.ps1', 'setup.ps1', 'uninstall.ps1', 'doctor.ps1', 'watchdog.ps1')) {
            $src = Join-Path $pkg $item
            if (-not (Test-Path -LiteralPath $src)) {
                if ($item -eq 'BUILD_INFO') { continue }   # packages built before BUILD_INFO existed
                throw "package incomplete: $item is missing in $pkg"
            }
            Copy-Item -LiteralPath $src -Destination $staging -Recurse -Force
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
    # The WinSW XML must exist before `install` (WinSW reads <id>/<executable> from it).
    Stop-CoupleService 'couple-backend'
    $env:COUPLE_HOME = $InstallDir
    $env:COUPLE_ENV_FILE = $envFile
    & $javaUpd -cp $updCp com.qimingnan.couple.updater.MainKt render-service $releaseDir
    if ($LASTEXITCODE -ne 0) { throw "render-service failed ($LASTEXITCODE)" }
    if (-not (Test-Path -LiteralPath (Join-Path $servicesDir 'couple-backend.xml'))) { throw 'render-service did not produce services\couple-backend.xml' }
    # The updater remembers the sha256 of the build it deployed; after a manual switch that value is stale.
    $updaterState = Join-Path $InstallDir 'data\updater\updater.json'
    if (Test-Path -LiteralPath $updaterState) {
        Write-Utf8NoBom $updaterState (Update-UpdaterStateJson ([IO.File]::ReadAllText($updaterState)) $version)
        Write-Host "    data\updater\updater.json -> current $version (sha256 re-learned from the next matching release)"
    }
    Install-WinswService 'couple-backend' $winsw

    Write-Step 'Service couple-updater'
    Expand-Template (Join-Path $pkg 'service\couple-updater.xml.in') (Join-Path $servicesDir 'couple-updater.xml') @{ INSTALL_DIR = $InstallDir }
    Install-WinswService 'couple-updater' $winsw

    $cfConfig = Join-Path $configDir 'cloudflared\config.yml'
    $tunnelPorts = Get-ExpectedTunnelPort $cfg
    $portValues = @{ BACKEND_PORT = $tunnelPorts.Backend; MQTT_WS_PORT = $tunnelPorts.MqttWs; HOOK_PORT = $tunnelPorts.Hook }
    if ($tunnelRequested) {
        Write-Step "Cloudflare Tunnel (cloudflared $($pins['CLOUDFLARED_VERSION']), locally managed)"
        if ($TunnelId -notmatch '^[0-9a-fA-F-]{36}$') { throw "-TunnelId must be the tunnel UUID, got '$TunnelId'" }
        $cfExe = Install-Cloudflared (Join-Path $InstallDir 'cloudflared') $pins $cfg['HTTPS_PROXY']
        if (-not (Test-Path -LiteralPath $TunnelJsonFile -PathType Leaf)) { throw "-TunnelJsonFile not found: $TunnelJsonFile" }
        $credDest = Join-Path $configDir "cloudflared\$TunnelId.json"
        if ([IO.Path]::GetFullPath($TunnelJsonFile) -ne [IO.Path]::GetFullPath($credDest)) {
            Copy-Item -LiteralPath $TunnelJsonFile -Destination $credDest -Force
        }
        Protect-File $credDest
        $portsStale = (Test-Path -LiteralPath $cfConfig) -and -not (Test-TunnelPortsMatch (Get-TunnelConfigInfo $cfConfig).Ports $tunnelPorts)
        if ((Test-Path -LiteralPath $cfConfig) -and -not $ForceTunnelConfig -and -not $portsStale) {
            Write-Host '    config\cloudflared\config.yml exists (kept; use -ForceTunnelConfig to re-render from the package)'
        } else {
            if ($portsStale) { Write-Host "    config.yml does not point at PORT=$($tunnelPorts.Backend) / MQTT_WS_PORT=$($tunnelPorts.MqttWs) / HOOK_PORT=$($tunnelPorts.Hook): re-rendering" }
            if (Test-Path -LiteralPath $cfConfig) { Copy-Item -LiteralPath $cfConfig -Destination "$cfConfig.bak" -Force }
            Expand-Template (Join-Path $pkg 'cloudflared\config.yml.in') $cfConfig (@{ TUNNEL_ID = $TunnelId; HOSTNAME = $TunnelHostname; CREDENTIALS_FILE = $credDest } + $portValues)
            Write-Host "    wrote $cfConfig"
        }
        & $cfExe tunnel --config $cfConfig ingress validate
        if ($LASTEXITCODE -ne 0) { throw 'cloudflared ingress validate failed' }
        Stop-CoupleService 'couple-cloudflared'
        Expand-Template (Join-Path $pkg 'service\couple-cloudflared.xml.in') (Join-Path $servicesDir 'couple-cloudflared.xml') @{ INSTALL_DIR = $InstallDir; CLOUDFLARED_VERSION = $pins['CLOUDFLARED_VERSION'] }
        Install-WinswService 'couple-cloudflared' $winsw
    } elseif (Test-Path -LiteralPath $cfConfig) {
        # Plain upgrade / re-run: keep the tunnel config, but make it follow PORT / MQTT_WS_PORT / HOOK_PORT.
        $info = Get-TunnelConfigInfo $cfConfig
        if (-not (Test-TunnelPortsMatch $info.Ports $tunnelPorts)) {
            Write-Step 'Cloudflare Tunnel config (ports changed)'
            if (-not ($info.TunnelId -and $info.Hostname -and $info.CredentialsFile)) {
                Write-Warning "config\cloudflared\config.yml does not point at the configured ports, but it has no tunnel/hostname/credentials-file to re-render from; fix it by hand or re-run setup.ps1"
            } else {
                Copy-Item -LiteralPath $cfConfig -Destination "$cfConfig.bak" -Force
                Expand-Template (Join-Path $pkg 'cloudflared\config.yml.in') $cfConfig (@{ TUNNEL_ID = $info.TunnelId; HOSTNAME = $info.Hostname; CREDENTIALS_FILE = $info.CredentialsFile } + $portValues)
                Write-Host "    re-rendered $cfConfig for PORT=$($tunnelPorts.Backend) / MQTT_WS_PORT=$($tunnelPorts.MqttWs) / HOOK_PORT=$($tunnelPorts.Hook) (previous: config.yml.bak)"
                $cfExe = Join-Path $InstallDir 'cloudflared\cloudflared.exe'
                if (Test-Path -LiteralPath $cfExe) {
                    & $cfExe tunnel --config $cfConfig ingress validate
                    if ($LASTEXITCODE -ne 0) { throw 'cloudflared ingress validate failed' }
                }
            }
        }
    }

    Write-Step 'Watchdog (scheduled task)'
    Install-Watchdog $pkg $InstallDir (-not $NoStart -and -not $NoWatchdog)

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
        } catch { Write-Verbose "health not ready: $($_.Exception.Message)" }
        Start-Sleep -Seconds 3
    }
    if ($ok) {
        Write-Host "    backend healthy: $($r.Content)" -ForegroundColor Green
    } else {
        Write-Warning "backend not healthy yet - check $InstallDir\logs\couple-backend.out.log / .err.log"
    }
    Write-Host ''
    Write-Host 'Next: copy DEPLOY_HOOK_SECRET into the GitHub secret of the same name:'
    Write-Host "    Select-String -Path '$envFile' -Pattern '^DEPLOY_HOOK_SECRET='"
} finally {
    Remove-Item -LiteralPath $watchdogLock -Force -ErrorAction SilentlyContinue
}
