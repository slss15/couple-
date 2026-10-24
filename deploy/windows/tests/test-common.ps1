<#
.SYNOPSIS
  Unit tests for the pure helpers in lib/common.ps1. Runs on Windows PowerShell 5.1 and PowerShell 7 (also on Linux):
    pwsh -NoProfile -File deploy/windows/tests/test-common.ps1
  Exits 1 when any assertion fails. Not shipped in the zip.
#>
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'lib/common.ps1')

$script:failures = 0
function Assert-Equal($Expected, $Actual, [string]$Name) {
    if ($Expected -ceq $Actual) { Write-Host "ok   $Name" }
    else { $script:failures++; Write-Host "FAIL $Name`n     expected: [$Expected]`n     actual:   [$Actual]" -ForegroundColor Red }
}

# --- Get-MemoryProfile: <=2.5 GB small, <=5 GB medium, else large
$cases = @(
    @(1GB, '256M', '384m'), @(2GB, '256M', '384m'), @([long](2.5 * 1GB), '256M', '384m'),
    @(([long](2.5 * 1GB) + 1), '512M', '640m'), @(4GB, '512M', '640m'), @(5GB, '512M', '640m'),
    @((5GB + 1), '1G', '1024m'), @(8GB, '1G', '1024m'), @(64GB, '1G', '1024m')
)
foreach ($c in $cases) {
    $p = Get-MemoryProfile ([long]$c[0])
    Assert-Equal $c[1] $p.InnodbBufferPool "buffer pool for $($c[0]) bytes"
    Assert-Equal $c[2] $p.JvmXmx "Xmx for $($c[0]) bytes"
}
# A "4 GB" VM usually reports a little less than 4 GiB.
Assert-Equal '640m' (Get-MemoryProfile 4176310272).JvmXmx 'Xmx for a 3.9 GiB VM'

# --- JAVA_OPTS auto-management
Assert-Equal '-Xms64m -Xmx640m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError' (Get-DefaultJavaOption '640m') 'default JAVA_OPTS'
Assert-Equal $true (Test-JavaOptsAutoManaged '') 'empty JAVA_OPTS is auto'
Assert-Equal $true (Test-JavaOptsAutoManaged $null) 'null JAVA_OPTS is auto'
Assert-Equal $true (Test-JavaOptsAutoManaged '-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError') 'shipped default is auto'
Assert-Equal $true (Test-JavaOptsAutoManaged (Get-DefaultJavaOption '1024m')) 'setup-written value is auto'
Assert-Equal $false (Test-JavaOptsAutoManaged '-Xms64m -Xmx700m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError') 'custom Xmx is kept'
Assert-Equal $false (Test-JavaOptsAutoManaged '-Xmx512m') 'custom flags are kept'

# --- ConvertTo-Hostname
Assert-Equal 'couple.example.com' (ConvertTo-Hostname 'couple.example.com') 'plain hostname'
Assert-Equal 'couple.example.com' (ConvertTo-Hostname ' https://Couple.Example.COM/ ') 'scheme, case, slash stripped'
Assert-Equal 'a-b.c.example.co' (ConvertTo-Hostname 'a-b.c.example.co') 'hyphen + subdomains'
Assert-Equal $null (ConvertTo-Hostname 'localhost') 'single label rejected'
Assert-Equal $null (ConvertTo-Hostname 'couple.example.com/path') 'path rejected'
Assert-Equal $null (ConvertTo-Hostname '-bad.example.com') 'leading hyphen rejected'
Assert-Equal $null (ConvertTo-Hostname 'exa mple.com') 'space rejected'
Assert-Equal $null (ConvertTo-Hostname '') 'empty rejected'

