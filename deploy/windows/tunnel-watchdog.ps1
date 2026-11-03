<#
.SYNOPSIS
  Couple tunnel watchdog: restarts couple-cloudflared when the public URL stops answering through the tunnel.
  Hosted by WinSW as the service couple-tunnelwatch (registered by install.ps1 / setup.ps1 when a tunnel is set up).

.DESCRIPTION
  Installed to <InstallDir>\watchdog\tunnel-watchdog.ps1. Every TUNNELWATCH_INTERVAL_SECONDS (default 60):
    1. GET http://127.0.0.1:<PORT>/api/v1/health (local backend)
    2. GET https://<hostname>/api/v1/health through Cloudflare (hostname from config\cloudflared\config.yml;
       HTTPS_PROXY from couple.env is honoured)
  A public failure counts only while the local health is OK (a dead backend is not the tunnel's fault). After
  TUNNELWATCH_FAILURES (default 3) consecutive counted failures it restarts couple-cloudflared - at most once per
  TUNNELWATCH_COOLDOWN_MINUTES (default 5, shared with watchdog.ps1 through data\watchdog\cloudflared-restart.txt).
  With protocol http2 the edge connections can die silently while cloudflared itself keeps running; this catches it
  within about 3 minutes.

  Log: <InstallDir>\logs\tunnel-watchdog.log (rotated at 1 MB, 5 files; one line per failure / action, plus an
  "ok" heartbeat every 60 rounds). Pauses like watchdog.ps1: config\watchdog.pause, WATCHDOG_ENABLED=false, or a
  running install.ps1 (data\watchdog\install.lock). -Once runs a single round and prints the log lines.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    # Single round, log lines echoed to the console (manual runs / tests).
    [switch]$Once
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$logFile = Join-Path $InstallDir 'logs\tunnel-watchdog.log'
$stateDir = Join-Path $InstallDir 'data\watchdog'
$script:echo = [bool]$Once

function Write-TunnelWatchLog([string]$Level, [string]$Message) {
    $Message = ($Message -replace '\s*[\r\n]+\s*', ' ').Trim()
    $line = '{0} {1,-5} {2}' -f [DateTime]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture), $Level, $Message
    try { [IO.File]::AppendAllText($logFile, $line + "`r`n", (New-Object Text.UTF8Encoding $false)) } catch { Write-Verbose "log write failed: $($_.Exception.Message)" }
    if ($script:echo) { Write-Host $line }
}

function Get-IntSetting([hashtable]$Cfg, [string]$Key, [int]$Default, [int]$Min) {
    if ($Cfg[$Key] -match '^\d{1,5}$') { return [Math]::Max($Min, [int]$Cfg[$Key]) }
    return $Default
}

