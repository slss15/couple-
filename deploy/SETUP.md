# 后端从零配置清单

一条路走到底：照顺序打勾即可。更细的原理见 [`README.md`](README.md)（自动更新/发版）和 [`../backend/README.md`](../backend/README.md)（接口与实现）。

> 约定：域名用 `couple.example.com` 代替，换成你自己的；命令都在服务器的 `deploy/` 目录下执行。

---

## 0. 前置条件

- [ ] 一台常开的 Linux 服务器（家里 NAS / 小主机都行），能出站访问 `ghcr.io`、`github.com`、`cloudflare.com`。
- [ ] 安装 Docker Engine ≥ 24 和 Compose 插件 v2：
  ```bash
  curl -fsSL https://get.docker.com | sh
  docker version && docker compose version
  ```
- [ ] 确认架构：`uname -m` → `x86_64`（amd64）或 `aarch64`（arm64）。两种镜像都有，无需额外操作。
- [ ] 判断有没有可用的公网 IPv6，决定入口方案：
  ```bash
  curl -6 -s https://ifconfig.co || echo "没有公网 IPv6"
  ```
  | 情况 | 选择 | 启动参数 |
  |---|---|---|
  | 有公网 IPv6，且路由器/光猫可放行 443 入站 | 方案 A：Cloudflare 代理 AAAA + Caddy + DDNS | `--profile aaaa` |
  | 没有 IPv6 / NAT4 / 不想开端口 | 方案 B：Cloudflare Tunnel（只出站） | `--profile tunnel` |
- [ ] 一个托管在 Cloudflare 的域名（NS 已切到 Cloudflare）。
- [ ] 拿到代码（服务器只需要 `deploy/` 目录）：
  ```bash
  git clone https://github.com/qimingnan17/couple-.git && cd couple-/deploy
  cp .env.example .env && chmod 600 .env
  ```

## 1. 域名与 Cloudflare

### 方案 A：AAAA + DDNS + Caddy

