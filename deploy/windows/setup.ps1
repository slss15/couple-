<#
.SYNOPSIS
  One-command first-time setup of the Couple server on Windows: MySQL, secrets, Cloudflare Tunnel, services.

.DESCRIPTION
  Run from the extracted couple-server-windows-x64-<version> folder in an elevated PowerShell (5.1 or 7):

    powershell -ExecutionPolicy Bypass -File .\setup.ps1
    powershell -ExecutionPolicy Bypass -File .\setup.ps1 -Hostname couple.example.com

  The only question is the public hostname (a domain already active on Cloudflare). Everything else is automatic
  and idempotent, so re-running after a failure (or later, to repair) is safe:
    0. doctor.ps1 preflight (read-only; stops on FAIL unless -IgnoreDoctor)
    1. admin check, TLS 1.2, <InstallDir> layout, config\couple.env with generated secrets
    2. MySQL: if no MySQL service exists, installs the pinned MySQL 8.4 LTS ZIP (sha256-verified, VC++ runtime if
       missing) as service MySQL84 with a random root password (config\mysql-root.txt), then creates database and
       user `couple` and writes DB_PASSWORD. With an existing MySQL it asks once for the root password.
    3. JAVA_OPTS -Xmx by RAM (only while JAVA_OPTS is not customized)
    4. SETUP_TOKEN for the first-run page https://<host>/setup?token=...
    5. Cloudflare Tunnel: cloudflared login (browser), tunnel create/reuse, DNS route, credentials
    6. install.ps1 (services + local health check), then https://<host>/api/v1/health through the tunnel
    7. prints the admin link and the GitHub Secrets; saves them to <InstallDir>\SETUP-RESULT.txt (admins only)
  This file is saved as UTF-8 with BOM so Windows PowerShell 5.1 reads its Chinese messages correctly.
