<#
.SYNOPSIS
  Couple backup: nightly mysqldump of the couple database (gzip), plus couple.env and any extra directories,
  with retention. Run by the scheduled task \Couple\couple-backup (registered by install.ps1 or `backup.ps1 -Install`).

.DESCRIPTION
  Installed to <InstallDir>\scripts\backup.ps1 (with lib\common.ps1). The scheduled task runs it as SYSTEM every day
  at BACKUP_TIME (default 02:30; StartWhenAvailable = runs after boot when the machine was off). Each run:
    1. dumps the database (DB_URL/DB_USER/DB_PASSWORD from config\couple.env) with --single-transaction to
       <BACKUP_DIR>\couple-<timestamp>.sql, then gzips it (.sql.gz) and deletes the raw dump;
    2. copies config\couple.env to <BACKUP_DIR>\couple.env (it holds JWT_SECRET and the optional bootstrap key);
    3. zips every directory in BACKUP_EXTRA_DIRS (semicolon-separated) to extra-<name>.zip;
    4. deletes the oldest couple-*.sql.gz beyond BACKUP_KEEP (default 14).
  Log: <InstallDir>\logs\backup.log (rotated at 1 MB, 5 files). The backup dir is ACL-restricted to
  Administrators/SYSTEM (it contains the database dump and couple.env).

  Usage:  backup.ps1 [-InstallDir C:\couple] [-Install] [-Remove]
          without -Install/-Remove a backup runs right now (also what the task executes).

  Restore: see SETUP-windows.md 10.7. Move backups off the machine regularly; nothing on this host survives disk loss.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    # (Re-)register the daily scheduled task \Couple\couple-backup (elevated PowerShell required).
    [switch]$Install,
    # Remove the scheduled task (backup files are kept).
    [switch]$Remove
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$script:BackupTask = '\Couple\couple-backup'

# ---- pure helpers (unit-tested in tests/common.Tests.ps1) -----------------------------------------------------

function Get-DatabaseNameFromJdbcUrl([string]$Url) {
    if ($Url -match 'jdbc:mysql://[^/]+/([^?;/]+)') { return $Matches[1] }
    return 'couple'
}

function Get-HostPortFromJdbcUrl([string]$Url) {
    if ($Url -match 'jdbc:mysql://([^:/?]+)(?::(\d+))?') {
        return @{ Host = $Matches[1]; Port = if ($Matches[2]) { $Matches[2] } else { '3306' } }
    }
    return @{ Host = '127.0.0.1'; Port = '3306' }
}

# All but the newest [Keep] files (by LastWriteTime desc, Name as tiebreak); keep < 1 disables retention.
function Get-BackupFilesToDelete([object[]]$Files, [int]$Keep) {
    if ($Keep -lt 1 -or @($Files).Count -le $Keep) { return @() }
    $sorted = @($Files | Sort-Object -Property LastWriteTime, Name -Descending)
    return @($sorted | Select-Object -Skip $Keep)
}

function New-BackupTaskXml([string]$Command, [string]$Arguments, [string]$StartBoundary) {
    $c = [Security.SecurityElement]::Escape($Command)
    $a = [Security.SecurityElement]::Escape($Arguments)
    return @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Couple backup: nightly mysqldump + couple.env with retention (deploy/windows/backup.ps1)</Description>
  </RegistrationInfo>
  <Triggers>
    <CalendarTrigger>
      <StartBoundary>$StartBoundary</StartBoundary>
      <Enabled>true</Enabled>
      <ScheduleByDay>
        <DaysInterval>1</DaysInterval>
      </ScheduleByDay>
    </CalendarTrigger>
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
    <ExecutionTimeLimit>PT2H</ExecutionTimeLimit>
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

# ---- main actions ---------------------------------------------------------------------------------------------

function Compress-GzipFile([string]$Src, [string]$Dest) {
    Add-Type -AssemblyName System.IO.Compression
    $in = [IO.File]::OpenRead($Src)
    try {
        $fs = [IO.File]::Create($Dest)
        try {
            $gz = New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionLevel]::Optimal)
            try { $in.CopyTo($gz) } finally { $gz.Dispose() }
        } finally { $fs.Dispose() }
    } finally { $in.Dispose() }
}

