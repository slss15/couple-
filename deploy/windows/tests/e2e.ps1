<#
.SYNOPSIS
  End-to-end test of the Windows package on a throwaway Windows host (CI: windows-latest, see
  .github/workflows/_windows-package.yml job `e2e`). DESTRUCTIVE: installs MySQL 8.4 to C:\mysql, the Couple services
  to -InstallDir, and removes any pre-installed MySQL service first. Never run it on a real server.

    powershell -ExecutionPolicy Bypass -File deploy\windows\tests\e2e.ps1 -Pkg <extracted package dir> -IUnderstandThisIsDestructive

  Flow: doctor (WARN allowed) -> setup.ps1 -Hostname ci.invalid -SkipTunnel (Windows PowerShell 5.1, non-interactive,
  real MySQL install) -> health -> backend bound to 127.0.0.1 only -> POST /api/v1/setup with SETUP_TOKEN ->
  POST /api/v1/admin/bootstrap -> doctor again (ports owned by our services must PASS) -> setup.ps1 re-run with
  PowerShell 7 (couple.env, services, rendered configs unchanged) -> watchdog (stopped backend is restarted; the
  scheduled task runs as SYSTEM) -> install.ps1 refuses an older package -> stop/start every service (stand-in for a
  reboot) -> health -> uninstall.ps1 (services and task gone). Throws on the first failed assertion.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Pkg,
    [string]$InstallDir = 'C:\couple',
    [switch]$IUnderstandThisIsDestructive
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $IUnderstandThisIsDestructive) { throw 'e2e.ps1 installs and removes services and MySQL; pass -IUnderstandThisIsDestructive (CI only).' }
$Pkg = [IO.Path]::GetFullPath($Pkg)
. (Join-Path $Pkg 'lib\common.ps1')

$ps51 = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -First 1)
$envFile = Join-Path $InstallDir 'config\couple.env'
$ourServices = @('couple-backend', 'couple-updater', 'MySQL84')
$script:phase = 'start'

function Step([string]$Name) { $script:phase = $Name; Write-Host ''; Write-Host "===== E2E: $Name =====" -ForegroundColor Cyan }
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw "ASSERT [$script:phase]: $Message" } }

function Invoke-Script([string]$Shell, [string]$Script, [string[]]$Arguments) {
    Write-Host "> $Shell -File $Script $($Arguments -join ' ')"
    & $Shell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $Script @Arguments
    return $LASTEXITCODE
}

function Wait-Health([int]$Seconds = 180) {
    $port = (Read-KeyValueFile $envFile)['PORT']; if (-not $port) { $port = '8080' }
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-HttpProbe "http://127.0.0.1:$port/api/v1/health" 5 -Direct
        if ($r.Status -eq 200) { Write-Host "    health: $($r.Body)"; return $r.Body }
        Start-Sleep -Seconds 3
    }
    throw "ASSERT [$script:phase]: backend not healthy within $Seconds s"
}

function Get-Snapshot {
    $files = [ordered]@{}
    foreach ($rel in @('config\couple.env', 'config\mysql-root.txt', 'services\couple-backend.xml', 'services\couple-updater.xml', 'releases\CURRENT')) {
        $p = Join-Path $InstallDir $rel
        $files[$rel] = if (Test-Path -LiteralPath $p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash } else { 'missing' }
    }
    $files['C:\mysql\my.ini'] = (Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\mysql\my.ini').Hash
    $svcs = @(Get-CimInstance -ClassName Win32_Service | Where-Object { $ourServices -contains $_.Name } | Sort-Object Name |
            ForEach-Object { '{0}|{1}|{2}' -f $_.Name, $_.StartMode, $_.PathName })
    return [pscustomobject]@{ Files = $files; Services = $svcs }
}

# ---------------------------------------------------------------------------------------------------------------
Step 'remove pre-installed MySQL services (runner image)'
foreach ($s in @(Get-CimInstance -ClassName Win32_Service | Where-Object { $_.PathName -match 'mysqld(-nt)?\.exe' })) {
    Write-Host "    removing service $($s.Name) ($($s.PathName))"
    Stop-Service -Name $s.Name -Force -ErrorAction SilentlyContinue
    & sc.exe delete $s.Name | Out-Null
}

Step 'pre-seed couple.env (no automatic deploys on CI)'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $envFile) | Out-Null
$example = [IO.File]::ReadAllText((Join-Path $Pkg 'config\couple.env.example'))
Write-Utf8NoBom $envFile $example
Set-EnvValue $envFile 'UPDATE_ENABLED' 'false' | Out-Null

Step 'doctor.ps1 (WARN allowed)'
$code = Invoke-Script $ps51 (Join-Path $Pkg 'doctor.ps1') @('-InstallDir', $InstallDir, '-SkipTunnel')
Assert-True ($code -eq 0) "doctor exit code $code (a check FAILed)"

