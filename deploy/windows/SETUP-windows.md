# 服务器从零配置（Windows 原生方案，主推）

适用：一台**全新的云 Windows 主机**——不能虚拟化（跑不了 Docker / WSL2 / Hyper-V）、**没有公网 IPv4/IPv6**，唯一入口是 **Cloudflare Tunnel（只出站）**。
服务器上跑 3 个 Windows 服务（WinSW 包装）+ 1 个 MySQL：

| 服务 | 作用 | 监听 |
|---|---|---|
| `couple-backend` | Ktor 后端 + 内置 MQTT broker（Moquette）+ 管理后台网页 | `PORT`（默认 8080）、`MQTT_WS_PORT`（默认 127.0.0.1:8083） |
| `couple-updater` | 推送即部署 webhook + 轮询 GitHub Release，失败自动回滚 | 127.0.0.1:9000 |
| `couple-cloudflared` | Cloudflare Tunnel（本地管理，路由写在 `config.yml`） | 127.0.0.1:20241（metrics） |
| `MySQL84` | MySQL 8.4 LTS（`setup.ps1` 自动安装；已有 MySQL 则直接复用） | 127.0.0.1:3306 |

> Linux + Docker 备选方案见 [`../SETUP.md`](../SETUP.md)；自动更新/发版原理见 [`../README.md`](../README.md)。
> 约定：域名用 `couple.example.com`，安装目录用默认的 `C:\couple`，换成你自己的。
> 标注 **（待实机确认）** 的内容是从代码/文档推断、还没在真机上验证过的。

## 快速安装（推荐）：一条命令

整个过程只需要回答一个问题（域名），外加在浏览器里点一次 Cloudflare 授权。

### 0. 前提

- [ ] **管理员 PowerShell**：开始菜单右键 *Windows PowerShell* →「以管理员身份运行」。自带的 Windows PowerShell 5.1 即可（PowerShell 7 也行）。
- [ ] **Windows Server 2016+ / Windows 10+，x64**，内存 ≥ 2 GB。
- [ ] **域名已接入 Cloudflare**：在 Cloudflare 上状态为 **Active**（NS 已切过去）。要用的主机名（如 `couple.example.com`）**不需要**提前建 DNS 记录；已有 A/AAAA/CNAME 记录时脚本会问你要不要覆盖。
- [ ] 服务器能访问外网（GitHub、cdn.mysql.com、Cloudflare，以及隧道用的出站 TCP 7844）。不需要任何入站端口。国内网络拉 GitHub 慢：见附录第 4 节 `HTTPS_PROXY`。

### 1. 下载安装包并解压