#>
[CmdletBinding()]
param(
    # Public hostname, e.g. couple.example.com (asked interactively when omitted).
    [string]$Hostname,
    [string]$InstallDir = 'C:\couple',
    # Where the bundled MySQL is installed (program files / data). Ignored when a MySQL service already exists.
    [string]$MysqlDir = 'C:\mysql',
    [string]$MysqlDataDir = 'C:\mysql-data',
    # Root password of an already-installed MySQL (only needed when DB_PASSWORD is still empty).
    [SecureString]$MysqlRootPassword,
    # Name of the locally-managed Cloudflare Tunnel (created or reused).
    [string]$TunnelName = 'couple',
    # Replace an existing DNS record of the hostname with the tunnel CNAME without asking.
    [switch]$OverwriteDns,
    # Use a database configured by hand (DB_* in couple.env); do not install or touch MySQL.
    [switch]$SkipMysql,
    # Do not set up the Cloudflare Tunnel (local-only install).
    [switch]$SkipTunnel,
    # Continue even when doctor.ps1 reports FAIL (it still runs and prints its table).
    [switch]$IgnoreDoctor
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$script:StepName = '准备'
$script:StepCount = 7
function Enter-Step([int]$Number, [string]$Title) {
    $script:StepName = "$Number/$script:StepCount $Title"
    Write-Host ''
    Write-Step "[$Number/$script:StepCount] $Title"
}
function Write-Info([string]$Message) { Write-Host "    $Message" }
function Write-Ok([string]$Message) { Write-Host "    $Message" -ForegroundColor Green }

function ConvertFrom-SecureStringPlain([SecureString]$Secure) {
    if ($null -eq $Secure) { return '' }
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# Random hex plus a fixed tail, so it also passes a MEDIUM validate_password policy (upper, lower, digit, special).
function New-DbSecret { return ((Get-RandomHex 24) + 'Aa1-') }

function Read-YesNo([string]$Question) {
    $a = Read-Host "$Question (y/N)"
    return ($a -match '^(y|yes|是)$')
}

# ---- MySQL ----------------------------------------------------------------------------------------------------

function Find-MysqlService {
    $all = @(Get-CimInstance -ClassName Win32_Service | Where-Object { $_.PathName -match 'mysqld(-nt)?\.exe' })
    if ($all.Count -eq 0) { return $null }
    $svc = @($all | Where-Object { $_.Name -eq 'MySQL84' }) + @($all | Where-Object { $_.State -eq 'Running' }) + $all | Select-Object -First 1
    $bin = ''
    if ($svc.PathName -match '^\s*"?(?<exe>[^"]*?mysqld(?:-nt)?\.exe)') { $bin = Split-Path -Parent $Matches['exe'] }
    return [pscustomobject]@{ Name = $svc.Name; PathName = $svc.PathName; BinDir = $bin }
}

function Test-VcRedist {
    foreach ($key in @('HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64')) {
        $p = Get-ItemProperty -Path $key -ErrorAction SilentlyContinue
        if ($null -eq $p) { continue }
        $names = @($p.PSObject.Properties | ForEach-Object { $_.Name })
        if (-not ($names -contains 'Installed') -or [int]$p.Installed -ne 1) { continue }
        $major = if ($names -contains 'Major') { [int]$p.Major } else { 0 }
        $minor = if ($names -contains 'Minor') { [int]$p.Minor } else { 0 }
        # 14.20+ = Visual C++ 2019 or newer (2015-2022 share major version 14).
        if ($major -gt 14 -or ($major -eq 14 -and $minor -ge 20)) { return $true }
    }
    return $false
}

function Install-VcRedist([string]$DownloadDir, [string]$Proxy) {
    if (Test-VcRedist) { Write-Info 'VC++ 2019+ 运行库：已安装'; return }
    Write-Info 'VC++ 2019+ 运行库未安装（MySQL 依赖它），正在下载安装……'
    $exe = Join-Path $DownloadDir 'vc_redist.x64.exe'
    $iwr = @{ Uri = 'https://aka.ms/vs/17/release/vc_redist.x64.exe'; OutFile = $exe; UseBasicParsing = $true }
    if ($Proxy) { $iwr['Proxy'] = $Proxy }
    Invoke-WebRequest @iwr
    # Not pinnable (Microsoft replaces this file in place): require a valid Microsoft Authenticode signature instead.
    $sig = Get-AuthenticodeSignature -FilePath $exe
    if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
        throw "vc_redist.x64.exe 签名校验失败（$($sig.Status)）"
    }
    $p = Start-Process -FilePath $exe -ArgumentList '/install', '/quiet', '/norestart' -Wait -PassThru
    switch ($p.ExitCode) {
        0 { Write-Ok 'VC++ 运行库安装完成' }
        1638 { Write-Ok 'VC++ 运行库：已有更新的版本' }
        3010 { Write-Warning 'VC++ 运行库已安装，Windows 提示需要重启（可以先继续，之后找时间重启）' }
        default { throw "vc_redist.x64.exe 退出码 $($p.ExitCode)" }
    }
    Remove-Item -LiteralPath $exe -Force -ErrorAction SilentlyContinue
}

# Runs SQL with the mysql client. Credentials go through a temporary option file (never on the command line).
function Invoke-MysqlSql([string]$BinDir, [string]$User, [string]$Secret, [string]$Sql, [string]$TargetHost, [string]$Database) {
    $cnf = Join-Path $script:configDir (".mysql-client-{0}.cnf" -f [guid]::NewGuid().ToString('N'))
    $lines = @('[client]', "user=$User")
    if ($Secret) { $lines += "password=$Secret" }
    [IO.File]::WriteAllText($cnf, (($lines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)
    try {
        Protect-File $cnf
        $mysqlArgs = @("--defaults-extra-file=$cnf")
        if (-not $Secret) { $mysqlArgs += '--skip-password' }
        if ($TargetHost) { $mysqlArgs += "--host=$TargetHost" }
        $mysqlArgs += @('--batch', '--skip-column-names', '--default-character-set=utf8mb4', "--execute=$Sql")
        if ($Database) { $mysqlArgs += $Database }
        return (Invoke-NativeCapture (Join-Path $BinDir 'mysql.exe') $mysqlArgs)
    } finally {
        Remove-Item -LiteralPath $cnf -Force -ErrorAction SilentlyContinue
    }
}

function Wait-TcpPort([string]$TargetHost, [int]$Port, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $c = New-Object Net.Sockets.TcpClient
        try { $c.Connect($TargetHost, $Port); return $true } catch { Start-Sleep -Seconds 2 } finally { $c.Close() }
    }
    return $false
}

function Install-BundledMysql([hashtable]$Pins, [string]$Proxy, [long]$TotalRam) {
    $v = $Pins['MYSQL_VERSION']
    $mysqld = Join-Path $MysqlDir 'bin\mysqld.exe'
    Install-VcRedist $script:downloadDir $Proxy

    if (Test-Path -LiteralPath $mysqld) {
        Write-Info "MySQL 程序已在 $MysqlDir（保留）"
    } else {
        if ((Test-Path -LiteralPath $MysqlDir) -and @(Get-ChildItem -LiteralPath $MysqlDir -Force).Count -gt 0) {
            throw "$MysqlDir 已存在但里面没有 bin\mysqld.exe；请清空它或用 -MysqlDir 指定其他目录"
        }
        $zip = Join-Path $script:downloadDir "mysql-$v-winx64.zip"
        Write-Info "下载 MySQL $v（约 270 MB，sha256 校验）"
        Get-PinnedFile -Urls @(
            "https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-$v-winx64.zip",
            "https://cdn.mysql.com/archives/mysql-8.4/mysql-$v-winx64.zip"
        ) -Sha256 $Pins['MYSQL_WINDOWS_X64_SHA256'] -Dest $zip -Proxy $Proxy | Out-Null
        Write-Info "解压到 $MysqlDir"
        $extract = "$MysqlDir.extracting"
        if (Test-Path -LiteralPath $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, $extract)
        $inner = Join-Path $extract "mysql-$v-winx64"
        if (-not (Test-Path -LiteralPath (Join-Path $inner 'bin\mysqld.exe'))) { throw "MySQL zip 结构不符合预期（缺少 mysql-$v-winx64\bin\mysqld.exe）" }
        if (Test-Path -LiteralPath $MysqlDir) { Remove-Item -LiteralPath $MysqlDir -Force }
        Move-Item -LiteralPath $inner -Destination $MysqlDir
        Remove-Item -LiteralPath $extract -Recurse -Force
        Remove-Item -LiteralPath $zip -Force
    }

    $ini = Join-Path $MysqlDir 'my.ini'
    if (Test-Path -LiteralPath $ini) {
        Write-Info "my.ini 已存在（保留）：$ini"
    } else {
        $prof = Get-MemoryProfile $TotalRam
        [IO.File]::WriteAllText($ini, (New-MysqlIni $MysqlDir $MysqlDataDir $prof.InnodbBufferPool), [Text.Encoding]::ASCII)
        Write-Info "写入 my.ini（innodb_buffer_pool_size=$($prof.InnodbBufferPool)，performance_schema=OFF，只监听 127.0.0.1）"
    }

    $dataEmpty = (-not (Test-Path -LiteralPath $MysqlDataDir)) -or @(Get-ChildItem -LiteralPath $MysqlDataDir -Force).Count -eq 0
    if ($dataEmpty) {
        Write-Info "初始化数据目录 $MysqlDataDir"
        $r = Invoke-NativeCapture $mysqld @("--defaults-file=$ini", '--initialize-insecure', '--console')
        if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw "mysqld --initialize-insecure 失败（$($r.ExitCode)）" }
    } else {
        Write-Info "数据目录已初始化（保留）：$MysqlDataDir"
    }

    if ((Get-ServiceState 'MySQL84') -eq 'Missing') {
        Write-Info '注册服务 MySQL84'
        $r = Invoke-NativeCapture $mysqld @('--install', 'MySQL84', "--defaults-file=$ini")
        if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw "mysqld --install 失败（$($r.ExitCode)）" }
        Set-Service -Name 'MySQL84' -StartupType Automatic
    }
    return (Join-Path $MysqlDir 'bin')
}