# --- Set-EnvIfEmpty / Set-EnvValue
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("couple-test-{0}.env" -f [guid]::NewGuid().ToString('N'))
try {
    Write-Utf8NoBom $tmp "# comment`r`nDB_PASSWORD=`r`nJWT_SECRET=keep-me`r`n  SPACED = `r`nJAVA_OPTS=-Xmx1g -Dx=a=b`r`n"
    Assert-Equal $true (Set-EnvIfEmpty $tmp 'DB_PASSWORD' 'p1') 'fills empty value'
    Assert-Equal $false (Set-EnvIfEmpty $tmp 'DB_PASSWORD' 'p2') 'second call keeps value'
    Assert-Equal $false (Set-EnvIfEmpty $tmp 'JWT_SECRET' 'x') 'never overwrites non-empty'
    Assert-Equal $true (Set-EnvIfEmpty $tmp 'SPACED' 'v') 'fills key with surrounding spaces'
    Assert-Equal $true (Set-EnvIfEmpty $tmp 'SETUP_TOKEN' 'abc') 'appends missing key'
    Assert-Equal $false (Set-EnvIfEmpty $tmp 'SETUP_TOKEN' 'def') 'appended key is kept'
    Assert-Equal $false (Set-EnvValue $tmp 'JAVA_OPTS' '-Xmx1g -Dx=a=b') 'same value is no change'
    Assert-Equal $true (Set-EnvValue $tmp 'JAVA_OPTS' '-Xmx2g') 'overwrites with Set-EnvValue'
    $m = Read-KeyValueFile $tmp
    Assert-Equal 'p1' $m['DB_PASSWORD'] 'DB_PASSWORD persisted'
    Assert-Equal 'keep-me' $m['JWT_SECRET'] 'JWT_SECRET untouched'
    Assert-Equal 'v' $m['SPACED'] 'SPACED filled'
    Assert-Equal 'abc' $m['SETUP_TOKEN'] 'SETUP_TOKEN appended'
    Assert-Equal '-Xmx2g' $m['JAVA_OPTS'] 'JAVA_OPTS overwritten'
    $raw = [IO.File]::ReadAllText($tmp)
    Assert-Equal $true ($raw.StartsWith('# comment')) 'comment kept, no BOM'
    Assert-Equal 1 ([regex]::Matches($raw, '(?m)^SETUP_TOKEN=').Count) 'no duplicate key'
    Remove-Item -LiteralPath $tmp
    Assert-Equal $true (Set-EnvIfEmpty $tmp 'NEW' 'x') 'creates missing file'
    Assert-Equal 'x' (Read-KeyValueFile $tmp)['NEW'] 'created file readable'
} finally {
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
}

# --- Get-RandomHex
Assert-Equal $true ((Get-RandomHex 16) -cmatch '^[0-9a-f]{32}$') 'Get-RandomHex 16 -> 32 hex chars'

# --- New-MysqlIni
$ini = New-MysqlIni 'C:\mysql' 'C:\mysql-data' '512M'
Assert-Equal $true ($ini -match '(?m)^basedir=C:/mysql\r?$') 'my.ini basedir forward slashes'
Assert-Equal $true ($ini -match '(?m)^innodb_buffer_pool_size=512M\r?$') 'my.ini buffer pool'
Assert-Equal $true ($ini -match '(?m)^performance_schema=OFF\r?$') 'my.ini performance_schema off'
Assert-Equal $true ($ini -match '(?m)^bind-address=127\.0\.0\.1\r?$') 'my.ini bind 127.0.0.1'
Assert-Equal $true ($ini -match '(?m)^character-set-server=utf8mb4\r?$') 'my.ini utf8mb4'
Assert-Equal $false ($ini -match '[^\x00-\x7F]') 'my.ini ASCII only'

# --- Get-TunnelConfigInfo (rendered from the shipped template)
$yml = Join-Path ([IO.Path]::GetTempPath()) ("couple-test-{0}.yml" -f [guid]::NewGuid().ToString('N'))
try {
    Expand-Template (Join-Path (Split-Path -Parent $PSScriptRoot) 'cloudflared/config.yml.in') $yml @{ TUNNEL_ID = '6F1C0000-1111-2222-3333-444455556666'; HOSTNAME = 'couple.example.com'; CREDENTIALS_FILE = 'C:\couple\config\cloudflared\x.json' }
    $ti = Get-TunnelConfigInfo $yml
    Assert-Equal '6f1c0000-1111-2222-3333-444455556666' $ti.TunnelId 'tunnel id from config.yml'
    Assert-Equal 'couple.example.com' $ti.Hostname 'hostname from config.yml'
} finally {
    Remove-Item -LiteralPath $yml -ErrorAction SilentlyContinue
}
Assert-Equal '' (Get-TunnelConfigInfo 'does-not-exist.yml').Hostname 'missing config.yml'

# --- Invoke-NativeCapture keeps stdout/stderr apart and does not throw under ErrorActionPreference=Stop
$self = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$r = Invoke-NativeCapture $self @('-NoProfile', '-Command', "[Console]::Out.WriteLine('out1'); [Console]::Error.WriteLine('err1'); exit 3")
Assert-Equal 3 $r.ExitCode 'native exit code'
Assert-Equal 'out1' $r.Stdout 'native stdout'
Assert-Equal 'err1' $r.Stderr.Trim() 'native stderr'

if ($script:failures) { Write-Host "$script:failures test(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'all tests passed' -ForegroundColor Green
