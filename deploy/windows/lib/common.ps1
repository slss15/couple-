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

# Reads a rendered config\cloudflared\config.yml (pure): tunnel UUID, first public hostname, credentials file,
# metrics address and the loopback ports of the ingress services (in file order, distinct).
function Get-TunnelConfigInfo([string]$Path) {
    $info = [pscustomobject]@{ TunnelId = ''; Hostname = ''; CredentialsFile = ''; Metrics = ''; Ports = @() }
    if (-not (Test-Path -LiteralPath $Path)) { return $info }
    $ports = New-Object System.Collections.Generic.List[int]
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*#') { continue }
        if (-not $info.TunnelId -and $line -match '^\s*tunnel:\s*([0-9A-Fa-f-]{36})\s*$') { $info.TunnelId = $Matches[1].ToLowerInvariant() }
        if (-not $info.Hostname -and $line -match '^\s*-?\s*hostname:\s*(\S+)\s*$') { $info.Hostname = $Matches[1].ToLowerInvariant() }
        if (-not $info.CredentialsFile -and $line -match '^\s*credentials-file:\s*(.+?)\s*$') { $info.CredentialsFile = $Matches[1].Trim('"', "'") }
        if (-not $info.Metrics -and $line -match '^\s*metrics:\s*(\S+)\s*$') { $info.Metrics = $Matches[1] }
        if ($line -match '^\s*-?\s*service:\s*https?://(127\.0\.0\.1|localhost):(\d{1,5})') {
            $p = [int]$Matches[2]
            if (-not $ports.Contains($p)) { $ports.Add($p) }
        }
    }
    $info.Ports = $ports.ToArray()
    return $info
}

# Ports the rendered tunnel config must point at, from couple.env values (pure). Order: backend, MQTT WS, updater hook.
function Get-ExpectedTunnelPort([hashtable]$Cfg) {
    $p = 8080; $m = 8083; $h = 9000
    if ($Cfg -and $Cfg['PORT'] -match '^\d{1,5}$') { $p = [int]$Cfg['PORT'] }
    if ($Cfg -and $Cfg['MQTT_WS_PORT'] -match '^\d{1,5}$') { $m = [int]$Cfg['MQTT_WS_PORT'] }
    if ($Cfg -and $Cfg['HOOK_PORT'] -match '^\d{1,5}$') { $h = [int]$Cfg['HOOK_PORT'] }
    return [pscustomobject]@{ Backend = $p; MqttWs = $m; Hook = $h }
}