function Invoke-Backup([string]$Dir) {
    $logFile = Join-Path $Dir 'logs\backup.log'
    function Write-Log([string]$Level, [string]$Message) {
        $line = '{0} {1,-5} {2}' -f [DateTime]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture), $Level, $Message
        try {
            [IO.File]::AppendAllText($logFile, $line + "`r`n", (New-Object Text.UTF8Encoding $false))
        } catch { Write-Verbose "log write failed: $($_.Exception.Message)" }
        Write-Host $line
    }
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logFile) | Out-Null
        Invoke-LogRotation $logFile 1MB 5 | Out-Null
    } catch { Write-Verbose "cannot prepare log dir: $($_.Exception.Message)" }

    try {
        $cfg = Read-KeyValueFile (Join-Path $Dir 'config\couple.env')
        if ($cfg['BACKUP_ENABLED'] -match '^(?i:false|0|no|off)$') {
            Write-Log 'INFO' 'disabled (BACKUP_ENABLED=false); nothing done'
            return 'disabled'
        }
        $keep = 14
        if ($cfg['BACKUP_KEEP'] -match '^\d{1,4}$') { $keep = [Math]::Max(1, [int]$cfg['BACKUP_KEEP']) }
        $backupDir = if ($cfg['BACKUP_DIR']) { $cfg['BACKUP_DIR'] } else { Join-Path $Dir 'backups' }
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        Protect-Directory $backupDir   # contains the whole database and couple.env

        # couple.env (JWT_SECRET, optional BOOTSTRAP_PRIVATE_KEY, ...)
        $envFile = Join-Path $Dir 'config\couple.env'
        if (Test-Path -LiteralPath $envFile) {
            Copy-Item -LiteralPath $envFile -Destination (Join-Path $backupDir 'couple.env') -Force
        }

        # database dump
        $db = Get-DatabaseNameFromJdbcUrl $cfg['DB_URL']
        $hp = Get-HostPortFromJdbcUrl $cfg['DB_URL']
        $mysqldump = if ($cfg['BACKUP_MYSQLDUMP']) { $cfg['BACKUP_MYSQLDUMP'] } else { 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe' }
        if (-not (Test-Path -LiteralPath $mysqldump)) {
            $onPath = Get-Command mysqldump.exe -ErrorAction SilentlyContinue
            if ($null -ne $onPath) { $mysqldump = $onPath.Source } else {
                Write-Log 'ERROR' "mysqldump not found at $mysqldump (set BACKUP_MYSQLDUMP); database NOT backed up"
                return 'no_mysqldump'
            }
        }
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $sql = Join-Path $backupDir "couple-$stamp.sql"
        $gz = "$sql.gz"
        $errFile = Join-Path $backupDir ".mysqldump-$stamp.err"
        $user = if ($cfg['DB_USER']) { $cfg['DB_USER'] } else { 'root' }
        $oldPwd = $env:MYSQL_PWD
        $env:MYSQL_PWD = $cfg['DB_PASSWORD']
        try {
            # --result-file writes the file itself (UTF-8); a PowerShell > redirect would produce UTF-16.
            $dumpArgs = '--single-transaction --routines --triggers --events --no-tablespaces ' +
                "--default-character-set=utf8mb4 --result-file=`"$sql`" -h $($hp.Host) -P $($hp.Port) -u $user $db"
            $p = Start-Process -FilePath $mysqldump -ArgumentList $dumpArgs -Wait -PassThru -NoNewWindow -RedirectStandardError $errFile
        } finally {
            if ($null -ne $oldPwd) { $env:MYSQL_PWD = $oldPwd } else { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
        }
        $errText = if (Test-Path -LiteralPath $errFile) { ([IO.File]::ReadAllText($errFile)).Trim() } else { '' }
        Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
        if ($p.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $sql) -or (Get-Item -LiteralPath $sql).Length -eq 0) {
            Remove-Item -LiteralPath $sql -Force -ErrorAction SilentlyContinue
            Write-Log 'ERROR' "mysqldump failed ($($p.ExitCode)): $($errText.Substring(0, [Math]::Min(300, $errText.Length)))"
            return 'dump_failed'
        }
        Compress-GzipFile $sql $gz
        Remove-Item -LiteralPath $sql -Force
        $size = '{0:N1} KB' -f ((Get-Item -LiteralPath $gz).Length / 1KB)

        # optional extra directories (e.g. local media storage)
        $extras = @()
        if ($cfg['BACKUP_EXTRA_DIRS']) { $extras = @($cfg['BACKUP_EXTRA_DIRS'].Split(';') | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
        $extrasDone = $false
        foreach ($d in $extras) {
            try {
                if (-not (Test-Path -LiteralPath $d)) { Write-Log 'WARN' "BACKUP_EXTRA_DIRS entry not found: $d"; continue }
                if (-not (Get-ChildItem -LiteralPath $d -Force | Select-Object -First 1)) { continue }   # empty dir
                $leaf = ($d.TrimEnd('\') -split '[\\/]' | Select-Object -Last 1)
                Compress-Archive -Path (Join-Path $d '*') -DestinationPath (Join-Path $backupDir "extra-$leaf.zip") -Force
                $extrasDone = $true
            } catch { Write-Log 'WARN' "zipping $d failed: $($_.Exception.Message)" }
        }

        # retention
        $files = @(Get-ChildItem -Path $backupDir -Filter 'couple-*.sql.gz' -File)
        $deleted = @(Get-BackupFilesToDelete $files $keep)
        foreach ($f in $deleted) { Remove-Item -LiteralPath $f.FullName -Force }
        Write-Log 'INFO' ("backup ok: {0} ({1}, db {2}@{3}:{4}); kept {5}/{6} dumps{7}" -f `
            (Split-Path -Leaf $gz), $size, $db, $hp.Host, $hp.Port, ($files.Count - $deleted.Count), $files.Count,
            $(if ($extrasDone) { '; extras zipped' } else { '' }))
        return 'ok'
    } catch {
        Write-Log 'ERROR' "backup failed: $($_.Exception.Message)"
        return 'failed'
    }
}