# Returns the root password for the MySQL in $BinDir: config\mysql-root.txt, -MysqlRootPassword, a fresh
# --initialize-insecure root (sets a random password and saves it), or an interactive prompt.
function Get-MysqlRootSecret([string]$BinDir, [bool]$Bundled, [SecureString]$Given) {
    $saved = (Read-KeyValueFile $script:rootFile)['MYSQL_ROOT_PASSWORD']
    if ($saved) {
        if ((Invoke-MysqlSql $BinDir 'root' $saved 'SELECT 1').ExitCode -eq 0) { return $saved }
        Write-Warning "config\mysql-root.txt 里的 root 密码无法登录 MySQL"
    }
    $given = ConvertFrom-SecureStringPlain $Given
    if ($given) {
        if ((Invoke-MysqlSql $BinDir 'root' $given 'SELECT 1').ExitCode -eq 0) { return $given }
        throw '-MysqlRootPassword 无法登录 MySQL（root@localhost）'
    }
    if ($Bundled -and (Invoke-MysqlSql $BinDir 'root' '' 'SELECT 1').ExitCode -eq 0) {
        # Fresh --initialize-insecure: save first, then set, so the password is never lost half-way.
        $new = New-DbSecret
        Write-Utf8NoBom $script:rootFile ("# MySQL root password, generated by setup.ps1. Keep it secret.`r`nMYSQL_ROOT_PASSWORD=$new`r`n")
        Protect-File $script:rootFile
        $r = Invoke-MysqlSql $BinDir 'root' '' "ALTER USER 'root'@'localhost' IDENTIFIED BY '$new';"
        if ($r.ExitCode -ne 0) { Remove-Item -LiteralPath $script:rootFile -Force; Write-Host $r.Output; throw '设置 MySQL root 密码失败' }
        Write-Ok "已设置随机 root 密码，保存在 $script:rootFile（仅管理员可读）"
        return $new
    }
    for ($i = 1; $i -le 3; $i++) {
        $pw = ConvertFrom-SecureStringPlain (Read-Host -AsSecureString "请输入现有 MySQL 的 root 密码（只用这一次，用来创建数据库 couple 和用户 couple）")
        if ((Invoke-MysqlSql $BinDir 'root' $pw 'SELECT 1').ExitCode -eq 0) { return $pw }
        Write-Warning 'root 密码不对，请重试'
    }
    throw '无法以 root 登录 MySQL；可用 -MysqlRootPassword 传入，或用 -SkipMysql 并手动填写 couple.env 的 DB_PASSWORD'
}

