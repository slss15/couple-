<#
.SYNOPSIS
  Shared helpers for install.ps1 and setup.ps1 (dot-sourced: . "$PSScriptRoot\lib\common.ps1").

.DESCRIPTION
  Must stay compatible with Windows PowerShell 5.1 and PowerShell 7. Keep this file ASCII-only.
  Pure helpers (no side effects outside the given path) are unit-tested by deploy/windows/tests/test-common.ps1.
#>

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

function Enable-Tls12 {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

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

# Sets KEY=value in a couple.env-style file. With -OnlyIfEmpty an existing non-empty value is kept.
# Returns $true when the file was changed. A missing key is appended; comments and other lines are preserved.
function Set-EnvValue([string]$Path, [string]$Key, [string]$Value, [switch]$OnlyIfEmpty) {
    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $Path) { $lines.AddRange([string[]][IO.File]::ReadAllLines($Path)) }
    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*='
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $current = $lines[$i].Substring($lines[$i].IndexOf('=') + 1).Trim()
            if ($OnlyIfEmpty -and $current -ne '') { return $false }
            if ($current -ceq $Value) { return $false }
            $lines[$i] = "$Key=$Value"
            Write-Utf8NoBom $Path (($lines -join "`r`n") + "`r`n")
            return $true
        }
    }
    $lines.Add("$Key=$Value")
    Write-Utf8NoBom $Path (($lines -join "`r`n") + "`r`n")
    return $true
}

# Sets KEY=value only when the key is missing or empty. Never overwrites an existing non-empty value.
function Set-EnvIfEmpty([string]$Path, [string]$Key, [string]$Value) {
    return (Set-EnvValue -Path $Path -Key $Key -Value $Value -OnlyIfEmpty)
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

# Downloads the first working URL of $Urls to $Dest and checks its sha256. Keeps an already-verified $Dest.
function Get-PinnedFile([string[]]$Urls, [string]$Sha256, [string]$Dest, [string]$Proxy) {
    $want = $Sha256.ToLowerInvariant()
    if ((Test-Path -LiteralPath $Dest) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $Dest).Hash.ToLowerInvariant() -eq $want) {
        return $Dest
    }
    Enable-Tls12
    $tmp = "$Dest.download"
    $lastError = $null
    foreach ($url in $Urls) {
        $iwr = @{ Uri = $url; OutFile = $tmp; UseBasicParsing = $true }
        if ($Proxy) { $iwr['Proxy'] = $Proxy }
        Write-Host "    downloading $url"
        try {
            Invoke-WebRequest @iwr
        } catch {
            $lastError = $_.Exception.Message
            Write-Host "    failed: $lastError"
            continue
        }
        $got = (Get-FileHash -Algorithm SHA256 -LiteralPath $tmp).Hash.ToLowerInvariant()
        if ($got -ne $want) { Remove-Item -LiteralPath $tmp -Force; throw "sha256 mismatch for ${url}: $got (want $want)" }
        Move-Item -LiteralPath $tmp -Destination $Dest -Force
        return $Dest
    }
    throw "download failed ($($Urls -join ', ')): $lastError"
}

function Install-Cloudflared([string]$Dir, [hashtable]$Pins, [string]$Proxy) {
    $exe = Join-Path $Dir 'cloudflared.exe'
    $want = $Pins['CLOUDFLARED_WINDOWS_AMD64_SHA256'].ToLowerInvariant()
    $have = if (Test-Path -LiteralPath $exe) { (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash.ToLowerInvariant() } else { '' }
    if ($have -eq $want) { return $exe }
    Stop-CoupleService 'couple-cloudflared'
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $url = "https://github.com/cloudflare/cloudflared/releases/download/$($Pins['CLOUDFLARED_VERSION'])/cloudflared-windows-amd64.exe"
    Get-PinnedFile -Urls @($url) -Sha256 $want -Dest $exe -Proxy $Proxy | Out-Null
    return $exe
}

# Creates config\couple.env from the package example when missing and fills the generated secrets that are empty.
function Initialize-CoupleEnv([string]$EnvFile, [string]$Example, [string[]]$SecretKeys) {
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        Copy-Item -LiteralPath $Example -Destination $EnvFile
        Write-Host '    created from couple.env.example'
    }
    foreach ($k in $SecretKeys) {
        if (Set-EnvIfEmpty $EnvFile $k (Get-RandomHex 32)) { Write-Host "    generated $k" } else { Write-Host "    kept existing $k" }
    }
    Protect-File $EnvFile
}