function Register-BackupTask([string]$Dir) {
    if (-not (Test-Admin)) { throw 'Run this script from an elevated PowerShell (Run as Administrator).' }
    $cfg = Read-KeyValueFile (Join-Path $Dir 'config\couple.env')
    $time = if ($cfg['BACKUP_TIME'] -match '^([01]?\d|2[0-3]):([0-5]\d)$') { $cfg['BACKUP_TIME'] } else { '02:30' }
    $self = Join-Path $PSScriptRoot 'backup.ps1'
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $taskArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$self`" -InstallDir `"$Dir`""
    $xml = New-BackupTaskXml $ps $taskArgs ((Get-Date -Format 'yyyy-MM-dd') + "T$time`:00")
    $xmlFile = Join-Path $Dir 'scripts\couple-backup.task.xml'
    [IO.File]::WriteAllText($xmlFile, $xml, [Text.Encoding]::Unicode)
    try {
        $r = Invoke-NativeCapture 'schtasks.exe' @('/Create', '/TN', $script:BackupTask, '/XML', $xmlFile, '/F')
        if ($r.ExitCode -ne 0) { throw "schtasks /Create $script:BackupTask failed: $($r.Output)" }
    } finally {
        Remove-Item -LiteralPath $xmlFile -Force -ErrorAction SilentlyContinue
    }
    Write-Host "scheduled task $script:BackupTask (daily $time as SYSTEM, log logs\backup.log)"
}

# ---- entry point (skipped when dot-sourced for tests) ----------------------------------------------------------

if ($MyInvocation.InvocationName -ne '.') {
    $InstallDir = [IO.Path]::GetFullPath($InstallDir)
    if ($Remove) {
        & schtasks.exe /Query /TN $script:BackupTask 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            & schtasks.exe /Delete /TN $script:BackupTask /F | Out-Null
            Write-Host "removed scheduled task $script:BackupTask (files in backups\ kept)"
        } else {
            Write-Host "task $script:BackupTask not registered"
        }
    } elseif ($Install) {
        Register-BackupTask $InstallDir
    } else {
        $null = Invoke-Backup $InstallDir
    }
}
