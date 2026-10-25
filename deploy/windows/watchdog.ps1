<#
.SYNOPSIS
  Couple watchdog: keeps the tunnel, the backend and MySQL alive. Run every 2 minutes as SYSTEM by the scheduled
  task \Couple\couple-watchdog (registered by install.ps1, removed by uninstall.ps1).

.DESCRIPTION
  Installed to <InstallDir>\watchdog\watchdog.ps1 (admins/SYSTEM-only directory). Each run:
    1. couple-backend: started when stopped; GET http://127.0.0.1:<PORT>/api/v1/health, restarted after
       2 consecutive failures (skipped while couple-updater is deploying or install.ps1 is running).
    2. MySQL (MySQL84 or WATCHDOG_MYSQL_SERVICE): started when stopped.
    3. couple-updater: started when stopped.
    4. couple-cloudflared (only when the service exists): started when stopped. Two independent probes:
         - metrics endpoint (config.yml `metrics:`, default 127.0.0.1:20241): cloudflared_tunnel_ha_connections >= 1
         - https://<hostname>/api/v1/health through Cloudflare (hostname from config.yml), counted only while the
           local backend is healthy (a dead backend is not the tunnel's fault)
       Either probe failing 2 runs in a row restarts couple-cloudflared. With protocol http2 its edge connections can
       die silently while /ready still answers OK; this is the backstop.
    5. warns when free space on C: or the install drive is under 2 GB.
  Restarts have a cooldown (WATCHDOG_COOLDOWN_MINUTES, default 5) so a persistent outage does not cause a restart
  loop. Log: <InstallDir>\logs\watchdog.log (rotated at 1 MB, 5 files). State: <InstallDir>\data\watchdog\state.txt.

  Pause: create <InstallDir>\config\watchdog.pause (or set WATCHDOG_ENABLED=false in couple.env), e.g. while a
  service is stopped on purpose. A service whose start type is Disabled is never started.
  Exit code is always 0 (failures are logged); -Once prints the log lines to the console as well.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    # Print log lines to the console too (manual runs).
    [switch]$Once
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$logFile = Join-Path $InstallDir 'logs\watchdog.log'
$stateDir = Join-Path $InstallDir 'data\watchdog'
$stateFile = Join-Path $stateDir 'state.txt'
$script:echo = [bool]$Once

function Write-WatchdogLog([string]$Level, [string]$Message) {
    $Message = ($Message -replace '\s*[\r\n]+\s*', ' ').Trim()
    $line = '{0} {1,-5} {2}' -f [DateTime]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture), $Level, $Message
    try { [IO.File]::AppendAllText($logFile, $line + "`r`n", (New-Object Text.UTF8Encoding $false)) } catch { Write-Verbose "log write failed: $($_.Exception.Message)" }
    if ($script:echo) { Write-Host $line }
}

function Save-State([hashtable]$State) {
    $lines = @('# Couple watchdog state (written every run)')
    foreach ($k in ($State.Keys | Sort-Object)) { $lines += "$k=$($State[$k])" }
    $tmp = "$stateFile.tmp"
    Write-Utf8NoBom $tmp (($lines -join "`r`n") + "`r`n")
    Move-Item -LiteralPath $tmp -Destination $stateFile -Force
}

# Starts a stopped service (unless its start type is Disabled). Returns $true when it is running afterwards.
function Confirm-ServiceRunning([string]$Name) {
    $svc = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $svc) { return $false }
    if ($svc.Status -eq 'Running') { return $true }
    $startType = ''
    try { $startType = [string](Get-CimInstance -ClassName Win32_Service -Filter "Name='$Name'").StartMode } catch { $startType = '' }
    if ($startType -eq 'Disabled') { Write-WatchdogLog 'INFO' "$Name is $($svc.Status) and Disabled; not starting it"; return $false }
    if ($svc.Status -eq 'StartPending') { Write-WatchdogLog 'INFO' "$Name is starting"; return $true }
    Write-WatchdogLog 'WARN' "$Name was $($svc.Status); starting it"
    try {
        Start-Service -Name $Name
        $svc.WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        Write-WatchdogLog 'INFO' "$Name started"
        return $true
    } catch {
        Write-WatchdogLog 'ERROR' "starting $Name failed: $($_.Exception.Message)"
        return $false
    }
}