在管理员 PowerShell 里整段粘贴（下载滚动预发布 `edge` 上最新的 `couple-server-windows-x64-<版本>.zip` 并校验 sha256）：

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
$rel = Invoke-RestMethod https://api.github.com/repos/slss15/couple-/releases/tags/edge -Headers @{ 'User-Agent' = 'couple-setup' }
$zip = $rel.assets | Where-Object { $_.name -match '^couple-server-windows-x64-.+\.zip$' } | Select-Object -First 1
$sum = $rel.assets | Where-Object { $_.name -eq "$($zip.name).sha256" } | Select-Object -First 1
New-Item -ItemType Directory -Force C:\couple-install | Out-Null; Set-Location C:\couple-install
Invoke-WebRequest $zip.browser_download_url -OutFile $zip.name -UseBasicParsing
Invoke-WebRequest $sum.browser_download_url -OutFile "$($zip.name).sha256" -UseBasicParsing
$want = ((Get-Content "$($zip.name).sha256" -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
if ((Get-FileHash -Algorithm SHA256 $zip.name).Hash.ToLowerInvariant() -ne $want) { throw 'sha256 不一致' } else { 'sha256 OK' }
Expand-Archive $zip.name -DestinationPath . -Force
Set-Location (Join-Path C:\couple-install ($zip.name -replace '\.zip$', ''))
Get-ChildItem -Recurse | Unblock-File
```

也可以用浏览器从 <https://github.com/slss15/couple-/releases> 下载 zip，右键“全部解压缩”，再在 PowerShell 里 `cd` 进解压出来的目录。正式版、代理下载等见附录第 3 节。

### 2. 运行 `setup.ps1`

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

它只问一句：**服务器域名**（例如 `couple.example.com`）。接着全自动完成下面 7 步，每步都打印中文进度：

1. **准备**：检查管理员权限、建 `C:\couple\…` 目录、生成 `config\couple.env` 和各项密钥（`JWT_SECRET`、`MQTT_HOOK_SECRET`、`DEPLOY_HOOK_SECRET`）。
2. **MySQL**：
   - 没装过 MySQL → 下载固定版本的 **MySQL 8.4 LTS** ZIP（sha256 校验，约 270 MB），缺 VC++ 运行库时自动安装；装到 `C:\mysql`，数据在 `C:\mysql-data`；按内存写 `my.ini`（utf8mb4、只监听 127.0.0.1、`performance_schema=OFF`、缓冲池 ≤2.5 GB→256M / ≤5 GB→512M / 更大→1G）；注册服务 `MySQL84`；root 设随机密码，保存在 `C:\couple\config\mysql-root.txt`（仅管理员可读）。
   - 已经有 MySQL 服务 → 只问**一次** root 密码（用来建库建用户，不保存）。
   - 然后建库 `couple` + 用户 `couple`（随机密码），写进 `couple.env` 的 `DB_PASSWORD`。
3. **JVM 内存**：按内存设置 `JAVA_OPTS` 的 `-Xmx`（≤2.5 GB→384m / ≤5 GB→640m / 更大→1024m）；你改过 `JAVA_OPTS` 就不动它。
4. **首次设置令牌**：生成 `SETUP_TOKEN`。
5. **Cloudflare Tunnel**：下载固定版本的 cloudflared；第一次会打印一个**授权链接**（有浏览器时自动打开）——在任意电脑上打开、登录 Cloudflare、**选中这个域名**、点 *Authorize*，脚本自动继续；然后创建（或复用）名为 `couple` 的隧道，把域名的 DNS 指向隧道（CNAME，橙云）。
6. **安装服务**：调用 `install.ps1` 注册并启动 `couple-backend` / `couple-updater` / `couple-cloudflared`，等本机健康检查通过，再经 Cloudflare 检查 `https://<域名>/api/v1/health`（最多 60 秒）。
7. **完成**：打印一个结果框，并保存到 `C:\couple\SETUP-RESULT.txt`（仅管理员可读，含密钥）：
   ```text
   ① 管理后台首次设置：https://couple.example.com/setup?token=<SETUP_TOKEN>
   ② GitHub Secrets：DEPLOY_HOOK_URL = https://couple.example.com
                     DEPLOY_HOOK_SECRET = <64 位十六进制>
   ③ Cloudflare 控制台关闭 Bot Fight Mode
   ```

- **可以重复运行**：每一步都会先检查现状，已完成的保留（已有的密钥、密码、隧道、DNS、`my.ini` 都不会被覆盖）。中途失败时会打印**失败的步骤**和**日志路径**（`C:\couple\logs\setup-<时间>.log`），修好原因后直接再运行一次即可。
- 换域名：重新运行并输入新域名，脚本会改 DNS 并重新生成隧道配置（旧的存为 `config.yml.bak`）。
- 常用参数：

  | 参数 | 说明 |
  |---|---|
  | `-Hostname couple.example.com` | 不再询问域名（非交互） |
  | `-MysqlRootPassword (Read-Host -AsSecureString)` | 已有 MySQL 时提前给 root 密码 |
  | `-OverwriteDns` | 域名已有其他 DNS 记录时直接覆盖，不再询问 |
  | `-SkipMysql` | 不碰 MySQL（`couple.env` 里的 `DB_*` 自己填好） |
  | `-SkipTunnel` | 不配 Cloudflare Tunnel（只本机可访问） |
  | `-InstallDir` / `-MysqlDir` / `-MysqlDataDir` / `-TunnelName` | 默认 `C:\couple` / `C:\mysql` / `C:\mysql-data` / `couple` |

### 3. 打开输出的链接，设置管理员

浏览器打开结果框 ① 里的 `https://<域名>/setup?token=…`，按页面提示创建管理员账号。之后用这个账号登录 `https://<域名>/` 管理后台。
链接忘了：在服务器上 `notepad C:\couple\SETUP-RESULT.txt`（管理员 PowerShell 里打开）。

### 4. 把两个值填进 GitHub Secrets，并关闭 Bot Fight Mode

- [ ] GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret**，照结果框 ② 新建两个：
  - `DEPLOY_HOOK_URL` = `https://<域名>`（**只填域名**，部署脚本自己会加 `/_deploy/hook`）
  - `DEPLOY_HOOK_SECRET` = 结果框里的那串
- [ ] **Cloudflare 控制台 → Security → Bots → Bot Fight Mode：关闭（必须）**：<https://dash.cloudflare.com/?to=/:account/:zone/security/bots>。否则 GitHub Actions 的部署请求和 App 的连接会被质询拦截。建议同时看一眼附录第 1.1 节的其他安全设置。
- 发版用的 APK 签名 Secrets 见附录第 9 节。

### 5. 手机扫码

管理后台 → **服务器配置**：后端地址 `https://<域名>`、MQTT 地址 `wss://<域名>/mqtt`（TURN、存储按需）→ **引导二维码** → 生成 → 两台手机装好 APK 后扫码、注册、配对。逐项说明见附录第 8 节。

完成。之后每次推 `main` 服务器会自动更新（附录第 10 节）。

---

### 6. 自动体检、看门狗与防降级（了解即可）

- **安装前体检 `doctor.ps1`**：`setup.ps1` 会先运行它，逐项列出 PASS / WARN / FAIL 和修复提示（管理员权限、PowerShell/系统版本、内存与 C 盘空间、端口占用、代理、到 GitHub / MySQL / Cloudflare 的连通性、时钟偏差、控制台代码页、Defender/AppLocker）。有 FAIL 时停止；确认无碍可加 `-IgnoreDoctor`。单独运行：`powershell -ExecutionPolicy Bypass -File .\doctor.ps1`。
- **看门狗**：计划任务 `\Couple\couple-watchdog`（SYSTEM，每 2 分钟 + 开机）。服务停了就拉起；后端健康检查连续 2 次失败就重启后端；隧道连接数为 0 或经 Cloudflare 的公网健康检查连续 2 次失败就重启 `couple-cloudflared`；C 盘剩余 < 2 GB 告警。日志 `C:\couple\logs\watchdog.log`（1 MB 轮转，留 5 份）。暂停：新建空文件 `C:\couple\config\watchdog.pause`，或在 `couple.env` 写 `WATCHDOG_ENABLED=false`。
- **防降级**：自动更新不会装比当前旧的版本；`install.ps1` 遇到更旧的安装包会拒绝，确需回退加 `-AllowDowngrade`（`setup.ps1` 也支持透传）。固定频道 `UPDATE_CHANNEL=<版本号>` 或签名 webhook 里的 `allowDowngrade:true` 视为有意回退。
- **只监听本机**：后端默认 `BIND_HOST=127.0.0.1`，外部只能经 Cloudflare Tunnel 访问。
- **权限**：`C:\couple` 只允许 Administrators 和 SYSTEM 修改。
- **私有仓库**：在 `couple.env` 设置 `UPDATE_GITHUB_TOKEN`（只读 contents 的 fine-grained token），updater 就能下载私有仓库的发布包。

# 附录：手动安装 / 排障

下面是 `setup.ps1` 背后每一步的手动做法、每个配置项的说明和日常运维。用 `setup.ps1` 装好的服务器一般只需要看第 6（验证）、10（运维）、11（排障）节。

**手动安装的执行顺序**：0 → 3（先把安装包下载解压，第 1 节要用里面的 `install.ps1 -CloudflaredOnly`）→ 1 → 2 → 4 → 5 → 6 → 7 → 8 → 9。第 10、11 节是日常运维与排查。

---

## 0. 前置条件

- [ ] **管理员 PowerShell**：开始菜单右键 *Windows PowerShell* →「以管理员身份运行」。自带的 Windows PowerShell 5.1 即可（PowerShell 7 也行）。确认：
  ```powershell
  ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)   # True
  $PSVersionTable.PSVersion
  ```
- [ ] **系统版本**：Windows Server 2016 / 2019 / 2022 / 2025 或 Windows 10/11，**x64**。WinSW 用的是 .NET Framework 4.6.1 版，2016+ 自带 .NET 4.7+。
  ```powershell
  Get-ComputerInfo -Property WindowsProductName, OsVersion, OsArchitecture
  [Environment]::Is64BitOperatingSystem   # True
  ```
- [ ] **出站网络**：能访问 `github.com`、`api.github.com`、`objects.githubusercontent.com`（下载安装包/APK）、`cloudflare.com`，以及 Cloudflare 隧道边缘的 **TCP 7844**。不需要任何入站端口，云厂商安全组**不要**放行 8080/8083/9000/3306。
  ```powershell
  Test-NetConnection github.com -Port 443
  Test-NetConnection region1.v2.argotunnel.com -Port 7844     # TcpTestSucceeded : True
  ```
  国内网络拉 GitHub 慢：准备一个 HTTP 代理，第 4 节填 `HTTPS_PROXY`。
- [ ] **系统时间准确**（webhook 签名允许的误差 300 秒）：`w32tm /resync`；`Get-Date` 核对。可选设成北京时间：`Set-TimeZone -Id 'China Standard Time'`（`UPDATE_WINDOW` 按本机时区）。
- [ ] **内存规划**（Windows 本身约占 1 GB）：

  | 主机内存 | JVM 堆（`couple.env` → `JAVA_OPTS`） | MySQL（`my.ini` → `innodb_buffer_pool_size`） |
  |---|---|---|
  | 2 GB | `-Xmx384m` | `256M` |
  | 4 GB | `-Xmx512m` ～ `-Xmx768m` | `512M` |

  两种都要在 `my.ini` 里写 `performance_schema=OFF`（省 200～400 MB）。
  - JVM 堆：改 `C:\couple\config\couple.env` 的 `JAVA_OPTS` 里的 `-Xmx`，**下次部署或重跑 `install.ps1` 时生效**（立即生效见第 10.6 节）。updater 自己固定 `-Xmx96m`，cloudflared 约 30～50 MB。
  - MySQL：改 `my.ini`（第 2 节），然后 `Restart-Service MySQL84`。
- [ ] 一个托管在 Cloudflare 的域名（NS 已切到 Cloudflare）。

## 1. Cloudflare

### 1.1 域名接入与安全设置

- [ ] 域名已在 Cloudflare 上状态为 **Active**。隧道不需要 SSL 证书、不需要 A/AAAA 记录（`route dns` 会自动建 CNAME）。如果该主机名已有 A/AAAA/CNAME 记录，先删掉。
- [ ] **Security → Bots → Bot Fight Mode：关闭（必须）**。免费版的 Bot Fight Mode **无法用 WAF 自定义规则跳过**，它会挑战 GitHub Actions 调 webhook 的 curl，也会挑战 App 的非浏览器客户端（OkHttp、Paho WebSocket）。
- [ ] **Security → Settings**：关闭 *Browser Integrity Check*（或用 Configuration Rule 只对 `/api/`、`/mqtt`、`/_deploy/` 关闭）；如有 WAF 托管质询规则，加一条 `URI Path starts with /_deploy/ or /mqtt or /api/` → **Skip**。
- [ ] **Network → WebSockets：开启**（默认开，MQTT 走 WSS）。

### 1.2 下载 cloudflared（版本固定、sha256 校验）

需要先完成第 3 节（下载并解压安装包），然后在安装包目录里：

```powershell
cd C:\couple-install\couple-server-windows-x64-<版本>
powershell -ExecutionPolicy Bypass -File .\install.ps1 -CloudflaredOnly
# 输出：cloudflared 2026.10.3 ready: C:\couple\cloudflared\cloudflared.exe
```

版本与 sha256 固定在 [`versions.env`](versions.env)（`CLOUDFLARED_VERSION`、`CLOUDFLARED_WINDOWS_AMD64_SHA256`），只下载 `cloudflared-windows-amd64.exe`，校验不通过就报错。
如果要走代理：`-CloudflaredOnly` 会读取 `C:\couple\config\couple.env` 里的 `HTTPS_PROXY`，可以先做第 4 节第一步（复制模板并填好 `HTTPS_PROXY`）再执行。

### 1.3 登录、创建隧道、绑定域名

```powershell
$cf = 'C:\couple\cloudflared\cloudflared.exe'
& $cf tunnel login                                   # 打印一个链接：在任意电脑的浏览器打开，选中你的域名授权
& $cf tunnel create couple                           # 输出隧道 UUID，生成 %USERPROFILE%\.cloudflared\<UUID>.json
& $cf tunnel route dns couple couple.example.com     # 建 CNAME couple.example.com → <UUID>.cfargotunnel.com（橙云）
& $cf tunnel list                                    # 记下 ID 列
```

- `login` 生成 `%USERPROFILE%\.cloudflared\cert.pem`（能管理这个域名下的隧道，**当作密钥保管**，见第 10.7 节备份）。
- 主机名已有记录导致 `route dns` 失败：先在 DNS 面板删掉，或加 `--overwrite-dns`。
- [ ] 记下三样东西，第 5 节要用：
  - `-TunnelId` = 隧道 UUID（36 位，如 `6f1c…-…`）
  - `-TunnelHostname` = `couple.example.com`
  - `-TunnelJsonFile` = `$env:USERPROFILE\.cloudflared\<UUID>.json`

第 5 节执行 `install.ps1 -TunnelId … -TunnelHostname … -TunnelJsonFile …` 后，脚本会：把凭证复制到 `C:\couple\config\cloudflared\<UUID>.json`（收紧权限）、从 [`cloudflared/config.yml.in`](cloudflared/config.yml.in) 渲染 `C:\couple\config\cloudflared\config.yml`、跑 `cloudflared tunnel ingress validate`、注册 `couple-cloudflared` 服务。

### 1.4 路由（写在 `config.yml` 里，自上而下第一条命中）

| 路径 | 去向 | 说明 |
|---|---|---|
| `/api/v1/internal/*` | 404 | 内部钩子绝不对外 |
| `/_deploy/hook`、`/_deploy/status` | 127.0.0.1:9000（couple-updater） | GitHub Actions 推送即部署（HMAC 签名） |
| 其他 `/_deploy/*` | 404 | |
| `/mqtt*` | 127.0.0.1:8083（内置 broker） | App 填 `wss://couple.example.com/mqtt` |
| 其余 | 127.0.0.1:8080（couple-backend） | REST API + 管理后台网页（后端直接托管） |

`protocol: http2`（QUIC/UDP 在 NAT4、国内运营商常被限速/阻断）、`no-autoupdate: true`。**注意**：`config.yml` 里的 8080/8083 是写死的，改了 `PORT` / `MQTT_WS_PORT` 要同步手改 `C:\couple\config\cloudflared\config.yml` 并 `Restart-Service couple-cloudflared`。

## 2. MySQL 8.4（LTS）

两种装法任选其一。推荐 **ZIP 方式**（目录、服务名、配置文件都可控）。

### 2.1 ZIP 方式（推荐）

- [ ] 安装 VC++ 运行库（MySQL 8.4 Windows 版依赖它）（待实机确认：部分云镜像已自带）：
  ```powershell
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest https://aka.ms/vs/17/release/vc_redist.x64.exe -OutFile $env:TEMP\vc_redist.x64.exe -UseBasicParsing
  Start-Process $env:TEMP\vc_redist.x64.exe -ArgumentList '/install','/quiet','/norestart' -Wait
  ```
- [ ] 到 <https://dev.mysql.com/downloads/mysql/> 选 **8.4.x LTS** → *Microsoft Windows* → **ZIP Archive**（`mysql-8.4.x-winx64.zip`，不要 debug/test 包），核对页面上的 MD5。解压，把里面的 `mysql-8.4.x-winx64` 目录改名放到 `C:\mysql`（即存在 `C:\mysql\bin\mysqld.exe`）。
- [ ] 写 `C:\mysql\my.ini`（ASCII 编码；`datadir` 放在程序目录外，升级 MySQL 时不受影响）：
  ```powershell
  @'
  [mysqld]
  basedir=C:/mysql
  datadir=C:/mysql-data
  port=3306
  bind-address=127.0.0.1
  mysqlx=OFF
  character-set-server=utf8mb4
  collation-server=utf8mb4_0900_ai_ci
  # 2 GB host: 256M, 4 GB host: 512M
  innodb_buffer_pool_size=256M
  performance_schema=OFF
  max_connections=50
  log-error=C:/mysql-data/mysqld.err

  [client]
  port=3306
  default-character-set=utf8mb4
  '@ | Set-Content -Path C:\mysql\my.ini -Encoding ASCII
  ```
- [ ] 初始化（root 先无密码）、注册服务、启动：
  ```powershell
  & C:\mysql\bin\mysqld.exe --defaults-file=C:\mysql\my.ini --initialize-insecure --console   # C:\mysql-data 必须不存在或为空
  & C:\mysql\bin\mysqld.exe --install MySQL84 --defaults-file=C:\mysql\my.ini
  Set-Service MySQL84 -StartupType Automatic
  Start-Service MySQL84
  Get-Service MySQL84                                                                            # Running
  ```
- [ ] 设置 root 密码（自己生成一个，记进密码管理器）：
  ```powershell
  & C:\mysql\bin\mysql.exe -uroot --skip-password -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '<ROOT密码>';"
  ```
- 可选：把 `C:\mysql\bin` 加进 PATH：`[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path','Machine') + ';C:\mysql\bin', 'Machine')`（新开 PowerShell 生效）。

### 2.2 MSI 方式（备选）

MySQL Installer / `mysql-8.4.x-winx64.msi` → 只装 *MySQL Server* → 配置向导里：Config Type 选 *Server Computer* 或 *Development Computer*，端口 3306，认证方式保持默认的 **caching_sha2_password**（Strong Password Encryption），服务名 `MySQL84`，设置 root 密码。
装完编辑 `C:\ProgramData\MySQL\MySQL Server 8.4\my.ini`（路径待实机确认），在 `[mysqld]` 下按 2.1 的示例改/加 `bind-address=127.0.0.1`、`innodb_buffer_pool_size`、`performance_schema=OFF`、`mysqlx=OFF`、`character-set-server=utf8mb4`，然后 `Restart-Service MySQL84`。命令行客户端在 `C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe`。

### 2.3 建库、建用户

- [ ] 先生成 `DB_PASSWORD`（十六进制，避免特殊字符的转义问题），第 4 节要填同一个值：
  ```powershell
  function New-Secret([int]$Bytes = 32) { $b = New-Object byte[] $Bytes; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') }) }
  $dbPass = New-Secret 24; $dbPass     # 复制下来
  ```
- [ ] 建库建用户（两条都建：JDBC 走 TCP 127.0.0.1，命令行客户端默认走 `localhost`）：
  ```powershell
  @"
  CREATE DATABASE IF NOT EXISTS couple CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  CREATE USER IF NOT EXISTS 'couple'@'127.0.0.1' IDENTIFIED BY '$dbPass';
  CREATE USER IF NOT EXISTS 'couple'@'localhost' IDENTIFIED BY '$dbPass';
  GRANT ALL PRIVILEGES ON couple.* TO 'couple'@'127.0.0.1', 'couple'@'localhost';
  FLUSH PRIVILEGES;
  "@ | & C:\mysql\bin\mysql.exe -uroot -p      # 输入 root 密码
  ```
- [ ] 验证：`& C:\mysql\bin\mysql.exe -h127.0.0.1 -ucouple -p couple -e "SELECT CURRENT_USER(), @@character_set_server, @@innodb_buffer_pool_size/1024/1024 AS pool_mb, @@performance_schema;"`
- 表结构不用手建：后端启动时 Flyway 自动迁移。认证插件保持 8.4 默认的 `caching_sha2_password`（**不要**用 `mysql_native_password`，8.4 默认没加载它）；`DB_URL` 里已有 `allowPublicKeyRetrieval=true`，无 SSL 的本机连接需要它。

## 3. 下载安装包并校验 sha256

安装包：`couple-server-windows-x64-<版本>.zip` + 同名 `.sha256`（内容是 `sha256sum` 格式：`<64位哈希>  <文件名>`）。

| 来源 | 版本名 | 说明 |
|---|---|---|
| 滚动预发布 **`edge`**（Releases 页里标 *Pre-release* 的 `edge (rolling)`） | `edge-<7位commit>` | 每次推 `main`（动了 backend/admin/deploy/windows）都会替换，只保留最新一个 |
| 正式 Release **`vX.Y.Z`** | `X.Y.Z` | 打 tag 发版时附带，与 APK 同一个 Release |

首次部署建议用与 `UPDATE_CHANNEL` 一致的那个（默认 `edge`）：

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
$repo = 'slss15/couple-'
$tag  = 'edge'                          # 正式版：'v0.2.0'；最新正式版：'latest'
$api  = if ($tag -eq 'latest') { "https://api.github.com/repos/$repo/releases/latest" } else { "https://api.github.com/repos/$repo/releases/tags/$tag" }
$rel  = Invoke-RestMethod $api -Headers @{ 'User-Agent' = 'couple-setup' }      # 走代理时加 -Proxy http://127.0.0.1:7890
$zip  = $rel.assets | Where-Object { $_.name -match '^couple-server-windows-x64-.+\.zip$' } | Select-Object -First 1
$sum  = $rel.assets | Where-Object { $_.name -eq "$($zip.name).sha256" } | Select-Object -First 1
New-Item -ItemType Directory -Force C:\couple-install | Out-Null; Set-Location C:\couple-install
Invoke-WebRequest $zip.browser_download_url -OutFile $zip.name -UseBasicParsing
Invoke-WebRequest $sum.browser_download_url -OutFile "$($zip.name).sha256" -UseBasicParsing

$want = ((Get-Content "$($zip.name).sha256" -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
$have = (Get-FileHash -Algorithm SHA256 $zip.name).Hash.ToLowerInvariant()
if ($have -ne $want) { throw "sha256 不一致：$have != $want" } else { "sha256 OK: $have" }

Expand-Archive $zip.name -DestinationPath . -Force
Set-Location (Join-Path C:\couple-install ($zip.name -replace '\.zip$', ''))
Get-ChildItem -Recurse | Unblock-File
Get-Content .\VERSION
```

- [ ] `sha256 OK`，并且当前目录里有 `install.ps1`、`jre\`、`app\`、`admin\`、`updater\`、`winsw\`、`VERSION`。
- 也可以浏览器手动下载：<https://github.com/slss15/couple-/releases>，再用 `Get-FileHash -Algorithm SHA256 <zip>` 与 `.sha256` 文件第一列比对。

## 4. `couple.env`（`C:\couple\config\couple.env`）

先复制模板，再填写（`install.ps1` 发现文件已存在就不会覆盖，只会补齐空的 `JWT_SECRET` / `MQTT_HOOK_SECRET` / `DEPLOY_HOOK_SECRET`）：

```powershell
New-Item -ItemType Directory -Force C:\couple\config | Out-Null
Copy-Item .\config\couple.env.example C:\couple\config\couple.env      # 在安装包目录执行；已存在就别覆盖
notepad C:\couple\config\couple.env                                    # 从管理员 PowerShell 打开（安装后只有管理员/SYSTEM 可读写）
```

格式：每行 `KEY=VALUE`，`#` 开头为注释，值不用引号，不支持变量展开。**文件里的非空值优先于服务配置里的环境变量**。
生成随机串（每个各生成一次，互不相同）：

```powershell
function New-Secret([int]$Bytes = 32) { $b = New-Object byte[] $Bytes; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') }) }
New-Secret 32                                             # 64 位十六进制
```

### 4.1 数据库

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `DB_URL` | ✅ | `jdbc:mysql://127.0.0.1:3306/couple?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | JDBC 地址，模板里已填好 | 一般不改 |
| `DB_USER` | ✅ | `couple` | MySQL 用户 | 第 2.3 节 |
| `DB_PASSWORD` | ✅ | 空 → `setup.ps1` 建库后写入 | 上述用户的密码；为空时 `install.ps1` 会警告 | `setup.ps1` 自动，或第 2.3 节的 `$dbPass` |

### 4.2 后端（`couple-backend`，改后 `Restart-Service couple-backend`）

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `SETUP_TOKEN` | 自动 | 空 → `setup.ps1` 生成 | 首次设置页 `https://<域名>/setup?token=<SETUP_TOKEN>` 的令牌 | 留空让 `setup.ps1` 生成 |
| `JWT_SECRET` | ✅ | 空 → install 自动生成 | HS256 签名密钥，**≥ 32 字符**否则后端拒绝启动；改后所有人重新登录 | 留空让 `install.ps1` 生成 |
| `REGISTRATION_CODE` | 建议 | 空 | 注册邀请码；**留空 = 关闭注册**。首次部署必须填（第 7 节要用），两人都注册完后清空 | `New-Secret 4` 或自定 |
| `MQTT_HOOK_SECRET` | ✅ | 空 → install 自动生成 | updater 向后端上报状态时的 `X-Internal-Token`（Windows 用内置 broker，不涉及 EMQX）；改后**两个服务都要重启** | 留空让 `install.ps1` 生成 |
| `BOOTSTRAP_PRIVATE_KEY` | 可选 | 空 | 引导二维码的 Ed25519 私钥（base64 的 32 字节 seed 或 PKCS#8 DER）。留空 = 首次启动自动生成并存进数据库。**只在首次启动前设置**，之后再改 = 两台手机都要重新信任 | `$b=New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)` |
| `ADMIN_USERNAME` | 可选 | 空 | 指定管理员用户名；留空 = 第一个注册的用户是管理员 | — |
| `AUTH_RATE_LIMIT_PER_MINUTE` | | `30` | 注册/登录/刷新，每 IP 每分钟上限（按 `CF-Connecting-IP`） | — |
| `HTTPS_PROXY` | 可选 | 空 | 出站代理（后端镜像 APK、updater 查询/下载 GitHub、`install.ps1` 下载 cloudflared），如 `http://127.0.0.1:7890`，支持 `http://user:pass@host:port` | — |
| `NO_PROXY` | | `localhost,127.0.0.1` | 不走代理的主机 | — |
| `PORT` | | `8080` | 后端 HTTP 端口；updater 的健康检查也用它。改了要同步改 cloudflared `config.yml` | — |
| `JAVA_OPTS` | | 空 = `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError` | 后端 JVM 参数，写进服务配置；`setup.ps1` 按内存自动填（≤2.5 GB `-Xmx384m`，≤5 GB `-Xmx640m`，更大 `-Xmx1024m`），改成其他值后不再自动改。下次部署/重跑 `install.ps1` 生效（立即生效见 10.6） | — |
| `COUPLE_API_BASE` | | 空 | 管理后台调 API 的地址，留空 = 同域（推荐）。旧名 `ADMIN_API_BASE` 也认 | — |

### 4.3 MQTT（内置 broker）

| 名称 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `MQTT_BROKER_MODE` | | `embedded` | Windows 必须保持 `embedded`（`external` 是 Docker+EMQX 方案用的） |
| `MQTT_WS_PORT` | | `8083` | WebSocket 端口（路径 `/mqtt`），cloudflared 指向它 |
| `MQTT_BIND_HOST` | | `127.0.0.1` | 只给本机 cloudflared 用，保持 127.0.0.1 |
| `MQTT_TCP_PORT` | | 空 | 可选纯 TCP 端口（调试），留空 = 关闭 |
| `MQTT_SESSION_EXPIRY` | | `7d` | 持久会话多久不上线就清除 |
| `MQTT_DATA_DIR` | | 服务配置里设为 `C:\couple\data\mqtt` | **不要**在 `couple.env` 里设置 |

### 4.4 自动更新（`couple-updater`，改后 `Restart-Service couple-updater`）

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `DEPLOY_HOOK_SECRET` | 推送即部署 ✅ | 空 → install 自动生成 | webhook 的 HMAC 密钥，**必须与 GitHub Secret `DEPLOY_HOOK_SECRET` 相同**（第 9 节）；留空 = 关闭 webhook，只轮询 | 留空让 `install.ps1` 生成 |
| `UPDATE_ENABLED` | | `true` | `false` = 停止一切自动部署（webhook 返回 503，轮询不执行） | — |
| `UPDATE_CHANNEL` | | `edge` | `edge` = 每次推 main 都部署；`stable` = 只跟最新正式版；`0.2.0` = 固定到 tag `v0.2.0` | — |
| `UPDATE_INTERVAL` | | `300` | 轮询间隔秒数（最少 30） | — |
| `UPDATE_WINDOW` | | 空 | 轮询时段（本机时区），如 `03:00-05:00`，可跨午夜；webhook 不受限 | — |
| `HEALTH_TIMEOUT` | | `90` | 新版本健康检查超时秒数，超时即回滚 | — |
| `UPDATE_REPO` | | `slss15/couple-` | 允许下载安装包的 GitHub 仓库（白名单） | — |
| `UPDATE_GITHUB_TOKEN` | 可选 | 空 | 只读 GitHub token，用于查询 Release API（限流时）。注意：目前**下载 zip 时不带 token**，私有仓库的推送即部署走不通（待实机确认） | — |
| `HOOK_BIND` / `HOOK_PORT` | | `127.0.0.1` / `9000` | webhook 监听地址，改端口要同步改 `config.yml`；一般不改 | — |
| `HOOK_MAX_SKEW` | | `300` | 签名时间戳允许误差（秒） | — |
| `KEEP_RELEASES` | | `3`（最少 2） | `C:\couple\releases` 下保留几个版本（当前和上一个总是保留） | — |
| `HEALTH_URL` / `STATUS_POST_URL` | | 由 `PORT` 推出 | 高级用法，一般不设 | — |

不要在 `couple.env` 里设置（服务配置已设好，文件里的非空值会覆盖它们）：`COUPLE_HOME`、`COUPLE_ENV_FILE`、`ADMIN_STATIC_DIR`、`RELEASES_DIR`（= `C:\couple\data\releases`，APK 镜像）、`MQTT_DATA_DIR`、`APP_VERSION`。

- [ ] 至少填好：`DB_PASSWORD`、`REGISTRATION_CODE`、`JAVA_OPTS` 的 `-Xmx`（按内存），需要代理时填 `HTTPS_PROXY`。其余保持默认。

## 5. 运行 `install.ps1`

在第 3 节解压出来的安装包目录里，**一条命令装好全部服务**（带上第 1.3 节记下的隧道参数）：

```powershell
cd C:\couple-install\couple-server-windows-x64-<版本>
powershell -ExecutionPolicy Bypass -File .\install.ps1 `
  -TunnelId <UUID> `
  -TunnelHostname couple.example.com `
  -TunnelJsonFile "$env:USERPROFILE\.cloudflared\<UUID>.json"
```

参数：

| 参数 | 说明 |
|---|---|
| `-InstallDir` | 安装目录，默认 `C:\couple` |
| `-TunnelId` / `-TunnelHostname` / `-TunnelJsonFile` | 三个必须同时给；给了才会安装/更新 `couple-cloudflared` |
| `-ForceTunnelConfig` | 重新渲染 `config\cloudflared\config.yml`（旧的存为 `config.yml.bak`）；默认已存在就保留 |
| `-NoStart` | 只注册服务不启动 |
| `-CloudflaredOnly` | 只下载 cloudflared 后退出（第 1.2 节） |

脚本会依次（可重复执行，升级也是它）：建目录 `C:\couple\{config,data\releases,data\mqtt,data\updater,logs,releases,services,updater,cloudflared}` → 补齐 `couple.env` 的三个密钥并把权限收紧为仅 Administrators/SYSTEM → 复制本版本到 `releases\<版本>`、渲染 `services\couple-backend.xml`、写 `releases\CURRENT` → 刷新 `updater\jre`、`updater\lib` → 注册 `couple-backend`、`couple-updater`、`couple-cloudflared` → 启动并等待 `http://127.0.0.1:<PORT>/api/v1/health`（最多 120 秒）。

- [ ] 末尾显示 `backend healthy: {...}`（绿色）。如果是黄色 `backend not healthy yet`，看第 11 节。
- [ ] 末尾提示把 `DEPLOY_HOOK_SECRET` 复制到 GitHub（第 9 节）。
- [ ] 安装成功后可以删掉 `%USERPROFILE%\.cloudflared\<UUID>.json`（已复制进 `C:\couple\config\cloudflared\`），但先按第 10.7 节备份。
- 可选加固：后端 HTTP 监听在 `0.0.0.0:8080`，虽然没有公网 IP、安全组也没放行，仍建议在 Windows 防火墙显式拦截：
  ```powershell
  New-NetFirewallRule -DisplayName 'couple: block inbound 8080/8083/9000' -Direction Inbound -Protocol TCP -LocalPort 8080,8083,9000 -Action Block
  ```

## 6. 验证

```powershell
Get-Service couple-*, MySQL84 | Format-Table Name, Status, StartType
# 期望：couple-backend / couple-updater / couple-cloudflared / MySQL84 都是 Running

Invoke-RestMethod http://127.0.0.1:8080/api/v1/health          # {status: ok, version: <版本>, protocolVersion: 1}
Invoke-RestMethod http://127.0.0.1:9000/_deploy/status         # updater 状态：target windows、channel、current.server
(Invoke-WebRequest http://127.0.0.1:20241/ready -UseBasicParsing).StatusCode   # 200 = 隧道已连上边缘
Test-NetConnection 127.0.0.1 -Port 8083                        # MQTT WebSocket 端口

# 经过 Cloudflare（也可以在自己电脑上执行）
Invoke-RestMethod https://couple.example.com/api/v1/health
Invoke-RestMethod https://couple.example.com/_deploy/status
try { Invoke-WebRequest https://couple.example.com/api/v1/internal/mqtt/auth -UseBasicParsing } catch { $_.Exception.Response.StatusCode.value__ }   # 必须是 404
```

- [ ] 浏览器打开 `https://couple.example.com/` 能看到管理后台登录页。
- **日志**（`C:\couple\logs\`，WinSW 按大小滚动）：

  | 文件 | 内容 |
  |---|---|
  | `couple-backend.out.log` / `.err.log` | 后端日志（Logback 输出到 stdout） |
  | `couple-backend.wrapper.log` | WinSW 启停记录 |
  | `couple-updater.out.log` / `.err.log` | updater（`updater: start channel=…`、`hook accepted`、`rolling back` 等） |
  | `couple-cloudflared.err.log` | cloudflared（`Registered tunnel connection` = 已连上） |
  | `C:\mysql-data\mysqld.err` | MySQL 错误日志（2.1 的 `log-error`） |

  ```powershell
  Get-Content C:\couple\logs\couple-backend.out.log -Tail 100 -Wait     # Ctrl+C 退出
  Get-Content C:\couple\data\updater\updater.json                       # updater 完整状态（含坏版本列表）
  Get-Content C:\couple\releases\CURRENT                                # 当前运行版本
  ```
- [ ] 若 `BOOTSTRAP_PRIVATE_KEY` 留空：`Select-String -Path C:\couple\logs\couple-backend.out.log -Pattern 'Generated a new bootstrap'` 会看到警告——私钥已存进数据库，第 10.7 节的 MySQL 备份包含它。

## 7. 创建首个管理员

> 用 `setup.ps1` 安装时，直接打开它输出的 `https://<域名>/setup?token=<SETUP_TOKEN>` 创建管理员即可（快速安装第 3 步），不需要本节。下面是不用首次设置页时的 API 做法。

管理后台只有登录页、没有注册入口；App 要扫管理员生成的二维码才能连服务器——所以第一个账号用 API 注册。没设 `ADMIN_USERNAME` 时**第一个注册的用户自动成为管理员**，请自己先注册。需要 `REGISTRATION_CODE` 非空（第 4 节）。

```powershell
$body = @{
  username    = 'me'                     # 3–32 位 [A-Za-z0-9_.-]
  password    = '一个强密码'              # 8–128 字符
  displayName = '我'                      # 1–64 字符
  inviteCode  = '<REGISTRATION_CODE>'
  deviceId    = 'setup-cli'
  deviceName  = 'powershell'
} | ConvertTo-Json
# PowerShell 5.1 默认不按 UTF-8 发送字符串，所以传字节
$r = Invoke-RestMethod -Method Post -Uri https://couple.example.com/api/v1/auth/register `
       -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$r.user
```

- [ ] 返回里有 `user.id` 即成功（返回体是 TokenPair：`accessToken`、`refreshToken`、`accessExpiresAt`、`user`）。之后在手机上用同一账号密码登录。
- 也可以在服务器本机把地址换成 `http://127.0.0.1:8080/api/v1/auth/register`。
- 常见错误：`INVITE_CODE_INVALID` / `REGISTRATION_DISABLED`（`REGISTRATION_CODE` 不对或为空，改后要 `Restart-Service couple-backend`）、`VALIDATION_ERROR`（用户名/密码长度）、`RATE_LIMITED`。
- [ ] 打开 `https://couple.example.com/`，用该账号登录管理后台，左侧菜单应有：仪表盘 / 服务器配置 / 版本发布 / 引导二维码 / 审计日志。

## 8. 后台配置 → 引导二维码 → 两台手机扫码配对

后台各字段的含义与 Linux 方案相同，详见 [`../SETUP.md` 第 5、6 节](../SETUP.md#5-后台服务器配置逐项填写)。Windows 上的要点：

1. [ ] **服务器配置 → 地址**：后端地址 `baseUrl` = `https://couple.example.com`（**不带** `/api/v1`）；MQTT 地址 `mqttUrl` = `wss://couple.example.com/mqtt`。Release 版 App 只接受 `https://` / `wss://`。
2. [ ] **TURN**（语音/视频）：推荐 Cloudflare Realtime TURN（提供方 `cloudflare`，填 TURN Key ID + API Token）。这台机器没有公网地址，**不能**在本机自建 coturn。
3. [ ] **存储**：按 SETUP.md 5.3 选择；没有公网入站时推荐 Cloudflare R2。选 `local` 时文件在本机的具体目录（待实机确认）。
4. [ ] **版本发布设置**：仓库 `slss15/couple-`；`autoPublish` 建议先关；签名证书固定 `expectedSigningCertSha256` 可留空（首次镜像自动固定）。APK 镜像存放在 `C:\couple\data\releases`。保存后到「版本发布」页点“立即同步”确认无报错（还没发过版时列表为空是正常的）。国内网络访问 GitHub 失败 → 设 `HTTPS_PROXY` 并重启后端。
5. [ ] 每次改地址都会让 `configVersion` +1，**改完要重新生成二维码**。
6. [ ] **引导二维码** → 生成，核对下方解出的 `baseUrl`、`mqttUrl`。
7. [ ] 两台手机安装 APK（GitHub Release 的 `couple-X.Y.Z.apk`）→ 打开 → 扫码（App 先取公钥 TOFU、校验签名、做 30 秒健康检查，通过才保存）。两人可在「我的 → 服务器配置」里核对**公钥指纹**一致。
8. [ ] 管理员用第 7 节的账号登录；另一半在 App 里注册（用户名 + 密码 + 邀请码 `REGISTRATION_CODE`）。
9. [ ] 配对：一方「生成配对码」（6 位，10 分钟有效），另一方输入。成功后「我的」页显示 TA 在线、MQTT 已连接。
10. [ ] 两人都注册完后关闭注册：把 `couple.env` 的 `REGISTRATION_CODE=` 清空 → `Restart-Service couple-backend`。

## 9. GitHub Secrets / Variables

仓库 → **Settings → Secrets and variables → Actions**。

**Secrets**（*New repository secret*）：

| Secret | 用于 | 值 |
|---|---|---|
| `DEPLOY_HOOK_URL` | 推送即部署 | `https://couple.example.com`（不带路径） |
| `DEPLOY_HOOK_SECRET` | 推送即部署 | 与服务器 `couple.env` 的 `DEPLOY_HOOK_SECRET` **完全相同** |
| `SIGNING_KEYSTORE_BASE64` | 打 tag 发版（APK 签名） | keystore 的 base64 |
| `SIGNING_STORE_PASSWORD` | 同上 | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 同上 | key 别名（如 `couple`） |
| `SIGNING_KEY_PASSWORD` | 同上 | key 密码 |

从服务器取 `DEPLOY_HOOK_SECRET`（复制到剪贴板，粘贴进 GitHub 后清空剪贴板）：

```powershell
$s = (Select-String -Path C:\couple\config\couple.env -Pattern '^DEPLOY_HOOK_SECRET=(.+)$').Matches[0].Groups[1].Value.Trim()
$s.Length          # 64
Set-Clipboard $s   # 粘贴到 GitHub 后执行：Set-Clipboard ' '
```

keystore 的生成与 base64 见 [`../README.md` 第二节](../README.md#二github-secretssettings--secrets-and-variables--actions)（Windows 上 base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes('couple-release.jks')) | Set-Clipboard`）。keystore **离线备份两份**，丢了就无法再给已安装的 App 推升级。

**Variables**（*Variables* 标签页）：`DEPLOY_TARGET` **不用设置**（不设 = `windows`；只有改用 Docker 方案才设为 `docker`）。

**GHCR 不需要任何配置**：每次推送仍会顺带构建 Docker 镜像（用自带的 `GITHUB_TOKEN`），但 Windows 服务器只用 GitHub Release 上的 zip。

- [ ] 验证：推一个改动到 `main`（或 Actions → `deploy` → *Run workflow*），`windows` → `deploy` 任务显示绿色 ✓，`Invoke-RestMethod https://couple.example.com/_deploy/status` 的 `current.server` 变成新的 `edge-xxxxxxx`。

## 10. 日常运维

### 10.1 推送即部署（自动）

```bash
git push origin main      # 动了 backend/**、admin/**、deploy/windows/** 等才触发
```

Actions 的 `deploy` workflow：测试 → `windows / build`（Linux 上交叉 jlink 打 zip）→ `windows / smoke`（windows-latest 上实测 `install.ps1` / 服务 / `uninstall.ps1`）→ `windows / publish-edge`（替换滚动预发布 `edge` 上的 zip）→ `windows / deploy`（签名 webhook，然后轮询 `/_deploy/status` 最多约 10 分钟）。

- 绿色 ✓ = 新版本健康；红色 ✗ = `failed` / `rolled_back` / `rollback_failed`（日志里有 `health_timeout`、`sha256_mismatch`、`download_failed`、`start_failed` 等原因）。
- 服务器侧看：`Invoke-RestMethod http://127.0.0.1:9000/_deploy/status`、`couple-updater.out.log`，管理后台仪表盘的「服务器状态」。
- 失败的那个 zip（按 sha256）会被标记为坏，轮询不再部署它；修好后再推一次即可。webhook 没打通时（Secret 没配、Bot Fight 拦截），服务器也会在 `UPDATE_INTERVAL` 内自己从 `edge` 拉到。

### 10.2 打 tag 发版

```bash
git tag -a v0.2.0 -m "这次更新了什么

minSupportedVersionCode: 0"
git push origin v0.2.0
```

产出 GitHub Release `v0.2.0`：`couple-0.2.0.apk`、`release.json`、`couple-server-windows-x64-0.2.0.zip` + `.sha256`。tag 发版**不调用 webhook**：`UPDATE_CHANNEL=stable`（或 `0.2.0`）的服务器在下一次轮询时自动部署；`edge` 的服务器已经在推 main 时部署过同样的代码。
**数据库迁移只允许“只扩不缩”**（加表/可空列/索引），因为回滚只换程序、不撤销迁移，见 [`../README.md`](../README.md#数据库迁移规则重要)。

### 10.3 手动回滚 / 固定版本

`C:\couple\releases\` 下保留最近 3 个版本（`KEEP_RELEASES`）。**先停自动更新**，否则下一轮轮询会再升回去：

```powershell
# 1) 停自动更新
(Get-Content C:\couple\config\couple.env) -replace '^UPDATE_ENABLED=.*', 'UPDATE_ENABLED=false' | Set-Content C:\couple\config\couple.env -Encoding UTF8
Restart-Service couple-updater

# 2) 切到本机已有的某个版本
Get-ChildItem C:\couple\releases -Directory | Sort-Object LastWriteTime -Descending | Select-Object Name, LastWriteTime
Get-Content C:\couple\releases\CURRENT
$ver = 'edge-1a2b3c4'                                   # 要回到的版本目录名
Stop-Service couple-backend
& C:\couple\updater\jre\bin\java.exe -cp 'C:\couple\updater\lib\*' com.qimingnan.couple.updater.MainKt render-service "C:\couple\releases\$ver"
Start-Service couple-backend
Restart-Service couple-updater                          # 让 updater 读到新的 CURRENT
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health

# 3) 修好后恢复
(Get-Content C:\couple\config\couple.env) -replace '^UPDATE_ENABLED=.*', 'UPDATE_ENABLED=true' | Set-Content C:\couple\config\couple.env -Encoding UTF8
Restart-Service couple-updater
```

- 固定到某个正式版（本机没有也行）：`UPDATE_CHANNEL=0.1.0`、`UPDATE_ENABLED=true` → `Restart-Service couple-updater`，updater 会下载 tag `v0.1.0` 的 zip 并部署。`edge` 只保留最新一个 zip，旧的 edge 构建只能用上面的本机方式回滚。
- `render-service` 的参数必须是 `C:\couple\releases\` 下的目录（安装目录不是默认值时先设 `$env:COUPLE_HOME`）。
- 别用旧 zip 的 `install.ps1` 来回滚：它会把 updater 也一起降级。

### 10.4 updater 自身升级（不会自动升级）

updater（以及 cloudflared、WinSW 服务配置）**不随推送即部署更新**。看到新版本改了 updater 或 `deploy/windows/**` 时：

1. 按第 3 节下载**当前正在跑的版本**（`Get-Content C:\couple\releases\CURRENT`；通常就是 `edge` 上最新的那个）或更新版本的 zip，校验 sha256 并解压。
2. 在解压目录执行 `powershell -ExecutionPolicy Bypass -File .\install.ps1`（不带隧道参数时 cloudflared 保持原样，只重启）。
   - 注意：`install.ps1` 总会把后端切到**这个 zip 的版本**并重启后端，所以别用比当前旧的 zip。
   - 要同时升级 cloudflared（`versions.env` 里的 `CLOUDFLARED_VERSION` 变了）或采用新的路由规则：带上三个 `-Tunnel*` 参数（`-TunnelJsonFile C:\couple\config\cloudflared\<UUID>.json` 即可）；路由规则变了再加 `-ForceTunnelConfig`。
3. `Get-Content C:\couple\updater\VERSION` 确认 updater 版本。

### 10.5 卸载

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1                  # 只删服务，保留 C:\couple（配置、数据、日志、版本）；不动 MySQL
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -RemoveData      # 连 C:\couple 一起删（要求输入 YES；-Force 跳过确认）
```

### 10.6 让 `JAVA_OPTS` 立即生效

```powershell
Stop-Service couple-backend
& C:\couple\updater\jre\bin\java.exe -cp 'C:\couple\updater\lib\*' com.qimingnan.couple.updater.MainKt render-service "C:\couple\releases\$((Get-Content C:\couple\releases\CURRENT).Trim())"
Start-Service couple-backend
Select-String -Path C:\couple\services\couple-backend.xml -Pattern '<arguments>'
```

### 10.7 备份

要备份的东西：

| 内容 | 位置 | 丢了会怎样 |
|---|---|---|
| MySQL 数据库 `couple`（含自动生成的引导私钥，`server_settings` 表） | MySQL | 数据全丢 |
| `couple.env`（所有密钥；若设置了 `BOOTSTRAP_PRIVATE_KEY` 也在这里） | `C:\couple\config\couple.env` | 需重新生成，所有人重登，GitHub Secret 要同步 |
| 引导私钥 | 数据库或 `couple.env` | **两台手机都要重新扫码信任** |
| 隧道凭证 + `cert.pem` | `C:\couple\config\cloudflared\`、`%USERPROFILE%\.cloudflared\cert.pem` | 需重建隧道 |
| APK 签名 keystore | 你的电脑（离线） | 只能卸载重装 App |

每天 04:00 自动备份（SYSTEM 计划任务，保留 14 天）：

```powershell
New-Item -ItemType Directory -Force C:\couple-backup | Out-Null
& icacls.exe C:\couple-backup /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F' | Out-Null

# mysqldump 的凭据文件（不把密码写在命令行里）
@'
[client]
user=root
password=<ROOT密码>      # setup.ps1 装的 MySQL：见 C:\couple\config\mysql-root.txt
host=127.0.0.1
'@ | Set-Content C:\couple-backup\mysqldump.cnf -Encoding ASCII

@'
$ErrorActionPreference = 'Stop'
$d = Get-Date -Format 'yyyy-MM-dd'
$dir = 'C:\couple-backup'
$sql = Join-Path $dir "couple-$d.sql"
# 用 --result-file，不要用 > 重定向（PowerShell 5.1 会写成 UTF-16）
& 'C:\mysql\bin\mysqldump.exe' --defaults-extra-file="$dir\mysqldump.cnf" --single-transaction --routines --events --default-character-set=utf8mb4 --result-file="$sql" couple
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed ($LASTEXITCODE)" }
Compress-Archive -Path $sql, 'C:\couple\config\couple.env', 'C:\couple\config\cloudflared' -DestinationPath (Join-Path $dir "couple-$d.zip") -Force
Remove-Item $sql
Get-ChildItem $dir -Filter 'couple-*.zip' | Where-Object LastWriteTime -lt (Get-Date).AddDays(-14) | Remove-Item
'@ | Set-Content C:\couple-backup\backup.ps1 -Encoding UTF8

$action  = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File C:\couple-backup\backup.ps1'
$trigger = New-ScheduledTaskTrigger -Daily -At 4:00am
Register-ScheduledTask -TaskName 'couple-backup' -Action $action -Trigger $trigger -User 'SYSTEM' -RunLevel Highest -Force
Start-ScheduledTask couple-backup; Start-Sleep 20; Get-ChildItem C:\couple-backup     # 立即跑一次验证
```

- MSI 装的 MySQL 把路径换成 `C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe`。
- [ ] 备份文件定期拷到另一台机器/网盘并加密；`cert.pem` 和 keystore 单独离线保存。
- 恢复（先解压出 `.sql`；不要用 PowerShell 管道喂给 mysql，5.1 会按 ASCII 转码弄坏中文）：
  ```powershell
  Stop-Service couple-updater, couple-backend
  cmd /c "C:\mysql\bin\mysql.exe --defaults-extra-file=C:\couple-backup\mysqldump.cnf --default-character-set=utf8mb4 couple < C:\restore\couple-2026-01-01.sql"
  Start-Service couple-backend, couple-updater
  ```

## 11. 故障排查

| 现象 | 排查 |
|---|---|
| `setup.ps1` 报“安装失败，步骤：[x/7 …]” | 按提示看 `C:\couple\logs\setup-<时间>.log`；修好后直接重新运行 `setup.ps1`（已完成的步骤会跳过）。MySQL 步骤失败看 `C:\mysql-data\mysqld.err`；隧道步骤失败多半是授权时选错了域名（删掉 `%USERPROFILE%\.cloudflared\cert.pem` 后重跑，授权时选 `<域名>` 所属的那个）或出站被拦 |
| `setup.ps1` 一直停在 Cloudflare 授权 | 把打印出的链接复制到任意电脑的浏览器打开并点 *Authorize*；超时后重新运行即可 |
| 中文显示成乱码/方块 | 控制台字体不支持中文（不影响安装）；看 `C:\couple\SETUP-RESULT.txt` 或日志文件。不要用记事本另存 `setup.ps1`（它必须保持 UTF-8 带 BOM） |
| `install.ps1` 报 `Run this script from an elevated PowerShell` | 没用管理员 PowerShell |
| `install.ps1` 报“无法加载，因为在此系统上禁止运行脚本” | 用 `powershell -ExecutionPolicy Bypass -File .\install.ps1` 启动，或 `Get-ChildItem -Recurse \| Unblock-File` |
| `couple-backend` 起不来 / 反复重启 | 看 `C:\couple\logs\couple-backend.err.log`、`.out.log`、`.wrapper.log`：`Missing required environment variable DB_URL`（`couple.env` 缺行）、`JWT_SECRET must be at least 32 characters`、`Communications link failure`（MySQL 没启动：`Get-Service MySQL84`；后端是延迟自动启动 + 失败自动重启，开机时一般会自愈）、`Access denied for user`（`DB_PASSWORD` 与 2.3 不一致）、`OutOfMemoryError`（调 `JAVA_OPTS`，见 10.6） |
| 端口被占用（`Address already in use` / `BindException`） | `Get-NetTCPConnection -LocalPort 8080,8083,9000,3306,20241 -State Listen \| Select LocalPort, OwningProcess, @{n='Proc';e={(Get-Process -Id $_.OwningProcess).Name}}`。8080 常被 IIS/其他 Java 占用：停掉对方，或改 `PORT`（同时改 cloudflared `config.yml`） |
| `couple.env` 打不开 / 拒绝访问 | 文件只给 Administrators/SYSTEM：从管理员 PowerShell 里 `notepad C:\couple\config\couple.env` |
| 公网访问 1033 / 502，`/ready` 不是 200 | `C:\couple\logs\couple-cloudflared.err.log`：凭证路径错、`-TunnelId` 与凭证不匹配、出站 TCP 7844 被封（`Test-NetConnection region1.v2.argotunnel.com -Port 7844`）、系统时间不准。`config.yml` 已强制 `protocol: http2`。cloudflared 的隧道连接**不走** `HTTPS_PROXY`（`HTTPS_PROXY` 只用于下载；cloudflared 本身是否支持经 HTTP 代理建隧道：待实机确认），7844 被封时需要能直连的出口 |
| `cloudflared ingress validate failed` | `C:\couple\config\cloudflared\config.yml` 被手改坏：用 `install.ps1 … -ForceTunnelConfig` 重新渲染 |
| Cloudflare **524** | 源站 **100 秒**内没返回（免费版上限，不能调）。webhook 本身立即返回 202、APK 镜像“立即同步”也是后台执行，正常不会触发；出现说明后端卡住（看后端日志、MySQL 是否卡住）。长时间操作不要走公网同步请求 |
| Actions 部署 `HTTP 403` 或返回 HTML 挑战页；App 连不上但浏览器能打开 | **Bot Fight Mode 没关**（免费版无法用 WAF 规则跳过）或有托管质询 / Browser Integrity Check：按第 1.1 节关闭 |
| Actions 部署 `HTTP 401` | 两边 `DEPLOY_HOOK_SECRET` 不一致，或服务器时间误差 > 300 秒（`w32tm /resync`） |
| Actions 部署 `HTTP 400 unknown service` | 仓库变量 `DEPLOY_TARGET` 被设成了 `docker`，删掉它 |
| Actions 部署 `HTTP 404` | `/_deploy/hook` 没路由到 9000（`config.yml` 旧了），或 `couple-updater` 没启动 |
| Actions 部署 `HTTP 503` | `UPDATE_ENABLED=false` 或 `DEPLOY_HOOK_SECRET` 为空 |
| Actions 显示 `rolled_back (health_timeout)` | 新版本起不来：看 `couple-backend.err.log`；旧版本已自动恢复、新 zip 已标记为坏 |
| Actions/状态里 `failed (download_failed)` | 服务器访问不了 GitHub：设 `HTTPS_PROXY` 后 `Restart-Service couple-updater` |
| `failed (version_in_use)` | 同一版本名但 zip 内容不同（例如重跑了同一 tag 的构建），不会覆盖正在运行的版本；手动 `install.ps1` 升级后也可能短暂出现，下一个新版本部署后消失（待实机确认） |
| MySQL：`Public Key Retrieval is not allowed` | `DB_URL` 缺 `allowPublicKeyRetrieval=true`（8.4 默认 `caching_sha2_password`，无 SSL 时需要它） |
| MySQL：`Plugin 'mysql_native_password' is not loaded` / 认证插件相关错误 | 用户建成了 `mysql_native_password`：`ALTER USER 'couple'@'127.0.0.1' IDENTIFIED WITH caching_sha2_password BY '<DB_PASSWORD>';`（`localhost` 那条同样处理） |
| MySQL 服务起不来 | `C:\mysql-data\mysqld.err`；缺 VC++ 运行库（`mysqld.exe` 直接弹 DLL 错误）；`my.ini` 带 BOM 或非 ASCII 编码（用 2.1 的 `Set-Content -Encoding ASCII` 重写）（待实机确认）；`datadir` 目录在 `--initialize` 前不为空 |
| App 扫码后“健康检查失败” | 手机上打开 `https://couple.example.com/api/v1/health`；`baseUrl` 不要带 `/api/v1`；Release 版必须 https |
| App 显示 MQTT 连不上 | `mqttUrl` 必须是 `wss://域名/mqtt`；Cloudflare WebSockets 开启；`Test-NetConnection 127.0.0.1 -Port 8083`；`MQTT_BROKER_MODE` 必须是 `embedded` |
| 「版本发布」同步失败 | 访问不了 `api.github.com` → `HTTPS_PROXY`；`CERT_MISMATCH` = 签名证书与固定的不一致 |
| 查看 updater 详细状态 | `Get-Content C:\couple\data\updater\updater.json`（`bad.server` 是被标记为坏的 sha256 列表） |