function Initialize-CoupleDatabase([string]$BinDir, [string]$RootSecret, [string]$DbSecret) {
    $ver = (Invoke-MysqlSql $BinDir 'root' $RootSecret 'SELECT VERSION()').Stdout.Trim()
    $collation = if ($ver -match '^5\.') { 'utf8mb4_unicode_ci' } else { 'utf8mb4_0900_ai_ci' }
    $sql = @(
        "CREATE DATABASE IF NOT EXISTS couple CHARACTER SET utf8mb4 COLLATE $collation;",
        "CREATE USER IF NOT EXISTS 'couple'@'127.0.0.1' IDENTIFIED BY '$DbSecret';",
        "CREATE USER IF NOT EXISTS 'couple'@'localhost' IDENTIFIED BY '$DbSecret';",
        "ALTER USER 'couple'@'127.0.0.1' IDENTIFIED BY '$DbSecret';",
        "ALTER USER 'couple'@'localhost' IDENTIFIED BY '$DbSecret';",
        "GRANT ALL PRIVILEGES ON couple.* TO 'couple'@'127.0.0.1', 'couple'@'localhost';",
        'FLUSH PRIVILEGES;'
    ) -join ' '
    $r = Invoke-MysqlSql $BinDir 'root' $RootSecret $sql
    if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw '创建数据库/用户 couple 失败' }
}