function Restart-Guarded([hashtable]$State, [string]$Name, [string]$Reason, [int]$Cooldown) {
    $key = "lastRestart.$Name"
    $last = if ($State.ContainsKey($key)) { [string]$State[$key] } else { '' }
    if (-not (Test-RestartAllowed $last ([DateTime]::UtcNow) $Cooldown)) {
        Write-WatchdogLog 'WARN' "$Name unhealthy ($Reason) but restarted less than $Cooldown min ago ($last); waiting"
        return $false
    }
    Write-WatchdogLog 'WARN' "restarting $Name ($Reason)"
    $State[$key] = [DateTime]::UtcNow.ToString('o')
    $n = 0
    if ($State.ContainsKey("restarts.$Name")) { $n = [int]$State["restarts.$Name"] }
    $State["restarts.$Name"] = $n + 1
    try {
        Restart-Service -Name $Name -Force
        (Get-Service -Name $Name).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        Write-WatchdogLog 'INFO' "$Name restarted"
        return $true
    } catch {
        Write-WatchdogLog 'ERROR' "restarting $Name failed: $($_.Exception.Message)"
        return $false
    }
}

# True while couple-updater is switching releases (it stops/starts couple-backend itself).
function Test-UpdaterDeploying([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    try {
        $s = ConvertFrom-Json ([IO.File]::ReadAllText($Path))
        $names = @($s.PSObject.Properties | ForEach-Object { $_.Name })
        if (-not ($names -contains 'deploys') -or $null -eq $s.deploys) { return $false }
        $d = $s.deploys.PSObject.Properties | Where-Object { $_.Name -eq 'server' } | Select-Object -First 1
        if ($null -eq $d -or $d.Value.state -ne 'deploying') { return $false }
        # Ignore a stale "deploying" (updater crashed mid-deploy): older than 20 minutes.
        $updated = [long]$d.Value.updatedAt
        $age = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $updated) / 60000
        return ($age -lt 20)
    } catch {
        return $false
    }
}

# ---------------------------------------------------------------------------------------------------------------
try {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logFile), $stateDir | Out-Null
    Invoke-LogRotation $logFile 1MB 5 | Out-Null
} catch {
    Write-Verbose "cannot prepare log/state dirs: $($_.Exception.Message)"
}

