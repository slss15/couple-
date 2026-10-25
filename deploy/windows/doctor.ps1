<#
.SYNOPSIS
  Preflight check of a Windows host for the Couple server. Read-only: changes nothing.

.DESCRIPTION
  Run standalone from the extracted package (or C:\couple\releases\<version>) in an elevated PowerShell (5.1 or 7):

    powershell -ExecutionPolicy Bypass -File .\doctor.ps1
    powershell -ExecutionPolicy Bypass -File .\doctor.ps1 -SkipTunnel      # local-only install

  setup.ps1 runs it first and stops when a check FAILs (setup.ps1 -IgnoreDoctor skips that).
  Checks: admin rights, PowerShell version, OS build, RAM, free disk on C:, ports PORT/MQTT_WS_PORT/HOOK_PORT/3306
  (free or owned by our services), proxy settings, HTTPS to github.com, objects.githubusercontent.com,
  cdn.mysql.com, api.cloudflare.com, TCP region1.v2.argotunnel.com:7844, clock skew (HTTP Date header), console
  code page, Defender / AppLocker / execution policy hints.
  Prints a zh-CN table of PASS / WARN / FAIL with a fix hint per row. Exit code 1 when any check FAILs, else 0.
  Network checks use couple.env HTTPS_PROXY when set, otherwise a direct connection (that is what the services,
  running as SYSTEM, will use).
  This file is saved as UTF-8 with BOM so Windows PowerShell 5.1 reads its Chinese messages correctly.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\couple',
    # Tunnel-only checks (api.cloudflare.com, TCP 7844) become WARN instead of FAIL.
    [switch]$SkipTunnel,
    # MySQL is configured by hand: no port 3306 / cdn.mysql.com checks.
    [switch]$SkipMysql,
    # Skip all network checks (offline quick run).
    [switch]$NoNetwork,
    [int]$TimeoutSec = 8
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$script:results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$Item, [string]$Status, [string]$Detail, [string]$Hint) {
    $script:results.Add((New-DoctorResult $Item $Status $Detail $Hint))
}
# Runs one check; an unexpected error becomes a WARN row instead of aborting the whole doctor.
function Invoke-Check([string]$Item, [scriptblock]$Body) {
    try { & $Body } catch { Add-Result $Item 'WARN' "无法检查：$($_.Exception.Message)" '手动确认这一项' }
}

$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$cfg = Read-KeyValueFile (Join-Path $InstallDir 'config\couple.env')
$proxy = $cfg['HTTPS_PROXY']
$script:timeout = [Math]::Max(2, $TimeoutSec)
$onWindows = ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT)

Write-Host ''
Write-Host "Couple 环境检查（doctor）  安装目录 $InstallDir" -ForegroundColor Cyan

# ---- local checks ---------------------------------------------------------------------------------------------

Invoke-Check '管理员权限' {
    if (Test-Admin) { Add-Result '管理员权限' 'PASS' '已提升（管理员）' '' }
    else { Add-Result '管理员权限' 'FAIL' '当前不是管理员' '开始菜单右键 Windows PowerShell →「以管理员身份运行」' }
}

Invoke-Check 'PowerShell 版本' {
    $v = $PSVersionTable.PSVersion
    $ed = if ($PSVersionTable.ContainsKey('PSEdition')) { $PSVersionTable.PSEdition } else { 'Desktop' }
    $ok = ($v.Major -gt 5) -or ($v.Major -eq 5 -and $v.Minor -ge 1)
    if ($ok) { Add-Result 'PowerShell 版本' 'PASS' "$v（$ed）" '' }
    else { Add-Result 'PowerShell 版本' 'FAIL' "$v" '安装 Windows Management Framework 5.1（或 PowerShell 7）' }
}

Invoke-Check '操作系统' {
    if (-not $onWindows) { Add-Result '操作系统' 'FAIL' "不是 Windows（$([Environment]::OSVersion.VersionString)）" '只支持 Windows Server 2016+ / Windows 10+ x64'; return }
    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    $build = [int]$os.BuildNumber
    $status = Get-OsBuildStatus $build
    $detail = "$($os.Caption) build $build，$($os.OSArchitecture)"
    if (-not [Environment]::Is64BitOperatingSystem) { $status = 'FAIL'; $detail += '（32 位）' }
    $hint = switch ($status) { 'PASS' { '' } 'WARN' { '低于 Server 2016 / Win10 1607 未经测试；建议升级系统' } default { '需要 64 位 Windows Server 2016+ / Windows 10+' } }
    Add-Result '操作系统' $status $detail $hint
}