# Runs a native program, capturing stdout+stderr as text without tripping $ErrorActionPreference = 'Stop'
# (Windows PowerShell 5.1 turns redirected native stderr into terminating errors otherwise).
# Stdout and Stderr are kept apart (e.g. `cloudflared ... -o json` logs to stderr); Output has both, in order.
function Invoke-NativeCapture([string]$Exe, [string[]]$Arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $all = New-Object System.Collections.Generic.List[string]
    $stdout = New-Object System.Collections.Generic.List[string]
    $stderr = New-Object System.Collections.Generic.List[string]
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object {
            $line = "$_"
            $all.Add($line)
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $stderr.Add($line) } else { $stdout.Add($line) }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    return [pscustomobject]@{
        ExitCode = $code
        Output   = ($all -join "`n")
        Stdout   = ($stdout -join "`n")
        Stderr   = ($stderr -join "`n")
    }
}

function Initialize-CoupleLayout([string]$InstallDir) {
    foreach ($d in @('config', 'config\cloudflared', 'data\releases', 'data\mqtt', 'data\updater', 'logs', 'releases', 'services', 'updater', 'cloudflared')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir $d) | Out-Null
    }
}

# Reads the tunnel UUID and the (first) public hostname from a rendered config\cloudflared\config.yml (pure).
function Get-TunnelConfigInfo([string]$Path) {
    $info = [pscustomobject]@{ TunnelId = ''; Hostname = '' }
    if (-not (Test-Path -LiteralPath $Path)) { return $info }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if (-not $info.TunnelId -and $line -match '^\s*tunnel:\s*([0-9A-Fa-f-]{36})\s*$') { $info.TunnelId = $Matches[1].ToLowerInvariant() }
        if (-not $info.Hostname -and $line -match '^\s*-?\s*hostname:\s*(\S+)\s*$') { $info.Hostname = $Matches[1].ToLowerInvariant() }
    }
    return $info
}

# ---- sizing (pure) --------------------------------------------------------------------------------------------

# Memory profile for a host with $TotalBytes of RAM: <=2.5 GB small, <=5 GB medium, else large.
function Get-MemoryProfile([long]$TotalBytes) {
    $gb = [double]$TotalBytes / 1GB
    if ($gb -le 2.5) { return [pscustomobject]@{ Name = 'small'; InnodbBufferPool = '256M'; JvmXmx = '384m' } }
    if ($gb -le 5) { return [pscustomobject]@{ Name = 'medium'; InnodbBufferPool = '512M'; JvmXmx = '640m' } }
    return [pscustomobject]@{ Name = 'large'; InnodbBufferPool = '1G'; JvmXmx = '1024m' }
}

$script:CoupleJavaOptsTemplate = '-Xms64m -Xmx{0} -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError'

function Get-DefaultJavaOption([string]$Xmx) { return ($script:CoupleJavaOptsTemplate -f $Xmx) }

# True when JAVA_OPTS is empty, the shipped default (-Xmx512m) or one of the values setup.ps1 writes;
# anything else counts as customized by the user and is never touched.
function Test-JavaOptsAutoManaged([string]$Value) {
    if ($null -eq $Value -or $Value.Trim() -eq '') { return $true }
    $v = $Value.Trim()
    foreach ($x in @('512m', '384m', '640m', '1024m')) {
        if ($v -ceq (Get-DefaultJavaOption $x)) { return $true }
    }
    return $false
}

# Normalizes user input like "https://Couple.Example.com/" to "couple.example.com"; returns $null when invalid.
function ConvertTo-Hostname([string]$Value) {
    if ($null -eq $Value) { return $null }
    $h = $Value.Trim().ToLowerInvariant()
    $h = $h -replace '^[a-z]+://', ''
    $h = $h.TrimEnd('/')
    if ($h.Length -gt 253) { return $null }
    if ($h -notmatch '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$') { return $null }
    return $h
}

# my.ini for the bundled MySQL (ASCII; forward slashes so backslashes are never parsed as escapes).
function New-MysqlIni([string]$BaseDir, [string]$DataDir, [string]$BufferPool) {
    $b = $BaseDir.Replace('\', '/')
    $d = $DataDir.Replace('\', '/')
    return @"
# Written by Couple setup.ps1. Safe to edit; setup.ps1 never overwrites an existing my.ini.
[mysqld]
basedir=$b
datadir=$d
port=3306
bind-address=127.0.0.1
mysqlx=OFF
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=$BufferPool
performance_schema=OFF
max_connections=50
log-error=$d/mysqld.err

[client]
port=3306
default-character-set=utf8mb4
"@
}
