<#
.SYNOPSIS
  Removes the Couple Windows services. Keeps config, data, logs and releases unless -RemoveData is given.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -RemoveData -Force   # also deletes C:\couple (secrets, MQTT store, APK mirror)
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    [switch]$RemoveData,
    [switch]$Force
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell (Run as Administrator).'
}
$InstallDir = [IO.Path]::GetFullPath($InstallDir)

# The watchdog would restart the services we are about to remove.
$old = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & schtasks.exe /Query /TN '\Couple\couple-watchdog' 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        & schtasks.exe /Delete /TN '\Couple\couple-watchdog' /F 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Host 'removed scheduled task \Couple\couple-watchdog' } else { Write-Warning "schtasks /Delete returned $LASTEXITCODE" }
    }
    & schtasks.exe /Delete /TN '\Couple\' /F 2>&1 | Out-Null   # empty task folder (fails harmlessly when not empty/missing)
} finally {
    $ErrorActionPreference = $old
}

foreach ($name in @('couple-cloudflared', 'couple-updater', 'couple-backend')) {
    $svc = Get-Service -Name $name -ErrorAction SilentlyContinue
    if ($null -eq $svc) { continue }
    if ($svc.Status -ne 'Stopped') {
        Write-Host "stopping $name"
        Stop-Service -Name $name -Force
        $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
    }
    $exe = Join-Path $InstallDir "services\$name.exe"
    if (Test-Path -LiteralPath $exe) {
        & $exe uninstall
        if ($LASTEXITCODE -ne 0) { Write-Warning "WinSW uninstall of $name returned $LASTEXITCODE" }
    } else {
        & sc.exe delete $name | Out-Null
    }
    Write-Host "removed service $name"
}

if ($RemoveData) {
    if (-not $Force) {
        $answer = Read-Host "Delete $InstallDir including config\couple.env (all secrets), data\ and logs\? Type YES"
        if ($answer -ne 'YES') { Write-Host 'kept data'; return }
    }
    Remove-Item -LiteralPath $InstallDir -Recurse -Force
    Write-Host "deleted $InstallDir"
} else {
    Write-Host "kept $InstallDir (config, data, logs, releases). MySQL is not touched."
}