function Invoke-MysqlStep([hashtable]$Pins, [string]$Proxy, [long]$TotalRam, [SecureString]$RootPassword) {
    $svc = Find-MysqlService
    $bundled = $false
    if ($null -eq $svc) {
        Write-Info "未发现 MySQL 服务，安装 MySQL $($Pins['MYSQL_VERSION']) LTS 到 $MysqlDir（数据在 $MysqlDataDir）"
        $binDir = Install-BundledMysql $Pins $Proxy $TotalRam
        $svcName = 'MySQL84'
        $bundled = $true
    } else {
        Write-Info "发现已有 MySQL 服务 $($svc.Name)（$($svc.BinDir)）"
        if (-not $svc.BinDir -or -not (Test-Path -LiteralPath (Join-Path $svc.BinDir 'mysql.exe'))) {
            throw "找不到 $($svc.Name) 对应的 mysql.exe；请手动建库（附录 A.2）后用 -SkipMysql 重新运行"
        }
        $binDir = $svc.BinDir
        $svcName = $svc.Name
        $bundled = ($svcName -eq 'MySQL84' -and [IO.Path]::GetFullPath($binDir) -eq [IO.Path]::GetFullPath((Join-Path $MysqlDir 'bin')))
    }
    if ((Get-ServiceState $svcName) -ne 'Running') {
        Write-Info "启动服务 $svcName"
        Start-Service -Name $svcName
    }
    if (-not (Wait-TcpPort '127.0.0.1' 3306 60)) { throw "MySQL 没有在 60 秒内监听 127.0.0.1:3306；看 $MysqlDataDir\mysqld.err" }
    Write-Ok "MySQL 服务 $svcName 运行中"

    $dbSecret = (Read-KeyValueFile $script:envFile)['DB_PASSWORD']
    if ($dbSecret) {
        if ((Invoke-MysqlSql $binDir 'couple' $dbSecret 'SELECT 1' '127.0.0.1' 'couple').ExitCode -eq 0) {
            Write-Ok 'couple.env 的 DB_PASSWORD 可以登录数据库 couple（保留）'
            return
        }
        Write-Warning 'couple.env 的 DB_PASSWORD 无法登录数据库 couple，用 root 修复（把用户 couple 的密码设为 DB_PASSWORD）'
        $root = Get-MysqlRootSecret $binDir $bundled $RootPassword
        Initialize-CoupleDatabase $binDir $root $dbSecret
    } else {
        $root = Get-MysqlRootSecret $binDir $bundled $RootPassword
        $dbSecret = New-DbSecret
        Initialize-CoupleDatabase $binDir $root $dbSecret
        Set-EnvValue $script:envFile 'DB_PASSWORD' $dbSecret | Out-Null
        Write-Ok '已创建数据库 couple 和用户 couple，DB_PASSWORD 已写入 couple.env'
    }
    if ((Invoke-MysqlSql $binDir 'couple' $dbSecret 'SELECT 1' '127.0.0.1' 'couple').ExitCode -ne 0) {
        throw '用户 couple 仍无法通过 127.0.0.1 登录数据库 couple'
    }
}

# ---- Cloudflare Tunnel ----------------------------------------------------------------------------------------

function Invoke-Cloudflared([string]$Exe, [string]$Cert, [string[]]$Arguments) {
    return (Invoke-NativeCapture $Exe (@('tunnel', '--origincert', $Cert) + $Arguments))
}

function Find-Tunnel([string]$Exe, [string]$Cert, [string]$Name) {
    $r = Invoke-Cloudflared $Exe $Cert @('list', '--output', 'json', '--name', $Name)
    if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw 'cloudflared tunnel list 失败' }
    $json = $r.Stdout.Trim()
    if (-not $json -or $json -eq 'null') { return $null }
    $match = @(ConvertFrom-Json $json | ForEach-Object { $_ } | Where-Object { $_.name -eq $Name }) | Select-Object -First 1
    if ($null -eq $match) { return $null }
    return ([string]$match.id).ToLowerInvariant()
}