Invoke-Check '内存' {
    $total = [double](Get-CimInstance -ClassName Win32_ComputerSystem).TotalPhysicalMemory
    $gb = $total / 1GB
    $status = Get-ThresholdStatus $gb 1.5 2.0
    $prof = Get-MemoryProfile ([long]$total)
    $hint = if ($status -eq 'PASS') { '' } else { '至少 2 GB；内存小时 setup.ps1 会自动调小 MySQL 缓冲池和后端 -Xmx' }
    Add-Result '内存' $status ("{0:N1} GB（配置档 {1}）" -f $gb, $prof.Name) $hint
}

function Add-DiskResult([string]$Drive) {
    $d = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='$Drive'"
    if ($null -eq $d) { Add-Result "$Drive 可用空间" 'FAIL' "没有 $Drive 盘" '检查安装目录所在的盘'; return }
    $free = [double]$d.FreeSpace / 1GB
    $status = Get-ThresholdStatus $free 2 5
    $hint = if ($status -eq 'PASS') { '' } else { '需要约 2 GB（MySQL ~1 GB、3 个版本的安装包、日志）；建议留 5 GB 以上。清理磁盘或扩容' }
    Add-Result "$Drive 可用空间" $status ("{0:N1} GB 可用 / 共 {1:N0} GB" -f $free, ([double]$d.Size / 1GB)) $hint
}
Invoke-Check 'C: 可用空间' {
    Add-DiskResult 'C:'
    $installDrive = [IO.Path]::GetPathRoot($InstallDir).TrimEnd('\')
    if ($installDrive -and $installDrive -ne 'C:') { Add-DiskResult $installDrive }
}

# ---- ports ----------------------------------------------------------------------------------------------------

function Get-ListenerProcessId([int]$Port) {
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.OwningProcess } | Select-Object -Unique)
    }
    $rows = @(ConvertFrom-NetstatListen @(& netstat.exe -ano -p TCP)) + @(ConvertFrom-NetstatListen @(& netstat.exe -ano -p TCPv6))
    return @($rows | Where-Object { $_.Port -eq $Port } | ForEach-Object { $_.ProcessId } | Select-Object -Unique)
}

$script:svcByPid = $null
$script:procById = $null
function Get-PortOwner([int]$ProcessId) {
    if ($null -eq $script:svcByPid) {
        $script:svcByPid = @{}
        foreach ($s in @(Get-CimInstance -ClassName Win32_Service | Where-Object { $_.ProcessId -gt 0 })) {
            if (-not $script:svcByPid.ContainsKey([int]$s.ProcessId)) { $script:svcByPid[[int]$s.ProcessId] = [string]$s.Name }
        }
        $script:procById = @{}
        foreach ($p in @(Get-CimInstance -ClassName Win32_Process)) { $script:procById[[int]$p.ProcessId] = $p }
    }
    $proc = $script:procById[$ProcessId]
    $name = if ($null -ne $proc) { [string]$proc.Name } else { "PID $ProcessId" }
    # WinSW services: the listener is the java.exe child of the service wrapper process.
    $svc = ''
    if ($script:svcByPid.ContainsKey($ProcessId)) { $svc = $script:svcByPid[$ProcessId] }
    elseif ($null -ne $proc -and $script:svcByPid.ContainsKey([int]$proc.ParentProcessId)) { $svc = $script:svcByPid[[int]$proc.ParentProcessId] }
    return [pscustomobject]@{ Service = $svc; Process = $name }
}