# True when the ingress ports of a rendered config.yml are exactly the expected ones (pure).
function Test-TunnelPortsMatch([int[]]$Actual, $Expected) {
    $want = @($Expected.Backend, $Expected.MqttWs, $Expected.Hook) | Sort-Object -Unique
    $have = @($Actual) | Sort-Object -Unique
    if (@($want).Count -ne @($have).Count) { return $false }
    for ($i = 0; $i -lt @($want).Count; $i++) { if (@($want)[$i] -ne @($have)[$i]) { return $false } }
    return $true
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

# ---- doctor helpers (pure) -------------------------------------------------------------------------------------

# One doctor row. Newlines are folded and the detail is capped at 160 characters so the table stays readable.
function New-DoctorResult([string]$Item, [string]$Status, [string]$Detail, [string]$Hint) {
    $d = ([string]$Detail -replace '\s*[\r\n]+\s*', ' ').Trim()
    if ($d.Length -gt 160) { $d = $d.Substring(0, 157) + '...' }
    return [pscustomobject]@{ Item = $Item; Status = $Status; Detail = $d; Hint = $Hint }
}

# Removes user:password from a proxy URL or host string (pure): http://u:p@h:1 -> http://***@h:1.
function Hide-UrlCredential([string]$Value) {
    if (-not $Value) { return $Value }
    return ($Value -replace '(^|[=;\s]|://)[^/@\s;=]+@', '$1***@')
}

# 1 when any result is FAIL, else 0.
function Get-DoctorExitCode([object[]]$Results) {
    foreach ($r in @($Results)) { if ($null -ne $r -and $r.Status -eq 'FAIL') { return 1 } }
    return 0
}

# PASS / WARN / FAIL for a value where bigger is better (e.g. free GB): below $FailBelow -> FAIL, below $WarnBelow -> WARN.
function Get-ThresholdStatus([double]$Value, [double]$FailBelow, [double]$WarnBelow) {
    if ($Value -lt $FailBelow) { return 'FAIL' }
    if ($Value -lt $WarnBelow) { return 'WARN' }
    return 'PASS'
}

# Windows build -> PASS (>= 14393: Server 2016 / Win10 1607), WARN (>= 9600: Server 2012 R2 / 8.1), else FAIL.
function Get-OsBuildStatus([int]$Build) {
    if ($Build -ge 14393) { return 'PASS' }
    if ($Build -ge 9600) { return 'WARN' }
    return 'FAIL'
}

# Terminal display width: East Asian wide / fullwidth characters count as 2 columns.
function Get-DisplayWidth([string]$Text) {
    if (-not $Text) { return 0 }
    $w = 0
    foreach ($ch in $Text.ToCharArray()) {
        $c = [int]$ch
        if (($c -ge 0x1100 -and $c -le 0x115F) -or ($c -ge 0x2E80 -and $c -le 0xA4CF) -or ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
            ($c -ge 0xF900 -and $c -le 0xFAFF) -or ($c -ge 0xFE30 -and $c -le 0xFE4F) -or ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { $w += 2 } else { $w += 1 }
    }
    return $w
}

function Format-PaddedCell([string]$Text, [int]$Width) {
    if ($null -eq $Text) { $Text = '' }
    $pad = $Width - (Get-DisplayWidth $Text)
    if ($pad -le 0) { return $Text }
    return $Text + (' ' * $pad)
}

# Renders doctor results as text lines: a header row, a rule and one row per result (status, item, detail, hint).
function Format-DoctorTable([object[]]$Results, [string[]]$Headers) {
    $rows = @($Results | Where-Object { $null -ne $_ })
    $w1 = Get-DisplayWidth $Headers[0]; $w2 = Get-DisplayWidth $Headers[1]; $w3 = Get-DisplayWidth $Headers[2]
    foreach ($r in $rows) {
        $w1 = [Math]::Max($w1, (Get-DisplayWidth $r.Status))
        $w2 = [Math]::Max($w2, (Get-DisplayWidth $r.Item))
        $w3 = [Math]::Max($w3, [Math]::Min(60, (Get-DisplayWidth $r.Detail)))
    }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(((Format-PaddedCell $Headers[0] $w1), (Format-PaddedCell $Headers[1] $w2), (Format-PaddedCell $Headers[2] $w3), $Headers[3]) -join ' | ')
    $lines.Add(((('-' * $w1), ('-' * $w2), ('-' * $w3), ('-' * [Math]::Max(4, (Get-DisplayWidth $Headers[3])))) -join '-+-'))
    foreach ($r in $rows) {
        $hint = if ($r.Hint) { $r.Hint } else { '-' }
        $lines.Add(((Format-PaddedCell $r.Status $w1), (Format-PaddedCell $r.Item $w2), (Format-PaddedCell $r.Detail $w3), $hint) -join ' | ')
    }
    return $lines.ToArray()
}

# Seconds the local clock is ahead (+) or behind (-) an HTTP Date header (RFC 1123); $null when unparsable.
function Get-ClockSkew([string]$DateHeader, [DateTime]$NowUtc) {
    if (-not $DateHeader) { return $null }
    $parsed = [DateTime]::MinValue
    $styles = [Globalization.DateTimeStyles]::AdjustToUniversal -bor [Globalization.DateTimeStyles]::AssumeUniversal
    if (-not [DateTime]::TryParseExact($DateHeader.Trim(), 'r', [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$parsed)) { return $null }
    return [Math]::Round(($NowUtc.ToUniversalTime() - $parsed).TotalSeconds, 1)
}

# Listening TCP sockets from `netstat -ano` output (pure). Language independent: a listening row has the foreign
# address 0.0.0.0:0 or [::]:0. Returns objects with Address, Port, ProcessId.
function ConvertFrom-NetstatListen([string[]]$Lines) {
    $out = New-Object System.Collections.Generic.List[object]
    foreach ($line in @($Lines)) {
        $t = ([string]$line).Trim() -split '\s+'
        if ($t.Count -lt 5 -or $t[0] -ne 'TCP') { continue }
        if ($t[2] -ne '0.0.0.0:0' -and $t[2] -ne '[::]:0') { continue }
        $i = $t[1].LastIndexOf(':')
        if ($i -lt 0) { continue }
        $port = 0; $procId = 0
        if (-not [int]::TryParse($t[1].Substring($i + 1), [ref]$port)) { continue }
        if (-not [int]::TryParse($t[$t.Count - 1], [ref]$procId)) { continue }
        $out.Add([pscustomobject]@{ Address = $t[1].Substring(0, $i); Port = $port; ProcessId = $procId })
    }
    return $out.ToArray()
}

# Proxy server from the WinHTTP settings blob (HKLM ...\Internet Settings\Connections\WinHttpSettings); '' = direct.
function ConvertFrom-WinHttpSetting([byte[]]$Bytes) {
    if ($null -eq $Bytes -or $Bytes.Length -lt 16) { return '' }
    $flags = [BitConverter]::ToInt32($Bytes, 8)
    $len = [BitConverter]::ToInt32($Bytes, 12)
    if (($flags -band 2) -eq 0 -or $len -le 0 -or (16 + $len) -gt $Bytes.Length) { return '' }
    return [Text.Encoding]::ASCII.GetString($Bytes, 16, $len)
}

# Verdict for one port. $Owner = name of the service that owns the listener ('' = not a service), $Process = image name.
# Returns PASS when free or owned by an allowed service (or by a mysqld when $AllowMysql), else FAIL.
function Get-PortStatus([bool]$Listening, [string]$Owner, [string]$Process, [string[]]$AllowedServices, [bool]$AllowMysql) {
    if (-not $Listening) { return 'PASS' }
    if ($Owner -and (@($AllowedServices) -contains $Owner)) { return 'PASS' }
    if ($AllowMysql -and ($Process -match '^mysqld(-nt)?(\.exe)?$')) { return 'PASS' }
    return 'FAIL'
}

# ---- versions / anti-downgrade (pure) --------------------------------------------------------------------------

# Parses X.Y.Z[-pre][+build] (optional leading v); $null for anything else (e.g. edge-<sha>).
function ConvertTo-SemVer([string]$Version) {
    if ($null -eq $Version) { return $null }
    $m = [regex]::Match($Version.Trim(), '^v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$')
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{ Major = [long]$m.Groups[1].Value; Minor = [long]$m.Groups[2].Value; Patch = [long]$m.Groups[3].Value; Pre = $m.Groups[4].Value }
}

# -1 / 0 / 1 comparing two semantic versions; $null when either is not X.Y.Z (incomparable by name).
function Compare-SemVer([string]$A, [string]$B) {
    $x = ConvertTo-SemVer $A; $y = ConvertTo-SemVer $B
    if ($null -eq $x -or $null -eq $y) { return $null }
    foreach ($k in 'Major', 'Minor', 'Patch') {
        if ($x.$k -lt $y.$k) { return -1 }
        if ($x.$k -gt $y.$k) { return 1 }
    }
    if ($x.Pre -eq $y.Pre) { return 0 }
    if (-not $x.Pre) { return 1 }
    if (-not $y.Pre) { return -1 }
    $xa = $x.Pre.Split('.'); $ya = $y.Pre.Split('.')
    for ($i = 0; $i -lt [Math]::Min($xa.Count, $ya.Count); $i++) {
        $xn = 0L; $yn = 0L
        $xi = [long]::TryParse($xa[$i], [ref]$xn); $yi = [long]::TryParse($ya[$i], [ref]$yn)
        if ($xi -and $yi) { if ($xn -ne $yn) { if ($xn -lt $yn) { return -1 } else { return 1 } }; continue }
        if ($xi) { return -1 }
        if ($yi) { return 1 }
        $c = [string]::CompareOrdinal($xa[$i], $ya[$i])
        if ($c -ne 0) { if ($c -lt 0) { return -1 } else { return 1 } }
    }
    if ($xa.Count -lt $ya.Count) { return -1 }
    if ($xa.Count -gt $ya.Count) { return 1 }
    return 0
}

# Whether installing $NewVersion over $CurrentVersion is a downgrade. Same rule as the updater (VersionOrder.kt):
# both X.Y.Z -> semver; otherwise the commit epochs from BUILD_INFO (0 = unknown) decide; unknown -> not a downgrade.
# Returns 'downgrade', 'same', 'upgrade' or 'unknown'.
function Get-VersionRelation([string]$NewVersion, [long]$NewEpoch, [string]$CurrentVersion, [long]$CurrentEpoch) {
    if (-not $CurrentVersion) { return 'unknown' }
    if ($NewVersion -ceq $CurrentVersion) { return 'same' }
    $c = Compare-SemVer $NewVersion $CurrentVersion
    if ($null -eq $c) {
        if ($NewEpoch -le 0 -or $CurrentEpoch -le 0) { return 'unknown' }
        if ($NewEpoch -lt $CurrentEpoch) { return 'downgrade' }
        if ($NewEpoch -gt $CurrentEpoch) { return 'upgrade' }
        return 'unknown'
    }
    if ($c -lt 0) { return 'downgrade' }
    if ($c -gt 0) { return 'upgrade' }
    return 'same'
}

# COMMIT_EPOCH from a release's BUILD_INFO file (0 when missing or invalid).
function Get-BuildEpoch([string]$ReleaseDir) {
    $v = (Read-KeyValueFile (Join-Path $ReleaseDir 'BUILD_INFO'))['COMMIT_EPOCH']
    $n = 0L
    if ($v -and [long]::TryParse($v, [ref]$n) -and $n -gt 0) { return $n }
    return 0L
}

# Points data\updater\updater.json at $Version after install.ps1 switched releases by hand (pure: JSON in, JSON out).
# currentSha256 is cleared: the updater adopts the sha256 of the matching remote build instead of treating the
# manually installed copy as "same version, different bytes". Unknown fields are kept.
function Update-UpdaterStateJson([string]$Json, [string]$Version) {
    $state = $null
    if ($Json -and $Json.Trim()) { try { $state = ConvertFrom-Json $Json } catch { $state = $null } }
    if ($null -eq $state) { $state = New-Object psobject }
    $names = @($state.PSObject.Properties | ForEach-Object { $_.Name })
    $current = [pscustomobject]@{ server = $Version }
    if ($names -contains 'current') { $state.current = $current } else { $state | Add-Member -NotePropertyName current -NotePropertyValue $current }
    if ($names -contains 'currentSha256') { $state.currentSha256 = $null } else { $state | Add-Member -NotePropertyName currentSha256 -NotePropertyValue $null }
    return (ConvertTo-Json $state -Depth 20 -Compress)
}

# ---- watchdog helpers ------------------------------------------------------------------------------------------

# Sum of a Prometheus metric (all label sets) in a /metrics text; $null when absent (pure).
function ConvertFrom-PrometheusMetric([string]$Text, [string]$Name) {
    if (-not $Text) { return $null }
    $sum = 0.0; $found = $false
    $re = '^' + [regex]::Escape($Name) + '(\{[^}]*\})?\s+([-+0-9.eE]+|NaN)\s*(\d+)?\s*$'
    foreach ($line in ($Text -split "`r?`n")) {
        $m = [regex]::Match($line, $re)
        if (-not $m.Success) { continue }
        $v = 0.0
        if ([double]::TryParse($m.Groups[2].Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$v)) {
            $sum += $v; $found = $true
        }
    }
    if ($found) { return $sum }
    return $null
}

# Updates a consecutive-failure counter in $State (hashtable) and returns the new count (0 after a success).
function Step-FailCounter([hashtable]$State, [string]$Key, [bool]$Ok) {
    if ($Ok) { $State[$Key] = 0; return 0 }
    $n = 0
    if ($State.ContainsKey($Key)) { $n = [int]$State[$Key] }
    $n++
    $State[$Key] = $n
    return $n
}

# True when no restart happened within $CooldownMinutes before $NowUtc. $LastUtc is ISO-8601 text or empty.
function Test-RestartAllowed([string]$LastUtc, [DateTime]$NowUtc, [int]$CooldownMinutes) {
    if (-not $LastUtc) { return $true }
    $t = [DateTime]::MinValue
    if (-not [DateTime]::TryParse($LastUtc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal, [ref]$t)) { return $true }
    return (($NowUtc.ToUniversalTime() - $t).TotalMinutes -ge $CooldownMinutes)
}

# Rotates $Path when it is at least $MaxBytes: path.(Keep-1) -> path.Keep (dropped), ..., path -> path.1.
function Invoke-LogRotation([string]$Path, [long]$MaxBytes, [int]$Keep) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -lt $MaxBytes) { return $false }
    $oldest = "$Path.$Keep"
    if (Test-Path -LiteralPath $oldest) { Remove-Item -LiteralPath $oldest -Force }
    for ($i = $Keep - 1; $i -ge 1; $i--) {
        if (Test-Path -LiteralPath "$Path.$i") { Move-Item -LiteralPath "$Path.$i" -Destination "$Path.$($i + 1)" -Force }
    }
    Move-Item -LiteralPath $Path -Destination "$Path.1" -Force
    return $true
}

# Task Scheduler 1.2 XML for the watchdog: every $IntervalMinutes as SYSTEM, no overlap, 5 min limit, no battery
# conditions, runs missed starts. $StartBoundary like 2026-01-01T00:00:00 (local).
function New-WatchdogTaskXml([string]$Command, [string]$Arguments, [string]$StartBoundary, [int]$IntervalMinutes) {
    $c = [Security.SecurityElement]::Escape($Command)
    $a = [Security.SecurityElement]::Escape($Arguments)
    return @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Couple watchdog: cloudflared tunnel, couple-backend and MySQL health (deploy/windows/watchdog.ps1)</Description>
  </RegistrationInfo>
  <Triggers>
    <TimeTrigger>
      <Repetition>
        <Interval>PT${IntervalMinutes}M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
      <StartBoundary>$StartBoundary</StartBoundary>
      <Enabled>true</Enabled>
    </TimeTrigger>
    <BootTrigger>
      <Repetition>
        <Interval>PT${IntervalMinutes}M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
      <Delay>PT3M</Delay>
      <Enabled>true</Enabled>
    </BootTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>S-1-5-18</UserId>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT5M</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$c</Command>
      <Arguments>$a</Arguments>
    </Exec>
  </Actions>
</Task>
"@
}

# ---- side-effecting helpers shared by doctor.ps1 / watchdog.ps1 --------------------------------------------------

# GET (or HEAD) $Url without throwing. Returns Status (0 = no HTTP response), Body (<= 64 KB), Date header, Error.
# HttpWebRequest works the same on Windows PowerShell 5.1 and PowerShell 7.
function Invoke-HttpProbe([string]$Url, [int]$TimeoutSec = 10, [string]$Proxy, [switch]$Direct, [string]$Method = 'GET') {
    Enable-Tls12
    $res = [pscustomobject]@{ Status = 0; Body = ''; Date = ''; Error = ''; Headers = @{} }
    $resp = $null
    try {
        $req = [Net.HttpWebRequest][Net.WebRequest]::Create($Url)
        $req.Method = $Method
        $req.Timeout = $TimeoutSec * 1000
        $req.ReadWriteTimeout = $TimeoutSec * 1000
        $req.AllowAutoRedirect = $false
        $req.UserAgent = 'couple-doctor'
        if ($Direct) { $req.Proxy = $null }
        elseif ($Proxy) { $req.Proxy = New-Object Net.WebProxy($Proxy, $true) }
        try { $resp = [Net.HttpWebResponse]$req.GetResponse() }
        catch [Net.WebException] {
            if ($null -ne $_.Exception.Response) { $resp = [Net.HttpWebResponse]$_.Exception.Response }
            else { $res.Error = $_.Exception.Message }
        }
        if ($null -ne $resp) {
            $res.Status = [int]$resp.StatusCode
            $res.Date = [string]$resp.Headers['Date']
            foreach ($k in $resp.Headers.AllKeys) { $res.Headers[$k] = [string]$resp.Headers[$k] }
            if ($Method -ne 'HEAD') {
                $stream = $resp.GetResponseStream()
                $reader = New-Object IO.StreamReader($stream)
                $buf = New-Object char[] 65536
                $n = $reader.Read($buf, 0, $buf.Length)
                if ($n -gt 0) { $res.Body = New-Object string($buf, 0, $n) }
                $reader.Dispose()
            }
        }
    } catch {
        $res.Error = $_.Exception.Message
    } finally {
        if ($null -ne $resp) { $resp.Close() }
    }
    return $res
}

# TCP connect with a timeout; returns '' on success, else the error text.
function Test-TcpConnect([string]$TargetHost, [int]$Port, [int]$TimeoutSec = 5) {
    $c = New-Object Net.Sockets.TcpClient
    try {
        $ar = $c.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne($TimeoutSec * 1000)) { return "timeout after $TimeoutSec s" }
        $c.EndConnect($ar)
        return ''
    } catch {
        $e = $_.Exception
        while ($null -ne $e.InnerException) { $e = $e.InnerException }
        return $e.Message
    } finally {
        $c.Close()
    }
}

# Restricts a directory tree to Administrators + SYSTEM (inherited by everything below; well-known SIDs).
function Protect-Directory([string]$Path) {
    & icacls.exe $Path /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)(F)' '*S-1-5-18:(OI)(CI)(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls failed on $Path" }
}