function Invoke-TunnelStep([hashtable]$Pins, [string]$Proxy, [string]$Name, [bool]$Overwrite) {
    $cf = Install-Cloudflared (Join-Path $InstallDir 'cloudflared') $Pins $Proxy
    Write-Info "cloudflared $($Pins['CLOUDFLARED_VERSION'])：$cf"
    $cfHome = Join-Path $env:USERPROFILE '.cloudflared'
    $cert = Join-Path $cfHome 'cert.pem'

    if (Test-Path -LiteralPath $cert) {
        Write-Info "已登录 Cloudflare（$cert）"
    } else {
        Write-Host ''
        Write-Host '    需要授权 Cloudflare：下面会打印一个链接（有浏览器时会自动打开）。' -ForegroundColor Yellow
        Write-Host "    在任意电脑的浏览器里打开它，登录 Cloudflare，选择 $Hostname 所属的域名，点 Authorize。" -ForegroundColor Yellow
        Write-Host '    授权完成后这里会自动继续（cloudflared 最多等待几分钟）。' -ForegroundColor Yellow
        Write-Host ''
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $cf tunnel login } finally { $ErrorActionPreference = $old }
        if (-not (Test-Path -LiteralPath $cert)) { throw "Cloudflare 授权未完成（没有生成 $cert）；重新运行 setup.ps1 再试" }
        Write-Ok 'Cloudflare 授权完成'
    }

    $id = Find-Tunnel $cf $cert $Name
    if ($id) {
        Write-Info "复用已有隧道 $Name（$id）"
    } else {
        Write-Info "创建隧道 $Name"
        $r = Invoke-Cloudflared $cf $cert @('create', $Name)
        if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw 'cloudflared tunnel create 失败' }
        $id = Find-Tunnel $cf $cert $Name
        if (-not $id -and $r.Output -match '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})') { $id = $Matches[1] }
        if (-not $id) { Write-Host $r.Output; throw '创建隧道后找不到隧道 ID' }
        Write-Ok "已创建隧道 $Name（$id）"
    }

    $credInstalled = Join-Path $script:configDir "cloudflared\$id.json"
    $credHome = Join-Path $cfHome "$id.json"
    $credFile = $null
    foreach ($c in @($credInstalled, $credHome)) { if (Test-Path -LiteralPath $c) { $credFile = $c; break } }
    if (-not $credFile) {
        Write-Info '本机没有该隧道的凭证文件，重新获取（cloudflared tunnel token --cred-file）'
        $r = Invoke-Cloudflared $cf $cert @('token', '--cred-file', $credHome, $id)
        if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $credHome)) { Write-Host $r.Output; throw "无法获取隧道 $id 的凭证文件" }
        $credFile = $credHome
    }
    Write-Info "隧道凭证：$credFile"

    Write-Info "DNS：$Hostname → $id.cfargotunnel.com"
    $routeArgs = @('route', 'dns')
    if ($Overwrite) { $routeArgs += '--overwrite-dns' }
    $r = Invoke-Cloudflared $cf $cert ($routeArgs + @($id, $Hostname))
    if ($r.ExitCode -ne 0) {
        if ($r.Output -match 'already exists' -and -not $Overwrite) {
            Write-Warning "$Hostname 已经有一条 DNS 记录（A/AAAA/CNAME），不是指向这个隧道。"
            if (Read-YesNo "    改为指向隧道 $Name？原记录会被覆盖") {
                $r = Invoke-Cloudflared $cf $cert @('route', 'dns', '--overwrite-dns', $id, $Hostname)
                if ($r.ExitCode -ne 0) { Write-Host $r.Output; throw 'cloudflared tunnel route dns --overwrite-dns 失败' }
            } else {
                Write-Warning "保留原 DNS 记录：$Hostname 不会经过隧道，请到 Cloudflare DNS 面板处理后重新运行"
            }
        } else {
            Write-Host $r.Output
            throw 'cloudflared tunnel route dns 失败'
        }
    }
    Write-Ok 'DNS 已指向隧道'

    $cfConfig = Join-Path $script:configDir 'cloudflared\config.yml'
    $prev = Get-TunnelConfigInfo $cfConfig
    $force = (Test-Path -LiteralPath $cfConfig) -and ($prev.TunnelId -ne $id -or $prev.Hostname -ne $Hostname)
    if ($force) { Write-Info "隧道配置变了（$($prev.Hostname) / $($prev.TunnelId)），重新生成 config.yml（旧的存为 config.yml.bak）" }
    return @{ TunnelId = $id; TunnelHostname = $Hostname; TunnelJsonFile = $credFile; ForceTunnelConfig = [bool]$force }
}

function Test-PublicHealth([string]$Url, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
            if ($resp.StatusCode -eq 200 -and $resp.Content -match '"status"') { return $true }
        } catch { Write-Verbose "public health not ready: $($_.Exception.Message)" }
        Start-Sleep -Seconds 5
    }
    return $false
}

# ---------------------------------------------------------------------------------------------------------------
if (-not (Test-Admin)) {
    Write-Host '请用“以管理员身份运行”的 PowerShell 执行 setup.ps1。' -ForegroundColor Red
    exit 1
}
Enable-Tls12

# Preflight (read-only). A FAIL stops here, before anything is installed.
$doctorArgs = @{ InstallDir = $InstallDir }
if ($SkipTunnel) { $doctorArgs['SkipTunnel'] = $true }
if ($SkipMysql) { $doctorArgs['SkipMysql'] = $true }
& (Join-Path $PSScriptRoot 'doctor.ps1') @doctorArgs
$doctorExit = $LASTEXITCODE
if ($doctorExit -ne 0) {
    if ($IgnoreDoctor) {
        Write-Warning '环境检查有 FAIL 项，因为 -IgnoreDoctor 继续安装'
    } else {
        Write-Host '环境检查有 FAIL 项，没有做任何改动。按表格里的“修复建议”处理后重新运行 setup.ps1（确认无碍可加 -IgnoreDoctor 跳过）。' -ForegroundColor Red
        exit 1
    }
}