$tp = Get-ExpectedTunnelPort $cfg
$portChecks = @(
    @{ Port = $tp.Backend; Label = '后端 PORT'; Allowed = @('couple-backend'); Mysql = $false; Hint = '停掉占用程序，或在 couple.env 改 PORT（install.ps1 会让 cloudflared 配置跟着改）' },
    @{ Port = $tp.MqttWs; Label = 'MQTT_WS_PORT'; Allowed = @('couple-backend'); Mysql = $false; Hint = '停掉占用程序，或在 couple.env 改 MQTT_WS_PORT' },
    @{ Port = $tp.Hook; Label = 'updater HOOK_PORT'; Allowed = @('couple-updater'); Mysql = $false; Hint = '停掉占用程序，或在 couple.env 设置 HOOK_PORT' }
)
if (-not $SkipMysql) {
    $portChecks += @{ Port = 3306; Label = 'MySQL'; Allowed = @(); Mysql = $true; Hint = '3306 被非 MySQL 程序占用：停掉它，或用 -SkipMysql 并在 couple.env 手动配置 DB_URL' }
}
foreach ($pc in $portChecks) {
    $item = "端口 $($pc.Port)（$($pc.Label)）"
    Invoke-Check $item {
        if (-not $onWindows) { throw '仅支持 Windows' }
        $pids = @(Get-ListenerProcessId $pc.Port | Where-Object { $_ -gt 0 })
        if ($pids.Count -eq 0) { Add-Result $item 'PASS' '空闲' ''; return }
        $owners = @($pids | ForEach-Object { Get-PortOwner $_ })
        $status = 'PASS'
        foreach ($o in $owners) { if ((Get-PortStatus $true $o.Service $o.Process $pc.Allowed $pc.Mysql) -eq 'FAIL') { $status = 'FAIL' } }
        $desc = ($owners | ForEach-Object { if ($_.Service) { "服务 $($_.Service)（$($_.Process)）" } else { $_.Process } }) -join '、'
        if ($status -eq 'PASS') { Add-Result $item 'PASS' "被 $desc 占用（正常）" '' }
        else { Add-Result $item 'FAIL' "被 $desc 占用" $pc.Hint }
    }
}

# ---- proxy ----------------------------------------------------------------------------------------------------

Invoke-Check '代理设置' {
    $system = @()
    if ($onWindows) {
        $blob = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Internet Settings\Connections' -Name WinHttpSettings -ErrorAction SilentlyContinue)
        if ($null -ne $blob) { $w = ConvertFrom-WinHttpSetting ([byte[]]$blob.WinHttpSettings); if ($w) { $system += "WinHTTP=$(Hide-UrlCredential $w)" } }
        $ie = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
        if ($null -ne $ie) {
            $names = @($ie.PSObject.Properties | ForEach-Object { $_.Name })
            if (($names -contains 'ProxyEnable') -and [int]$ie.ProxyEnable -eq 1 -and ($names -contains 'ProxyServer') -and $ie.ProxyServer) { $system += "当前用户=$(Hide-UrlCredential ([string]$ie.ProxyServer))" }
        }
    }
    foreach ($n in 'HTTPS_PROXY', 'https_proxy', 'ALL_PROXY') {
        $v = [Environment]::GetEnvironmentVariable($n)
        if ($v) { $system += "环境变量 $n=$(Hide-UrlCredential $v)"; break }
    }
    if ($proxy) {
        $u = $null
        try { $u = [Uri]$(if ($proxy -match '://') { $proxy } else { "http://$proxy" }) } catch { $u = $null }
        if ($null -eq $u -or -not $u.Host) { Add-Result '代理设置' 'FAIL' "couple.env HTTPS_PROXY 格式不对：$(Hide-UrlCredential $proxy)" '写成 http://主机:端口（例 http://127.0.0.1:7890）'; return }
        $port = if ($u.Port -gt 0) { $u.Port } else { 80 }
        $err = Test-TcpConnect $u.Host $port 5
        if ($err) { Add-Result '代理设置' 'FAIL' "couple.env HTTPS_PROXY=$($u.Host):$port 连不上（$err）" '确认代理程序在运行并允许本机连接，或清空 HTTPS_PROXY' }
        else { Add-Result '代理设置' 'PASS' "使用 couple.env HTTPS_PROXY=$($u.Host):$port（可连通）" '' }
    } elseif ($system.Count -gt 0) {
        Add-Result '代理设置' 'WARN' ("系统代理 {0}；couple.env 未设置 HTTPS_PROXY" -f ($system -join '，')) '服务以 SYSTEM 运行，不会用系统/用户代理；需要代理才能上网时，把它写进 couple.env 的 HTTPS_PROXY'
    } else {
        Add-Result '代理设置' 'PASS' '直连（未配置代理）' ''
    }
}

# ---- network --------------------------------------------------------------------------------------------------