Step 'setup.ps1 -Hostname ci.invalid -SkipTunnel (Windows PowerShell 5.1)'
$code = Invoke-Script $ps51 (Join-Path $Pkg 'setup.ps1') @('-Hostname', 'ci.invalid', '-InstallDir', $InstallDir, '-SkipTunnel')
Assert-True ($code -eq 0) "setup.ps1 exit code $code"
foreach ($s in $ourServices) { Assert-True ((Get-ServiceState $s) -eq 'Running') "$s is $(Get-ServiceState $s)" }
$cfg = Read-KeyValueFile $envFile
foreach ($k in 'DB_PASSWORD', 'JWT_SECRET', 'SETUP_TOKEN', 'MQTT_HOOK_SECRET', 'DEPLOY_HOOK_SECRET', 'JAVA_OPTS') { Assert-True ([bool]$cfg[$k]) "$k empty in couple.env" }
Assert-True ($cfg['UPDATE_ENABLED'] -eq 'false') 'pre-seeded UPDATE_ENABLED was overwritten'

Step 'health and loopback bind'
Wait-Health | Out-Null
$port = if ($cfg['PORT']) { [int]$cfg['PORT'] } else { 8080 }
$listen = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
Assert-True ($listen.Count -gt 0) "nothing listens on $port"
foreach ($l in $listen) { Assert-True ($l.LocalAddress -eq '127.0.0.1') "backend listens on $($l.LocalAddress):$port (want 127.0.0.1 only)" }
foreach ($p in 8083, 9000, 3306) {
    foreach ($l in @(Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)) {
        Assert-True ($l.LocalAddress -eq '127.0.0.1') "port $p listens on $($l.LocalAddress)"
    }
}

Step 'first-run setup (POST /api/v1/setup) and bootstrap QR (POST /api/v1/admin/bootstrap)'
$base = "http://127.0.0.1:$port/api/v1"
$status = Invoke-RestMethod -Uri "$base/setup/status" -UseBasicParsing
Assert-True ([bool]$status.needed) 'setup/status says not needed before setup'
$body = @{ token = $cfg['SETUP_TOKEN']; username = 'ciadmin'; password = 'ci-Passw0rd-e2e'; deviceId = 'ci-e2e'; deviceName = 'CI' } | ConvertTo-Json -Compress
$pair = Invoke-RestMethod -Method Post -Uri "$base/setup" -ContentType 'application/json' -Body $body -UseBasicParsing
Assert-True ([bool]$pair.accessToken) 'setup returned no accessToken'
$headers = @{ Authorization = "Bearer $($pair.accessToken)" }
$qr = Invoke-RestMethod -Method Post -Uri "$base/admin/bootstrap" -Headers $headers -UseBasicParsing
Assert-True ([string]$qr.uri -like 'couple://bootstrap?d=*&s=*') "unexpected bootstrap uri: $($qr.uri)"
Write-Host "    bootstrap QR: $(([string]$qr.uri).Substring(0, 40))..."
$again = $null
try { Invoke-RestMethod -Method Post -Uri "$base/setup" -ContentType 'application/json' -Body $body -UseBasicParsing | Out-Null } catch { $again = $_.Exception.Response }
Assert-True ($null -ne $again -and [int]$again.StatusCode -eq 410) 'second setup was not rejected with 410'

Step 'doctor.ps1 after install (ports owned by our services must PASS)'
$code = Invoke-Script $ps51 (Join-Path $Pkg 'doctor.ps1') @('-InstallDir', $InstallDir, '-SkipTunnel')
Assert-True ($code -eq 0) "doctor after install exit code $code"

Step 'setup.ps1 re-run is a no-op (PowerShell 7 when available)'
$before = Get-Snapshot
$shell2 = if ($pwsh) { $pwsh.Source } else { $ps51 }
$code = Invoke-Script $shell2 (Join-Path $Pkg 'setup.ps1') @('-Hostname', 'ci.invalid', '-InstallDir', $InstallDir, '-SkipTunnel')
Assert-True ($code -eq 0) "setup.ps1 re-run exit code $code"
$after = Get-Snapshot
foreach ($k in $before.Files.Keys) { Assert-True ($before.Files[$k] -eq $after.Files[$k]) "$k changed on re-run" }
Assert-True (($before.Services -join "`n") -eq ($after.Services -join "`n")) "services changed on re-run:`n$($before.Services -join "`n")`n---`n$($after.Services -join "`n")"
Wait-Health | Out-Null