$pkg = $PSScriptRoot
$version = ([IO.File]::ReadAllText((Join-Path $pkg 'VERSION'))).Trim()
$pins = Read-KeyValueFile (Join-Path $pkg 'versions.env')
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$MysqlDir = [IO.Path]::GetFullPath($MysqlDir)
$MysqlDataDir = [IO.Path]::GetFullPath($MysqlDataDir)
$script:configDir = Join-Path $InstallDir 'config'
$script:envFile = Join-Path $script:configDir 'couple.env'
$script:rootFile = Join-Path $script:configDir 'mysql-root.txt'
$script:downloadDir = Join-Path $InstallDir 'data\downloads'
$resultFile = Join-Path $InstallDir 'SETUP-RESULT.txt'

Write-Host "Couple 服务器 $version 一键安装 → $InstallDir" -ForegroundColor Cyan

# Hostname: parameter, else ask (default: the one from a previous run).
$prevHost = (Get-TunnelConfigInfo (Join-Path $script:configDir 'cloudflared\config.yml')).Hostname
$hostInput = $Hostname
for ($try = 1; -not (ConvertTo-Hostname $hostInput); $try++) {
    if ($try -gt 1 -or $Hostname) { Write-Warning "「$hostInput」 不是有效的域名（例：couple.example.com，不带 https:// 和路径）" }
    if ($try -gt 3) { exit 1 }
    $prompt = '请输入服务器域名（必须是已接入 Cloudflare 的域名，例如 couple.example.com）'
    if ($prevHost) { $prompt += "，直接回车 = $prevHost" }
    $hostInput = Read-Host $prompt
    if (-not $hostInput -and $prevHost) { $hostInput = $prevHost }
}
$Hostname = ConvertTo-Hostname $hostInput