$script:dateHeader = ''
if ($NoNetwork) {
    Add-Result '网络检查' 'WARN' '已跳过（-NoNetwork）' '去掉 -NoNetwork 再运行一次'
} else {
    $mysqlInstalled = $false
    if ($onWindows -and -not $SkipMysql) {
        try { $mysqlInstalled = @(Get-CimInstance -ClassName Win32_Service | Where-Object { $_.PathName -match 'mysqld(-nt)?\.exe' }).Count -gt 0 } catch { $mysqlInstalled = $false }
    }
    $targets = @(
        @{ Host = 'github.com'; Required = $true; Why = '下载 cloudflared、版本更新' },
        @{ Host = 'objects.githubusercontent.com'; Required = $true; Why = 'GitHub Release 文件实际从这里下载' },
        @{ Host = 'cdn.mysql.com'; Required = (-not $SkipMysql -and -not $mysqlInstalled); Why = '下载 MySQL 8.4' },
        @{ Host = 'api.cloudflare.com'; Required = (-not $SkipTunnel); Why = 'cloudflared 登录/建隧道/改 DNS' }
    )
    $via = if ($proxy) { "经代理" } else { '直连' }
    foreach ($t in $targets) {
        $item = "HTTPS $($t.Host)"
        Invoke-Check $item {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            $r = if ($proxy) { Invoke-HttpProbe "https://$($t.Host)/" $script:timeout $proxy -Method HEAD } else { Invoke-HttpProbe "https://$($t.Host)/" $script:timeout -Direct -Method HEAD }
            $sec = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
            if ($r.Status -gt 0) {
                if (-not $script:dateHeader -and $r.Date) { $script:dateHeader = $r.Date }
                Add-Result $item 'PASS' "HTTP $($r.Status)，$sec 秒（$via）" ''
            } else {
                $status = if ($t.Required) { 'FAIL' } else { 'WARN' }
                Add-Result $item $status "连不上（$via）：$($r.Error)" "用途：$($t.Why)。放行出站 HTTPS 443；国内网络可在 couple.env 设置 HTTPS_PROXY；系统时间不准也会导致 TLS 失败"
            }
        }
    }

    $item = 'TCP region1.v2.argotunnel.com:7844'
    Invoke-Check $item {
        $err = Test-TcpConnect 'region1.v2.argotunnel.com' 7844 $script:timeout
        if (-not $err) { Add-Result $item 'PASS' '可连通' '' }
        else {
            $status = if ($SkipTunnel) { 'WARN' } else { 'FAIL' }
            Add-Result $item $status "连不上：$err" '放行出站 TCP 7844（cloudflared 的 http2 连接 Cloudflare 边缘用这个端口，不走 HTTPS_PROXY）'
        }
    }

    Invoke-Check '时钟偏差' {
        if (-not $script:dateHeader) {
            $r = Invoke-HttpProbe 'https://api.cloudflare.com/' $script:timeout $proxy -Method HEAD
            $script:dateHeader = $r.Date
        }
        $skew = Get-ClockSkew $script:dateHeader ([DateTime]::UtcNow)
        if ($null -eq $skew) { Add-Result '时钟偏差' 'WARN' '无法获取网络时间（上面的 HTTPS 都不通）' '确认系统时间正确：w32tm /query /status' ; return }
        $dir = if ($skew -ge 0) { '快' } else { '慢' }
        if ([Math]::Abs($skew) -gt 60) {
            Add-Result '时钟偏差' 'WARN' ("本机比网络时间{0} {1:N0} 秒" -f $dir, [Math]::Abs($skew)) '执行 w32tm /resync /force，并在「日期和时间」里打开自动同步；偏差超过 300 秒时自动部署的 webhook 会被拒绝，登录令牌也会出错'
        } else {
            Add-Result '时钟偏差' 'PASS' ("{0:N0} 秒" -f $skew) ''
        }
    }
}

# ---- console / security hints ---------------------------------------------------------------------------------

Invoke-Check '控制台代码页' {
    $cp = 0
    try { $cp = [Console]::OutputEncoding.CodePage } catch { $cp = 0 }
    if ($cp -eq 0) { Add-Result '控制台代码页' 'WARN' '无法读取（没有控制台）' '' ; return }
    if (@(65001, 936, 54936) -contains $cp) { Add-Result '控制台代码页' 'PASS' "$cp" '' }
    else { Add-Result '控制台代码页' 'WARN' "$cp（中文提示可能显示为乱码，不影响安装）" '执行 chcp 65001 后再运行，或在「区域 → 管理 → 更改系统区域设置」里选中文' }
}