try {
    $cfg = Read-KeyValueFile (Join-Path $InstallDir 'config\couple.env')
    if ($cfg['WATCHDOG_ENABLED'] -match '^(?i:false|0|no|off)$' -or (Test-Path -LiteralPath (Join-Path $InstallDir 'config\watchdog.pause'))) {
        Write-WatchdogLog 'INFO' 'paused (config\watchdog.pause or WATCHDOG_ENABLED=false); nothing checked'
        exit 0
    }
    $installLock = Join-Path $stateDir 'install.lock'
    if ((Test-Path -LiteralPath $installLock) -and ((Get-Date) - (Get-Item -LiteralPath $installLock).LastWriteTime).TotalMinutes -lt 30) {
        Write-WatchdogLog 'INFO' 'install.ps1 is running; nothing checked'
        exit 0
    }

    $state = Read-KeyValueFile $stateFile
    $cooldown = 5
    if ($cfg['WATCHDOG_COOLDOWN_MINUTES'] -match '^\d{1,4}$') { $cooldown = [Math]::Max(1, [int]$cfg['WATCHDOG_COOLDOWN_MINUTES']) }
    $failLimit = 2
    $ports = Get-ExpectedTunnelPort $cfg
    $proxy = $cfg['HTTPS_PROXY']
    $summary = New-Object System.Collections.Generic.List[string]

    # 1) MySQL first: the backend cannot become healthy without it.
    $mysqlName = if ($cfg['WATCHDOG_MYSQL_SERVICE']) { $cfg['WATCHDOG_MYSQL_SERVICE'] } else { 'MySQL84' }
    if ($null -ne (Get-Service -Name $mysqlName -ErrorAction SilentlyContinue)) {
        $ok = Confirm-ServiceRunning $mysqlName
        $summary.Add("mysql=$(if ($ok) { 'ok' } else { 'down' })")
    }

    # 2) backend
    $backendOk = $false
    if ($null -ne (Get-Service -Name 'couple-backend' -ErrorAction SilentlyContinue)) {
        if (Test-UpdaterDeploying (Join-Path $InstallDir 'data\updater\updater.json')) {
            Write-WatchdogLog 'INFO' 'couple-updater is deploying; backend checks skipped'
            $summary.Add('backend=deploying')
        } else {
            $running = Confirm-ServiceRunning 'couple-backend'
            $r = Invoke-HttpProbe "http://127.0.0.1:$($ports.Backend)/api/v1/health" 10 -Direct
            $backendOk = ($r.Status -eq 200)
            $n = Step-FailCounter $state 'fail.backend' $backendOk
            if (-not $backendOk) {
                $why = if ($r.Status) { "HTTP $($r.Status)" } else { $r.Error }
                Write-WatchdogLog 'WARN' "backend health failed ($n/$failLimit): $why"
                if ($running -and $n -ge $failLimit) {
                    if (Restart-Guarded $state 'couple-backend' "health failed $n times: $why" ([Math]::Max($cooldown, 5))) { $state['fail.backend'] = 0 }
                }
            }
            $summary.Add("backend=$(if ($backendOk) { 'ok' } else { "fail$n" })")
        }
    }

    # 3) updater
    if ($null -ne (Get-Service -Name 'couple-updater' -ErrorAction SilentlyContinue)) {
        $ok = Confirm-ServiceRunning 'couple-updater'
        $summary.Add("updater=$(if ($ok) { 'ok' } else { 'down' })")
    }

    # 4) tunnel
    if ($null -ne (Get-Service -Name 'couple-cloudflared' -ErrorAction SilentlyContinue)) {
        $tunnel = Get-TunnelConfigInfo (Join-Path $InstallDir 'config\cloudflared\config.yml')
        $running = Confirm-ServiceRunning 'couple-cloudflared'
        $metricsAddr = if ($tunnel.Metrics) { $tunnel.Metrics } else { '127.0.0.1:20241' }
        $m = Invoke-HttpProbe "http://$metricsAddr/metrics" 10 -Direct
        $conns = if ($m.Status -eq 200) { ConvertFrom-PrometheusMetric $m.Body 'cloudflared_tunnel_ha_connections' } else { $null }
        $metricsOk = ($null -ne $conns -and $conns -ge 1)
        $nm = Step-FailCounter $state 'fail.tunnelMetrics' $metricsOk
        if (-not $metricsOk) {
            $why = if ($m.Status -ne 200) { if ($m.Status) { "metrics HTTP $($m.Status)" } else { "metrics unreachable: $($m.Error)" } } elseif ($null -eq $conns) { 'cloudflared_tunnel_ha_connections missing' } else { "ha_connections=$conns" }
            Write-WatchdogLog 'WARN' "tunnel metrics check failed ($nm/$failLimit): $why"
        }
        $np = 0
        $publicState = 'skip'
        if ($tunnel.Hostname) {
            if ($backendOk) {
                $url = "https://$($tunnel.Hostname)/api/v1/health"
                $p = if ($proxy) { Invoke-HttpProbe $url 15 $proxy } else { Invoke-HttpProbe $url 15 -Direct }
                $publicOk = ($p.Status -eq 200 -and $p.Body -match '"status"')
                $np = Step-FailCounter $state 'fail.tunnelPublic' $publicOk
                $publicState = if ($publicOk) { 'ok' } else { "fail$np" }
                if (-not $publicOk) {
                    $why = if ($p.Status) { "HTTP $($p.Status)" } else { $p.Error }
                    Write-WatchdogLog 'WARN' "public health $url failed ($np/$failLimit): $why"
                }
            } else {
                $publicState = 'skip(backend down)'
            }
        }
        if ($running -and ($nm -ge $failLimit -or $np -ge $failLimit)) {
            $reason = "metrics fails=$nm, public fails=$np"
            if (Restart-Guarded $state 'couple-cloudflared' $reason $cooldown) {
                $state['fail.tunnelMetrics'] = 0
                $state['fail.tunnelPublic'] = 0
            }
        }
        $summary.Add("tunnel=conns:$(if ($null -ne $conns) { $conns } else { '-' }),public:$publicState")
    }

    # 5) disk
    $drives = @('C:')
    $installDrive = [IO.Path]::GetPathRoot($InstallDir).TrimEnd('\')
    if ($installDrive -and $drives -notcontains $installDrive) { $drives += $installDrive }
    foreach ($d in $drives) {
        try {
            $disk = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='$d'"
            if ($null -eq $disk) { continue }
            $freeGb = [double]$disk.FreeSpace / 1GB
            if ($freeGb -lt 2) { Write-WatchdogLog 'WARN' ("low disk space on {0}: {1:N2} GB free (< 2 GB). Clean up logs\, data\downloads\ or old releases\" -f $d, $freeGb) }
            $summary.Add(("disk{0}={1:N1}GB" -f $d.TrimEnd(':'), $freeGb))
        } catch {
            Write-WatchdogLog 'WARN' "disk check $d failed: $($_.Exception.Message)"
        }
    }

    $state['lastRun'] = [DateTime]::UtcNow.ToString('o')
    Save-State $state
    Write-WatchdogLog 'INFO' ($summary -join ' ')
} catch {
    Write-WatchdogLog 'ERROR' "watchdog run failed: $($_.Exception.Message)"
}
exit 0