Initialize-CoupleLayout $InstallDir
New-Item -ItemType Directory -Force -Path $script:downloadDir | Out-Null
$log = Join-Path $InstallDir ("logs\setup-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$transcript = $false
try { Start-Transcript -Path $log | Out-Null; $transcript = $true; Protect-File $log } catch { Write-Warning "无法写安装日志 ${log}: $($_.Exception.Message)" }

$exitCode = 0
try {
    Enter-Step 1 "准备：目录与配置文件（域名 $Hostname）"
    Initialize-CoupleEnv $script:envFile (Join-Path $pkg 'config\couple.env.example') @('JWT_SECRET', 'MQTT_HOOK_SECRET', 'DEPLOY_HOOK_SECRET')
    $proxy = (Read-KeyValueFile $script:envFile)['HTTPS_PROXY']
    if ($proxy) { Write-Info "使用代理 $proxy 下载" }
    $totalRam = [long](Get-CimInstance -ClassName Win32_ComputerSystem).TotalPhysicalMemory
    $memProfile = Get-MemoryProfile $totalRam
    Write-Info ("内存 {0:N1} GB → MySQL 缓冲池 {1}，后端 -Xmx{2}" -f ($totalRam / 1GB), $memProfile.InnodbBufferPool, $memProfile.JvmXmx)

    Enter-Step 2 'MySQL 数据库'
    if ($SkipMysql) { Write-Info '-SkipMysql：跳过（使用 couple.env 里手动填写的 DB_*）' } else { Invoke-MysqlStep $pins $proxy $totalRam $MysqlRootPassword }

    Enter-Step 3 'JVM 内存（JAVA_OPTS）'
    $javaOpts = (Read-KeyValueFile $script:envFile)['JAVA_OPTS']
    if (Test-JavaOptsAutoManaged $javaOpts) {
        $want = Get-DefaultJavaOption $memProfile.JvmXmx
        if (Set-EnvValue $script:envFile 'JAVA_OPTS' $want) { Write-Ok "JAVA_OPTS=$want" } else { Write-Info "JAVA_OPTS 已是 -Xmx$($memProfile.JvmXmx)（保留）" }
    } else {
        Write-Info "JAVA_OPTS 已自定义（保留）：$javaOpts"
    }

    Enter-Step 4 '首次设置令牌（SETUP_TOKEN）'
    if (Set-EnvIfEmpty $script:envFile 'SETUP_TOKEN' (Get-RandomHex 16)) { Write-Ok '已生成 SETUP_TOKEN' } else { Write-Info 'SETUP_TOKEN 已存在（保留）' }
    Protect-File $script:envFile

    Enter-Step 5 'Cloudflare Tunnel'
    $installArgs = @{ InstallDir = $InstallDir }
    if ($SkipTunnel) {
        Write-Info '-SkipTunnel：跳过（只在本机 127.0.0.1 可访问）'
    } else {
        $t = Invoke-TunnelStep $pins $proxy $TunnelName ([bool]$OverwriteDns)
        $installArgs['TunnelId'] = $t.TunnelId
        $installArgs['TunnelHostname'] = $t.TunnelHostname
        $installArgs['TunnelJsonFile'] = $t.TunnelJsonFile
        if ($t.ForceTunnelConfig) { $installArgs['ForceTunnelConfig'] = $true }
    }

    Enter-Step 6 '安装/升级服务（install.ps1）并检查健康'
    & (Join-Path $pkg 'install.ps1') @installArgs
    $publicOk = $false
    $healthUrl = "https://$Hostname/api/v1/health"
    if (-not $SkipTunnel) {
        Write-Info "经 Cloudflare 检查 $healthUrl（最多 60 秒）"
        $publicOk = Test-PublicHealth $healthUrl 60
        if ($publicOk) { Write-Ok '公网访问正常' } else { Write-Warning "公网暂时访问不到 $healthUrl（DNS 生效可能要几分钟）；稍后在浏览器里再试，仍不行看附录 11 故障排查" }
    }

    Enter-Step 7 '完成'
    $cfg = Read-KeyValueFile $script:envFile
    $setupUrl = "https://$Hostname/setup?token=$($cfg['SETUP_TOKEN'])"
    $bar = '=' * 78
    $summary = @(
        $bar,
        "  Couple 服务器安装完成（$version，$(Get-Date -Format 'yyyy-MM-dd HH:mm')）",
        $bar,
        '',
        '  ① 管理后台首次设置（浏览器打开，创建管理员账号）：',
        "     $setupUrl",
        '',
        '  ② GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret：',
        "     DEPLOY_HOOK_URL     = https://$Hostname",
        "     DEPLOY_HOOK_SECRET  = $($cfg['DEPLOY_HOOK_SECRET'])",
        "     （DEPLOY_HOOK_URL 只填域名，部署脚本会自己加 /_deploy/hook）",
        '',
        '  ③ Cloudflare 控制台 → Security → Bots：关闭 Bot Fight Mode（必须，否则 App 和自动部署会被拦截）',
        '     https://dash.cloudflare.com/?to=/:account/:zone/security/bots',
        '',
        '  ④ 在管理后台生成“引导二维码”，两台手机扫码。',
        '',
        "  公网健康检查：$(if ($SkipTunnel) { '已跳过（-SkipTunnel）' } elseif ($publicOk) { '正常' } else { '暂未通过，稍后重试' })",
        "  配置文件：$script:envFile",
        $(if (Test-Path -LiteralPath $script:rootFile) { "  MySQL root 密码：$script:rootFile" } else { $null }),
        "  安装日志：$log",
        "  本摘要已保存到：$resultFile（仅管理员可读，含密钥）",
        $bar
    ) | Where-Object { $null -ne $_ }
    Write-Host ''
    $summary | ForEach-Object { Write-Host $_ -ForegroundColor Green }
    [IO.File]::WriteAllText($resultFile, (($summary -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding $true))
    Protect-File $resultFile
} catch {
    $exitCode = 1
    Write-Host ''
    Write-Host "安装失败，步骤：[$script:StepName]" -ForegroundColor Red
    Write-Host "错误：$($_.Exception.Message)" -ForegroundColor Red
    if ($_.InvocationInfo -and $_.InvocationInfo.PositionMessage) { Write-Host $_.InvocationInfo.PositionMessage }
    Write-Host "安装日志：$log" -ForegroundColor Yellow
    Write-Host "服务日志：$InstallDir\logs\" -ForegroundColor Yellow
    Write-Host '修复后直接重新运行 setup.ps1 即可（每一步都可以重复执行）。' -ForegroundColor Yellow
} finally {
    if ($transcript) { try { Stop-Transcript | Out-Null } catch { Write-Verbose 'transcript already stopped' } }
}
exit $exitCode