# One round; returns the new consecutive-failure count.
function Invoke-TunnelWatchRound([int]$Failures, [int]$Round) {
    try { Invoke-LogRotation $logFile 1MB 5 | Out-Null } catch { Write-Verbose "rotation failed: $($_.Exception.Message)" }
    $cfg = Read-KeyValueFile (Join-Path $InstallDir 'config\couple.env')
    if ($cfg['WATCHDOG_ENABLED'] -match '^(?i:false|0|no|off)$' -or (Test-Path -LiteralPath (Join-Path $InstallDir 'config\watchdog.pause'))) {
        if ($Round % 60 -eq 0) { Write-TunnelWatchLog 'INFO' 'paused (config\watchdog.pause or WATCHDOG_ENABLED=false)' }
        return 0
    }
    $lock = Join-Path $stateDir 'install.lock'
    if ((Test-Path -LiteralPath $lock) -and ((Get-Date) - (Get-Item -LiteralPath $lock).LastWriteTime).TotalMinutes -lt 30) { return 0 }
    $svc = if (Get-Command Get-Service -ErrorAction SilentlyContinue) { Get-Service -Name 'couple-cloudflared' -ErrorAction SilentlyContinue } else { $null }
    if ($null -eq $svc) {
        if ($Round % 60 -eq 0) { Write-TunnelWatchLog 'INFO' 'service couple-cloudflared not installed; nothing to watch' }
        return 0
    }
    if ($svc.Status -ne 'Running') { return 0 } # starting a stopped service is watchdog.ps1's job
    $tunnel = Get-TunnelConfigInfo (Join-Path $InstallDir 'config\cloudflared\config.yml')
    if (-not $tunnel.Hostname) {
        if ($Round % 60 -eq 0) { Write-TunnelWatchLog 'WARN' 'no hostname in config\cloudflared\config.yml; public probe skipped' }
        return 0
    }
    $failLimit = Get-IntSetting $cfg 'TUNNELWATCH_FAILURES' 3 1
    $cooldown = Get-IntSetting $cfg 'TUNNELWATCH_COOLDOWN_MINUTES' 5 5
    $ports = Get-ExpectedTunnelPort $cfg

    $local = Invoke-HttpProbe "http://127.0.0.1:$($ports.Backend)/api/v1/health" 10 -Direct
    $localOk = ($local.Status -eq 200)
    $url = "https://$($tunnel.Hostname)/api/v1/health"
    $publicOk = $false
    $why = 'not probed (local health down)'
    if ($localOk) {
        $proxy = $cfg['HTTPS_PROXY']
        $p = if ($proxy) { Invoke-HttpProbe $url 15 $proxy } else { Invoke-HttpProbe $url 15 -Direct }
        $publicOk = ($p.Status -eq 200 -and $p.Body -match '"status"')
        $why = if ($p.Status) { "HTTP $($p.Status)" } else { [string]$p.Error }
    }
    $now = [DateTime]::UtcNow
    $last = Get-CloudflaredRestartMarker $InstallDir
    $d = Get-TunnelWatchDecision $publicOk $localOk $Failures $last $now $failLimit $cooldown
    switch ($d.Action) {
        'skip' {
            if ($Round % 10 -eq 0) { Write-TunnelWatchLog 'INFO' "local health down ($(if ($local.Status) { "HTTP $($local.Status)" } else { $local.Error })); public failures not counted" }
        }
        'none' {
            if (-not $publicOk) { Write-TunnelWatchLog 'WARN' "public $url failed ($($d.Failures)/$failLimit): $why" }
            elseif ($Failures -gt 0) { Write-TunnelWatchLog 'INFO' "public $url OK again after $Failures failure(s)" }
            elseif ($Round % 60 -eq 0) { Write-TunnelWatchLog 'INFO' "ok ($url)" }
        }
        'backoff' {
            Write-TunnelWatchLog 'WARN' "public $url failed ($($d.Failures)/$failLimit): $why; couple-cloudflared restarted less than $cooldown min ago ($last), waiting"
        }
        'restart' {
            Write-TunnelWatchLog 'WARN' "public $url failed $($d.Failures) times while local health is OK ($why); restarting couple-cloudflared"
            Set-CloudflaredRestartMarker $InstallDir $now
            try {
                Restart-Service -Name 'couple-cloudflared' -Force
                (Get-Service -Name 'couple-cloudflared').WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
                Write-TunnelWatchLog 'INFO' 'couple-cloudflared restarted'
            } catch {
                Write-TunnelWatchLog 'ERROR' "restarting couple-cloudflared failed: $($_.Exception.Message)"
            }
            return 0
        }
    }
    return [int]$d.Failures
}

# ---------------------------------------------------------------------------------------------------------------
try { New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logFile), $stateDir | Out-Null } catch { Write-Verbose "cannot prepare dirs: $($_.Exception.Message)" }

$failures = 0
$round = 0
if ($Once) {
    try { [void](Invoke-TunnelWatchRound 0 0) } catch { Write-TunnelWatchLog 'ERROR' "round failed: $($_.Exception.Message)" }
    exit 0
}
Write-TunnelWatchLog 'INFO' "started (pid $PID)"
while ($true) {
    $started = Get-Date
    try { $failures = Invoke-TunnelWatchRound $failures $round } catch { Write-TunnelWatchLog 'ERROR' "round failed: $($_.Exception.Message)" }
    $round++
    $interval = 60
    try { $interval = Get-IntSetting (Read-KeyValueFile (Join-Path $InstallDir 'config\couple.env')) 'TUNNELWATCH_INTERVAL_SECONDS' 60 15 } catch { $interval = 60 }
    $sleep = $interval - [int]((Get-Date) - $started).TotalSeconds
    if ($sleep -gt 0) { Start-Sleep -Seconds $sleep }
}