Invoke-Check 'Defender' {
    if (-not (Get-Command Get-MpComputerStatus -ErrorAction SilentlyContinue)) { Add-Result 'Defender' 'PASS' '未检测到 Windows Defender' ''; return }
    $st = $null
    try { $st = Get-MpComputerStatus -ErrorAction Stop } catch { $st = $null }
    if ($null -eq $st -or -not $st.RealTimeProtectionEnabled) { Add-Result 'Defender' 'PASS' '实时保护未开启' ''; return }
    $excl = @()
    try { $excl = @((Get-MpPreference -ErrorAction Stop).ExclusionPath | Where-Object { $_ }) } catch { $excl = @() }
    $covered = @($excl | Where-Object { $InstallDir.StartsWith($_.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
    if ($covered) { Add-Result 'Defender' 'PASS' '实时保护开启，安装目录已排除' '' }
    else { Add-Result 'Defender' 'WARN' '实时保护开启，安装目录未排除（提示，可忽略）' "可选：Add-MpPreference -ExclusionPath '$InstallDir','C:\mysql-data'（减少 MySQL/Java 的磁盘开销，避免误拦 cloudflared）" }
}

Invoke-Check 'AppLocker / 执行策略' {
    $item = 'AppLocker / 执行策略'
    if ($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage') {
        Add-Result $item 'FAIL' "PowerShell 处于 $($ExecutionContext.SessionState.LanguageMode)（AppLocker/WDAC 强制）" '让管理员为 C:\couple 和安装包目录放行脚本，或在不受限的主机上安装'
        return
    }
    $notes = @()
    foreach ($scope in 'MachinePolicy', 'UserPolicy') {
        $p = [string](Get-ExecutionPolicy -Scope $scope)
        if (@('AllSigned', 'RemoteSigned', 'Restricted') -contains $p) { $notes += "组策略 $scope=$p（-ExecutionPolicy Bypass 不生效）" }
    }
    if (Get-Command Get-AppLockerPolicy -ErrorAction SilentlyContinue) {
        try {
            [xml]$x = Get-AppLockerPolicy -Effective -Xml -ErrorAction Stop
            foreach ($rc in @($x.AppLockerPolicy.RuleCollection)) {
                if ($null -eq $rc) { continue }
                $rules = @($rc.ChildNodes | Where-Object { $_.NodeType -eq 'Element' }).Count
                if ($rc.EnforcementMode -eq 'Enabled' -and $rules -gt 0 -and @('Exe', 'Script', 'Dll') -contains $rc.Type) { $notes += "AppLocker $($rc.Type) 规则 $rules 条（强制）" }
            }
        } catch { Write-Verbose "AppLocker: $($_.Exception.Message)" }
    }
    if ($notes.Count -gt 0) { Add-Result $item 'WARN' ($notes -join '；') "可能阻止 $InstallDir 下的 java.exe / cloudflared.exe / WinSW 或本脚本；让管理员放行这些路径" }
    else { Add-Result $item 'PASS' '无限制' '' }
}

# ---- report ---------------------------------------------------------------------------------------------------

$all = $script:results.ToArray()
Write-Host ''
$lines = Format-DoctorTable $all @('结果', '检查项', '详情', '修复建议')
Write-Host $lines[0]
Write-Host $lines[1]
for ($i = 0; $i -lt $all.Count; $i++) {
    $color = switch ($all[$i].Status) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } default { 'Red' } }
    Write-Host $lines[$i + 2] -ForegroundColor $color
}
$nPass = @($all | Where-Object { $_.Status -eq 'PASS' }).Count
$nWarn = @($all | Where-Object { $_.Status -eq 'WARN' }).Count
$nFail = @($all | Where-Object { $_.Status -eq 'FAIL' }).Count
Write-Host ''
$code = Get-DoctorExitCode $all
if ($code -eq 0) { Write-Host "结果：PASS $nPass，WARN $nWarn，FAIL 0 —— 可以安装（WARN 是提示，不影响安装）" -ForegroundColor Green }
else { Write-Host "结果：PASS $nPass，WARN $nWarn，FAIL $nFail —— 先按「修复建议」处理 FAIL 项，再运行" -ForegroundColor Red }
exit $code