1. [ ] Cloudflare → 域名 → **SSL/TLS** → 模式选 **Full (strict)**。
2. [ ] **SSL/TLS → Origin Server → Create Certificate**（RSA，主机名填 `couple.example.com`，15 年），把证书和私钥分别存为 `deploy/certs/origin.pem`、`deploy/certs/origin.key`（`chmod 600`，不要提交）。
3. [ ] **My Profile → API Tokens → Create Token** → 模板 *Edit zone DNS*，Zone Resources 只选这个域名 → 得到的 token 填 `.env` 的 `CLOUDFLARE_API_TOKEN`。
4. [ ] `.env` 填 `PUBLIC_HOSTNAME=couple.example.com`。DDNS 容器会自动创建/更新橙云 AAAA 记录。
5. [ ] 路由器/防火墙放行 TCP 443 入站（建议只放行 [Cloudflare IP 段](https://www.cloudflare.com/ips/)）。

### 方案 B：Cloudflare Tunnel

1. [ ] Cloudflare **Zero Trust → Networks → Tunnels → Create a tunnel** → 类型 *Cloudflared* → 起名 `couple`。
2. [ ] 在“安装连接器”页面复制 `--token` 后面那串，填 `.env` 的 `CLOUDFLARE_TUNNEL_TOKEN`（不用按页面装 cloudflared，compose 里已有）。
3. [ ] 在该 tunnel 的 **Public Hostname** 里**按以下顺序**添加（先匹配先生效）：

| # | Subdomain/Domain | Path | Service |
|---|---|---|---|
| 1 | couple.example.com | `^/api/v1/internal/.*` | `http_status:404` |
| 2 | couple.example.com | `^/_deploy/(hook\|status)$` | `http://updater:9000` |
| 3 | couple.example.com | `^/_deploy/.*` | `http_status:404` |
| 4 | couple.example.com | `^/mqtt.*` | `http://emqx:8083` |
| 5 | couple.example.com | `^/api/.*` | `http://backend:8080` |
| 6 | couple.example.com | （空） | `http://admin:8080` |

> 面板里不能直接配 `http_status:404` 时，可以把 1、3 改成 Access 策略拦截，或改用本地配置文件 `cloudflared-config.example.yml`。

### 两种方案共同的路由结果

| 路径 | 去向 | 说明 |
|---|---|---|
| `/api/*` | backend:8080 | REST API（App 和管理后台都走这里） |
| `/mqtt` | emqx:8083 | MQTT over **WSS**，App 填 `wss://couple.example.com/mqtt` |
| `/_deploy/hook`、`/_deploy/status` | updater:9000 | GitHub Actions 推送即部署（HMAC 签名），不经过 backend |
| `/api/v1/internal/*`、其他 `/_deploy/*` | 404 | 内部钩子绝不对外 |
| 其余 | admin:8080 | 管理后台网页 |

- [ ] Cloudflare → **Network** → 确认 **WebSockets** 开启（默认开）。
- [ ] 如开了 Bot Fight Mode / WAF 托管挑战：**Security → WAF → Custom rules** 加一条 `URI Path starts with /_deploy/` → **Skip**，否则 GitHub Actions 的部署请求会被拦。

## 2. 填写 `.env`

`.env.example`（`deploy/` 与 `backend/` 下两份内容相同）已按下面分组。生成命令里的随机串都**各生成一次、互不相同**。

通用随机串：
```bash
openssl rand -base64 48 | tr -d '/+=' | cut -c1-48
```

### 2.1 MySQL

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | ✅ | — | MySQL root 密码，只在首次初始化数据卷时生效 | 通用随机串 |
| `DB_USER` | | `couple` | 后端使用的 MySQL 用户 | — |
| `DB_PASSWORD` | ✅ | — | 上述用户的密码，首次初始化数据卷时创建 | 通用随机串 |
| `DB_URL` | | compose 自动拼接 | 只有在本机直跑后端（不用 compose）时才需要 | — |

> 数据卷建好后再改这两个密码**不会**生效，需要进 MySQL 手动 `ALTER USER`。

### 2.2 后端

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `JWT_SECRET` | ✅ | — | 签发登录令牌的 HS256 密钥，**≥ 32 字符**，否则后端拒绝启动；修改后所有人需重新登录 | `openssl rand -base64 48` |
| `REGISTRATION_CODE` | 建议 | 空 | 注册邀请码，留空 = 关闭注册。两人都注册完后建议清空 | `openssl rand -hex 4` 或自定一个 |
| `MQTT_HOOK_SECRET` | 强烈建议 | 空 | EMQX 调 `/api/v1/internal/mqtt/*`、updater 上报状态时带的 `X-Internal-Token` | `openssl rand -hex 32` |
| `BOOTSTRAP_PRIVATE_KEY` | 建议 | 空 | 引导二维码的 Ed25519 签名私钥（base64 的 PKCS#8 DER / 32 字节 seed）。留空则首次启动自动生成并存进数据库。**丢失 = 所有手机要重新信任** | `openssl genpkey -algorithm ed25519 -outform DER \| base64 -w0` |
| `ADMIN_USERNAME` | | 空 | 指定管理员用户名；留空 = 第一个注册的用户是管理员 | — |
| `AUTH_RATE_LIMIT_PER_MINUTE` | | `30` | 注册/登录/刷新，每 IP 每分钟上限 | — |
| `HTTPS_PROXY` | | 空 | 版本镜像任务访问 `api.github.com` 的出站代理，如 `http://192.168.1.2:7890`（国内网络拉 GitHub 慢时用） | — |
| `RELEASES_DIR` | | `/data/releases` | 镜像 APK 的存放目录；compose 中固定并挂载命名卷 `releases-data`，**不用在 `.env` 里设置** | — |
| `PORT` | | `8080` | 容器内端口，compose 已固定，不要改 | — |
| `APP_VERSION` / `APP_REVISION` | | 镜像自带 | 由 CI 写入镜像，后台“服务器状态”显示用，不要手动设置 | — |

### 2.3 EMQX

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `EMQX_DASHBOARD_PASSWORD` | ✅ | — | EMQX 控制台（`http://127.0.0.1:18083`，用户 `admin`）密码，仅首次初始化生效 | 通用随机串 |

### 2.4 公网入口

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `PUBLIC_HOSTNAME` | 方案 A ✅ | — | 公网域名，Caddy 证书和 DDNS 用 | — |
| `CLOUDFLARE_API_TOKEN` | 方案 A ✅ | — | DDNS 用，权限 Zone:DNS:Edit，只限该域名 | 见第 1 节 A-3 |
| `CLOUDFLARE_TUNNEL_TOKEN` | 方案 B ✅ | — | Tunnel token | 见第 1 节 B-2 |

### 2.5 镜像与自动更新

| 名称 | 必填 | 默认 | 说明 | 如何生成 |
|---|---|---|---|---|
| `COUPLE_TAG` | | `edge` | 运行哪个镜像 tag：`edge` 每次推 main 都部署；`stable` 只跟正式版；`0.2.0` 固定版本 | — |
| `DEPLOY_HOOK_SECRET` | 推送即部署 ✅ | 空 | webhook 的 HMAC 密钥，**必须与 GitHub Secret `DEPLOY_HOOK_SECRET` 相同**；留空 = 关闭 webhook，只靠轮询 | `openssl rand -hex 32` |
| `UPDATE_ENABLED` | | `true` | `false` = 停止一切自动部署（webhook 返回 503） | — |
| `UPDATE_CHANNEL` | | 同 `COUPLE_TAG` | updater 跟踪的 tag，一般留空 | — |
| `UPDATE_INTERVAL` | | `300` | 轮询间隔秒数（最少 30），用于补上漏掉的 webhook | — |
| `UPDATE_WINDOW` | | 空 | 轮询时段，如 `03:00-05:00`（按 `TZ`），可跨午夜；webhook 不受限 | — |
| `HEALTH_TIMEOUT` | | `90` | 新版本健康检查超时秒数，超时自动回滚 | — |
| `GHCR_USER` | 私有包 ✅ | 空 | GitHub 用户名 | — |
| `GHCR_TOKEN` | 私有包 ✅ | 空 | 只勾 `read:packages` 的 classic PAT（GitHub → Settings → Developer settings → Tokens (classic)） | — |
| `TZ` | | `Asia/Shanghai` | updater 时区 | — |
| `ADMIN_API_BASE` | | 空 | 管理后台调 API 的地址；留空 = 同域（推荐） | — |

- [ ] 填完检查语法：`docker compose config -q`（没有输出就是对的）。

## 3. 首次启动与验证

```bash
docker compose --profile tunnel up -d        # 方案 A 换成 --profile aaaa
docker compose ps                             # backend / admin / emqx / mysql 应为 healthy
docker compose logs -f backend updater        # Ctrl+C 退出
```

- [ ] 本机健康检查：
  ```bash
  curl -s http://127.0.0.1:8080/api/v1/health      # 返回 200 和 JSON（含 protocolVersion）
  curl -sI http://127.0.0.1:8081/ | head -1        # admin：HTTP/1.1 200
  ```
- [ ] 公网健康检查（经过 Cloudflare）：
  ```bash
  curl -s https://couple.example.com/api/v1/health
  curl -s https://couple.example.com/_deploy/status | jq   # updater 状态（不含任何密钥）
  curl -s -o /dev/null -w '%{http_code}\n' https://couple.example.com/api/v1/internal/mqtt/auth   # 必须是 404
  ```
- [ ] EMQX：浏览器开 `http://127.0.0.1:18083`（远程用 `ssh -L 18083:127.0.0.1:18083 服务器`），用户 `admin` + `EMQX_DASHBOARD_PASSWORD` 登录：
  - **Access Control → Authentication** 有一条 HTTP 认证，状态 *Connected*；
  - **Access Control → Authorization** 有一条 HTTP 授权源，且 *No Match* 为 *deny*。
- [ ] 若 `BOOTSTRAP_PRIVATE_KEY` 留空：`docker compose logs backend | grep -i bootstrap` 会看到“Generated a new bootstrap Ed25519 key”警告——私钥已存进数据库，第 8 节的 MySQL 备份会包含它。
- [ ] GHCR 拉镜像报 `denied`：说明包还是 private，按 [`README.md` 第三节](README.md#三ghcr-镜像可见性) 改公开，或填 `GHCR_USER/GHCR_TOKEN`。

## 4. 第一个账号 = 管理员

管理后台只有登录页，没有注册入口；App 要扫二维码才能连服务器，而二维码要管理员生成——所以第一个账号用 API 注册（没设 `ADMIN_USERNAME` 时，**第一个注册的用户自动成为管理员**，请自己先注册）：

```bash
curl -s https://couple.example.com/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"me","password":"一个强密码","displayName":"我","inviteCode":"<REGISTRATION_CODE>","deviceId":"setup-cli","deviceName":"curl"}' | jq .user
```

- [ ] 返回里有 `user.id` 即成功（之后在手机上用同一账号密码登录即可）。
- [ ] 打开 `https://couple.example.com/`，用该账号登录管理后台，左侧菜单应有：仪表盘 / 服务器配置 / 版本发布 / 引导二维码 / 审计日志。

## 5. 后台「服务器配置」逐项填写

每次保存都会让 `configVersion` +1；**改了地址后要重新生成引导二维码**（第 6 节）。密钥类字段保存后只显示 `****末4位`：留着不动 = 保持，清空 = 删除。

### 5.1 地址

| 字段 | 填什么 |
|---|---|
| 后端地址 `baseUrl` | `https://couple.example.com`（**不要**带 `/api/v1`） |
| MQTT 地址 `mqttUrl` | `wss://couple.example.com/mqtt` |

Release 版 App 只接受 `https://` / `wss://`。

### 5.2 TURN（语音/视频通话中继，二选一）

**Cloudflare Realtime TURN（推荐，免运维）**

1. Cloudflare 控制台 → **Realtime**（旧名 Calls）→ **TURN Server** → **Create**，起名 `couple`。
2. 创建后得到 **Turn Token ID** 和 **API Token**（API Token 只显示一次，立即复制）。
3. 后台：提供方选 `cloudflare`，**TURN Key ID** 填 Turn Token ID，**API Token** 填 API Token → 保存。后端会用它按需换取短期 TURN 凭证，密钥不会下发给手机。

**自建 coturn**

1. 另起一台**有公网地址**的机器（TURN 需要入站 UDP，家里 NAT4 机器不行），安装 coturn，`/etc/turnserver.conf` 关键项：
   ```
   use-auth-secret
   static-auth-secret=<openssl rand -hex 32 生成>
   realm=couple.example.com
   listening-port=3478
   tls-listening-port=5349
   ```
2. 后台：提供方选 `coturn`，**TURN URLs** 填 `turn:turn.example.com:3478?transport=udp`、`turns:turn.example.com:5349`，**Shared Secret** 填 `static-auth-secret`，**凭证有效期**默认 86400 秒即可。

### 5.3 存储（照片/文件）

| 类型 | 说明 | 要填 |
|---|---|---|
| `local` | 存在服务器本地，最简单 | 无 |
| `minio` | 自建 S3 兼容存储 | Endpoint、Bucket、Region（一般 `us-east-1`）、Access Key ID、Secret Access Key、公开访问前缀（可选） |
| `r2` | Cloudflare R2：控制台 **R2 → Create bucket**，再 **Manage R2 API Tokens → Create API token**（Object Read & Write，限定该 bucket） | Endpoint `https://<账户ID>.r2.cloudflarestorage.com`、Bucket、Region `auto`、Access Key ID、Secret Access Key、公开访问前缀（绑定了自定义域名时填） |

### 5.4 版本发布设置（同一页的「版本发布设置」卡片）

后端会定时从 GitHub Releases 镜像 APK 到本机（`releases-data` 卷），手机从你自己的服务器下载更新，不依赖手机能访问 GitHub。

| 字段（API 名） | 填什么 |
|---|---|
| GitHub 仓库 `repo` | `qimingnan17/couple-`（默认值，一般不用改） |
| GitHub Token `githubToken` | 公开仓库可留空；仓库私有或想提高 API 限额时填一个只读的 fine-grained token（Contents: Read） |
| 自动发布 `autoPublish` | 开 = 镜像成功后直接对手机可见；关 = 镜像后停在“已镜像”，需在「版本发布」页手动发布（推荐先关，自己试装后再发） |
| 轮询间隔 `pollMinutes` | 默认 30 分钟；`0` = 关闭定时镜像，只能手动“立即同步”；其他取值 5–1440 |
| 签名证书固定 `expectedSigningCertSha256` | 发布签名证书的 SHA-256（64 位十六进制）。留空则用第一次镜像到的版本自动固定。之后任何证书不同的 APK 都会被拒绝 |

获取证书指纹（与 `release.json` 的 `signingCertSha256` 一致）：
```bash
keytool -list -v -keystore couple-release.jks -alias couple | grep 'SHA256:' | tr -d ': ' | sed 's/SHA256//' | tr 'A-F' 'a-f'
# 或者对已签名 APK：apksigner verify --print-certs couple-0.2.0.apk | grep 'SHA-256'
```

- [ ] 保存后到「版本发布」页点“立即同步”，确认没有报错（还没发过版时列表为空是正常的）。

## 6. 引导二维码 → 手机扫码 → 注册 → 配对

1. [ ] 后台「引导二维码」→ 生成。核对下方解出来的 `baseUrl`、`mqttUrl` 是第 5.1 节填的值。
2. [ ] 两台手机安装 APK（GitHub Release 里的 `couple-X.Y.Z.apk`），打开 → 扫码（或系统相机扫 `couple://bootstrap` 链接）。
   - App 会先取公钥（首次信任 TOFU），校验签名，再做 30 秒健康检查，通过后才保存。
   - 两人可以在“我的 → 服务器配置”里对一下**公钥指纹**是否一致。
3. [ ] 登录 / 注册：管理员用第 4 节的账号登录；另一半在 App 里注册（用户名 + 密码 + 邀请码 `REGISTRATION_CODE`）。
4. [ ] 配对：一方“生成配对码”（6 位，10 分钟有效），另一方输入。成功后“我的”页显示 TA 在线状态和 MQTT 已连接。
5. [ ] 两人都注册完后：把 `.env` 的 `REGISTRATION_CODE` 清空并 `docker compose up -d backend`，关闭注册。

## 7. GitHub Secrets 一览

仓库 → **Settings → Secrets and variables → Actions → New repository secret**：

| Secret | 用于 | 值 |
|---|---|---|
| `DEPLOY_HOOK_URL` | 推送即部署 | `https://couple.example.com`（不带路径） |
| `DEPLOY_HOOK_SECRET` | 推送即部署 | 与服务器 `.env` 的 `DEPLOY_HOOK_SECRET` 相同 |
| `SIGNING_KEYSTORE_BASE64` | 打 tag 发版 | `base64 -w0 couple-release.jks` 的输出 |
| `SIGNING_STORE_PASSWORD` | 打 tag 发版 | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 打 tag 发版 | key 别名（例如 `couple`） |
| `SIGNING_KEY_PASSWORD` | 打 tag 发版 | key 密码 |

生成 keystore（只做一次，**离线备份两份**，丢了就无法再给已安装的 App 推升级）：
```bash
keytool -genkeypair -v -keystore couple-release.jks -alias couple \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Couple, O=qimingnan17"
```

推镜像用自带的 `GITHUB_TOKEN`，不需要额外 Secret。PR 上运行的 CI 不读取任何 Secret、也不部署。

## 8. 日常运维

### 推送即部署（backend / admin）

```bash
git push origin main          # 改动了 backend/** 或 admin/** 时触发 deploy workflow
```
Actions 里 `deploy` 任务：测试 → 构建 amd64+arm64 → 推 `:sha-xxxxxxx` 和 `:edge` → 调 webhook → 等服务器结果。
绿色 ✓ = 新版本健康；红色 ✗ = 部署失败或已自动回滚（看日志里的 `health_timeout` / `pull_failed`）。webhook 没打通时，服务器会在 `UPDATE_INTERVAL` 内自己拉到 `edge`。

### 打 tag 发版（APK + stable 镜像）

```bash
git tag -a v0.2.0 -m "这次更新了什么

minSupportedVersionCode: 0"
git push origin v0.2.0
```
产出 GitHub Release（`couple-0.2.0.apk` + `release.json`）和 `stable`/`0.2`/`0.2.0` 镜像。后端的版本镜像任务随后把 APK 拉到本机；`autoPublish` 关闭时去「版本发布」页手动发布。
**数据库迁移只允许“只扩不缩”**（加表/加可空列/加索引），因为回滚只换回旧程序、不撤销迁移，详见 [`README.md`](README.md#数据库迁移规则重要)。

### 手动回滚 / 固定版本

```bash
# 1) 先停自动更新
sed -i 's/^UPDATE_ENABLED=.*/UPDATE_ENABLED=false/' .env && docker compose up -d updater
# 2a) 回到某个正式版
sed -i 's/^COUPLE_TAG=.*/COUPLE_TAG=0.1.0/' .env && docker compose pull backend admin && docker compose up -d backend admin
# 2b) 或回到某次 main 推送
docker pull ghcr.io/qimingnan17/couple-backend:sha-1a2b3c4
docker tag  ghcr.io/qimingnan17/couple-backend:sha-1a2b3c4 ghcr.io/qimingnan17/couple-backend:edge
docker compose up -d --no-deps --force-recreate backend
# 3) 修好后恢复
sed -i 's/^COUPLE_TAG=.*/COUPLE_TAG=edge/; s/^UPDATE_ENABLED=.*/UPDATE_ENABLED=true/' .env && docker compose up -d
```

### 备份

```bash
mkdir -p ~/couple-backup
# MySQL（每天一次，可放 crontab：0 4 * * *）
docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines couple' \
  | gzip > ~/couple-backup/couple-$(date +%F).sql.gz
# .env（含所有密钥）
cp .env ~/couple-backup/env-$(date +%F)
# 引导私钥：如果设置了 BOOTSTRAP_PRIVATE_KEY，它就在 .env 里；如果是自动生成的，它在数据库 server_settings 表里，随 MySQL 备份一起保存
```
- [ ] 备份文件放到另一台机器/网盘并加密。**引导私钥丢失 = 两台手机都要重新扫码信任；发布 keystore 丢失 = 只能卸载重装。**
- 恢复 MySQL：`gunzip -c couple-日期.sql.gz | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" couple'`

### 升级其他组件

mysql / emqx / caddy / cloudflared 不会被自动更新：`docker compose pull && docker compose up -d`。updater 本身：`git pull && docker compose up -d --build updater`。

## 9. 常见故障排查

| 现象 | 排查 |
|---|---|
| `docker compose up` 报 `required variable ... is missing` | `.env` 里该必填项为空；对照第 2 节 |
| backend 一直 `unhealthy` / 重启 | `docker compose logs backend`：`JWT_SECRET must be at least 32 characters`、数据库连不上（MySQL 还在初始化，等 1 分钟；或改了 `DB_PASSWORD` 但数据卷是旧的） |
| 拉镜像 `denied` / `unauthorized` | GHCR 包是 private：改公开或设 `GHCR_USER/GHCR_TOKEN` |
| 公网访问 502 / 1033 | 方案 B：tunnel 未连接（`docker compose logs cloudflared`）、Public Hostname 的服务名写错；方案 A：AAAA 未更新（`docker compose logs ddns`）、443 没放行、证书文件不在 `certs/` |
| 公网访问 525/526 | 方案 A 的 SSL 模式不是 Full (strict) 或源站证书不匹配域名 |
| App 扫码后“健康检查失败”并回滚 | 手机上打开 `https://couple.example.com/api/v1/health` 看是否 200；`baseUrl` 不要带 `/api/v1`；Release 版必须 https |
| App 显示 MQTT 连不上 | `mqttUrl` 必须是 `wss://域名/mqtt`；Cloudflare WebSockets 开启；EMQX 控制台认证状态；`MQTT_HOOK_SECRET` 改过后要 `docker compose up -d` 让 EMQX 也拿到新值 |
| 注册提示邀请码错误 / 注册已关闭 | `REGISTRATION_CODE` 为空即关闭注册；改后需 `docker compose up -d backend` |
| Actions 部署步骤 `HTTP 401` | 两边 `DEPLOY_HOOK_SECRET` 不一致，或服务器时间不准（误差需 < 300 秒，开启 NTP：`timedatectl set-ntp true`） |
| Actions 部署步骤 `HTTP 403` / HTML 挑战页 | Cloudflare WAF/Bot Fight 拦截，按第 1 节加 `/_deploy/` 跳过规则 |
| Actions 部署步骤 `HTTP 404` | 入口没把 `/_deploy/hook` 路由到 updater（第 1 节表格），或 updater 没启动 |
| Actions 部署步骤 `HTTP 503` | 服务器 `UPDATE_ENABLED=false` 或 `DEPLOY_HOOK_SECRET` 为空 |
| Actions 显示 `rolled_back (health_timeout)` | 新版本起不来：`docker compose logs backend` 看启动报错；旧版本已自动恢复，该 digest 被标记为坏，修复后再推即可 |
| 「版本发布」同步失败 | 服务器访问不了 `api.github.com`：设置 `HTTPS_PROXY`；私有仓库需要 GitHub Token；拒绝原因 `CERT_MISMATCH` = 签名证书和固定的不一致 |
| 查看 updater 详细状态 | `docker compose exec updater cat /state/updater.json` |