Step 'watchdog restarts a stopped backend; scheduled task runs as SYSTEM'
$task = Invoke-NativeCapture 'schtasks.exe' @('/Query', '/TN', '\Couple\couple-watchdog', '/V', '/FO', 'LIST')
Assert-True ($task.ExitCode -eq 0) 'scheduled task \Couple\couple-watchdog missing'
Write-Host $task.Stdout
Stop-Service -Name 'couple-backend' -Force
$code = Invoke-Script $ps51 (Join-Path $InstallDir 'watchdog\watchdog.ps1') @('-InstallDir', $InstallDir, '-Once')
Assert-True ((Get-ServiceState 'couple-backend') -eq 'Running') 'watchdog did not start the stopped backend'
Wait-Health | Out-Null
$stateFile = Join-Path $InstallDir 'data\watchdog\state.txt'
$stamp = if (Test-Path -LiteralPath $stateFile) { (Get-Item -LiteralPath $stateFile).LastWriteTimeUtc } else { [DateTime]::MinValue }
$r = Invoke-NativeCapture 'schtasks.exe' @('/Run', '/TN', '\Couple\couple-watchdog')
Assert-True ($r.ExitCode -eq 0) "schtasks /Run failed: $($r.Output)"
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline -and -not ((Test-Path -LiteralPath $stateFile) -and (Get-Item -LiteralPath $stateFile).LastWriteTimeUtc -gt $stamp)) { Start-Sleep -Seconds 3 }
Assert-True ((Test-Path -LiteralPath $stateFile) -and (Get-Item -LiteralPath $stateFile).LastWriteTimeUtc -gt $stamp) 'scheduled watchdog run did not update data\watchdog\state.txt'
$wlog = [IO.File]::ReadAllText((Join-Path $InstallDir 'logs\watchdog.log'))
Write-Host $wlog
Assert-True ($wlog -match 'backend=ok') 'watchdog log has no healthy backend line'

Step 'install.ps1 refuses an older package'
$old = Join-Path $env:TEMP 'couple-e2e-old-pkg'
if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
Copy-Item -LiteralPath $Pkg -Destination $old -Recurse
Set-Content -LiteralPath (Join-Path $old 'VERSION') -Value '0.0.1-ci' -Encoding ASCII
Write-Utf8NoBom (Join-Path $old 'BUILD_INFO') "VERSION=0.0.1-ci`nCOMMIT_EPOCH=1`n"
$currentBefore = [IO.File]::ReadAllText((Join-Path $InstallDir 'releases\CURRENT')).Trim()
$code = Invoke-Script $ps51 (Join-Path $old 'install.ps1') @('-InstallDir', $InstallDir, '-NoStart')
Assert-True ($code -ne 0) 'install.ps1 accepted an older package without -AllowDowngrade'
Assert-True ([IO.File]::ReadAllText((Join-Path $InstallDir 'releases\CURRENT')).Trim() -eq $currentBefore) 'CURRENT changed by a refused downgrade'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $InstallDir 'releases\0.0.1-ci'))) 'refused downgrade left a release dir'
Remove-Item -LiteralPath $old -Recurse -Force

Step 'stop/start every service (reboot stand-in) and recheck health'
foreach ($s in @('couple-cloudflared', 'couple-updater', 'couple-backend', 'MySQL84')) {
    if ((Get-ServiceState $s) -eq 'Running') { Stop-Service -Name $s -Force; (Get-Service $s).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90)) }
}
foreach ($s in $ourServices) { Assert-True ((Get-ServiceState $s) -eq 'Stopped') "$s did not stop" }
foreach ($s in @('MySQL84', 'couple-backend', 'couple-updater')) { Start-Service -Name $s }
Wait-Health | Out-Null
$u = Invoke-HttpProbe 'http://127.0.0.1:9000/_deploy/healthz' 5 -Direct
for ($i = 0; $i -lt 20 -and $u.Status -ne 200; $i++) { Start-Sleep -Seconds 3; $u = Invoke-HttpProbe 'http://127.0.0.1:9000/_deploy/healthz' 5 -Direct }
Assert-True ($u.Status -eq 200) 'updater not answering after restart'
$login = @{ username = 'ciadmin'; password = 'ci-Passw0rd-e2e'; deviceId = 'ci-e2e'; deviceName = 'CI' } | ConvertTo-Json -Compress
$tok = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' -Body $login -UseBasicParsing
Assert-True ([bool]$tok.accessToken) 'admin login after restart failed (data not persisted?)'

Step 'uninstall.ps1'
$code = Invoke-Script $ps51 (Join-Path $Pkg 'uninstall.ps1') @('-InstallDir', $InstallDir)
Assert-True ($code -eq 0) "uninstall.ps1 exit code $code"
foreach ($s in @('couple-backend', 'couple-updater', 'couple-cloudflared')) { Assert-True ((Get-ServiceState $s) -eq 'Missing') "$s still registered" }
Assert-True ((Invoke-NativeCapture 'schtasks.exe' @('/Query', '/TN', '\Couple\couple-watchdog')).ExitCode -ne 0) 'watchdog task still registered'

Write-Host ''
Write-Host 'E2E PASSED' -ForegroundColor Green
