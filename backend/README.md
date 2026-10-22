# Couple 后端（P0）

两人自用情侣 App 的后端。接口以仓库根目录的 `../contract.md` 为准（权威契约）。

- 技术栈：Kotlin 2.1 + Ktor 3（Netty）、Exposed + HikariCP + MySQL 8（Flyway 迁移）、kotlinx.serialization、JWT（HS256，java-jwt）、Argon2id（Bouncy Castle 纯 Java 实现）、Ed25519（JDK）、ULID。
- JDK：21（Gradle toolchain）。
- 模块：`config` / `auth`（注册、登录、refresh 轮换 + 家族吊销、登出）/ `pairing`（配对码、加入、解绑申请+对方确认）/ `bootstrap`（公钥、二维码签名）/ `admin`（配置、密钥脱敏、审计日志）/ `mqtt`（EMQX HTTP 认证/ACL 钩子）/ `health`。
- 每个 auth / pair / admin 操作都会写一条审计日志（`audit_log` 表，`GET /api/v1/admin/audit` 查看）。

## 目录

```
backend/
├── build.gradle.kts, settings.gradle.kts, gradlew, gradle/wrapper/
├── src/main/kotlin/com/qimingnan/couple/backend/
│   ├── Application.kt            # main + Ktor module（插件、路由装配）
│   ├── config/AppConfig.kt       # 环境变量
│   ├── common/                   # 错误体、模型（User/TokenPair/Couple）、ULID、工具
│   ├── db/                       # Exposed 表定义、Hikari + Flyway、server_settings
│   ├── audit/                    # 审计
│   ├── auth/ pairing/ bootstrap/ admin/ mqtt/ health/
├── src/main/resources/db/migration/V1__init.sql
├── src/test/kotlin/...           # Ktor testApplication + H2（MySQL 模式）
├── Dockerfile, docker-compose.yml, .env.example
└── deploy/Caddyfile, deploy/cloudflared-config.example.yml
```

## 本地运行

### 只跑测试

```bash
cd backend
./gradlew test          # 需要 JDK 21；测试用 H2 内存库（MODE=MySQL），无需 MySQL
```

### 本机直跑（自备 MySQL 8）

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/couple?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export DB_USER=couple DB_PASSWORD=... JWT_SECRET=$(openssl rand -base64 48) REGISTRATION_CODE=...
./gradlew run           # 或 ./gradlew installDist && build/install/couple-backend/bin/couple-backend
curl localhost:8080/api/v1/health
```

### Docker Compose（backend + MySQL 8.4 + EMQX 5.8）

```bash
cd backend
cp .env.example .env    # 填写所有 change-me；.env 不会进 git
docker compose up -d --build                  # 仅内网
docker compose --profile aaaa up -d --build   # 方案 A：Cloudflare 代理 AAAA + Caddy + DDNS
docker compose --profile tunnel up -d --build # 方案 B：Cloudflare Tunnel
```

- 后端只绑定 `127.0.0.1:8080`，EMQX Dashboard 只绑定 `127.0.0.1:18083`（用 SSH 隧道访问），MySQL 不对外。
- EMQX 通过环境变量配置 HTTP 认证（`/api/v1/internal/mqtt/auth`）和 HTTP 授权（`/api/v1/internal/mqtt/acl`），`no_match = deny`，授权缓存 30 秒。
- EMQX 在 backend 健康后才启动；如果 backend 重启，EMQX 的 HTTP 资源会自动重连（期间新连接会被拒绝）。

### 首次初始化

1. 第一个注册的用户自动成为管理员（或设置 `ADMIN_USERNAME` 指定）。注册需要邀请码 `REGISTRATION_CODE`。
2. 管理员 `PUT /api/v1/admin/config` 设置 `baseUrl`（如 `https://couple.example.com`，**不含** `/api/v1`）和 `mqttUrl`（如 `wss://couple.example.com/mqtt`）。
3. `POST /api/v1/admin/bootstrap` 得到 `couple://bootstrap?d=...&s=...`，由管理后台渲染成二维码，App 扫码。
4. Ed25519 私钥：设置 `BOOTSTRAP_PRIVATE_KEY`（推荐，生成：`openssl genpkey -algorithm ed25519 -outform DER | base64 -w0`），否则首次启动自动生成并存入数据库 `server_settings` 表（启动日志会打印公钥）。**私钥丢失 = 所有 App 需要重新信任（TOFU）**，请备份。

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 是 | MySQL JDBC 连接（compose 中自动拼接 `DB_URL`） |
| `JWT_SECRET` | 是 | HS256 密钥，≥ 32 字符 |
| `REGISTRATION_CODE` | 否 | 注册邀请码；留空 = 关闭注册 |
| `BOOTSTRAP_PRIVATE_KEY` | 否 | Ed25519 私钥：base64 的 PKCS#8 DER / PEM / 32 字节 seed |
| `ADMIN_USERNAME` | 否 | 指定管理员用户名（设置后"第一个用户是管理员"规则不再生效） |
| `MQTT_HOOK_SECRET` | 否（强烈建议） | EMQX 调内部钩子时的 `X-Internal-Token`（契约外新增） |
| `PORT` | 否 | 默认 8080 |
| `AUTH_RATE_LIMIT_PER_MINUTE` | 否 | 注册/登录/刷新每 IP 每分钟上限，默认 30 |

## 对外暴露：Cloudflare（同一域名同时承载 API 与 MQTT over WSS）

家里服务器没有公网 IPv4。两种入口都让 `https://couple.example.com/api/v1/...` 走后端、`wss://couple.example.com/mqtt` 走 EMQX 的 WebSocket 监听（8083，路径 `/mqtt`）。Cloudflare 边缘是双栈的，所以即使只配 AAAA 记录，只有 IPv4 的手机网络也能访问。

`/api/v1/internal/*` 是 EMQX 专用钩子，**必须不对外**：Caddy / cloudflared 规则直接返回 404；后端也会拒绝任何带 `CF-Connecting-IP` 头（即经过 Cloudflare）的内部请求，并校验 `MQTT_HOOK_SECRET`。

### 方案 A：Cloudflare 代理的 AAAA 记录（服务器有公网 IPv6 时的主路）

1. Cloudflare DNS 新建 `AAAA couple → <服务器 IPv6>`，**开启代理（橙云）**。IPv6 会变时用 compose 里的 `ddns` 服务（`favonia/cloudflare-ddns`）自动更新，需在 `.env` 填 `CLOUDFLARE_API_TOKEN`（仅 Zone:DNS:Edit 权限）。
2. SSL/TLS → 概览选 **Full (strict)**；SSL/TLS → 源服务器 → 创建 **Origin CA 证书**，保存为 `deploy/certs/origin.pem` 和 `deploy/certs/origin.key`（已在 `.gitignore`，不要提交）。
3. Network 中确认 **WebSockets** 已开启（默认开启）。
4. `docker compose --profile aaaa up -d`。Caddy 监听 443，按 `deploy/Caddyfile` 分流：`/mqtt*` → `emqx:8083`，`/api/v1/internal/*` → 404，其它 → `backend:8080`。
5. 防火墙只放行 Cloudflare IP 段访问 443（列表见 <https://www.cloudflare.com/ips/>，定期核对；`deploy/Caddyfile` 的 `trusted_proxies` 也用同一份）。注意 Docker 发布的端口会绕过 ufw，请在 `DOCKER-USER` 链（`ip6tables` / `iptables`）里加规则，例如：
   ```bash
   for net in 2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32; do
     ip6tables -I DOCKER-USER -p tcp --dport 443 -s $net -j RETURN
   done
   ip6tables -A DOCKER-USER -p tcp --dport 443 -j DROP
   ```
   同时确认 Docker 已为该端口监听 IPv6（`ss -ltn | grep 443` 能看到 `[::]:443`）。
6. 验证：`curl https://couple.example.com/api/v1/health`。

### 方案 B：Cloudflare Tunnel（没有公网 IPv6 时的主路，或作为备用）

1. Cloudflare Zero Trust → Networks → Tunnels → 创建 tunnel（cloudflared 类型），复制 token 填入 `.env` 的 `CLOUDFLARE_TUNNEL_TOKEN`。
2. 在该 tunnel 的 **Public Hostname** 中按顺序添加（顺序很重要，先匹配先生效）：
   | Hostname | Path | Service |
   |---|---|---|
   | couple.example.com | `^/api/v1/internal/.*` | `http_status:404`（面板里不支持时可省略，后端会自行拒绝） |
   | couple.example.com | `^/mqtt.*` | `http://emqx:8083` |
   | couple.example.com | （空） | `http://backend:8080` |
   本地管理的 tunnel 可参考 `deploy/cloudflared-config.example.yml`。
3. `docker compose --profile tunnel up -d`。Tunnel 自动创建 CNAME，无需开放任何入站端口。
4. Tunnel 只用于 HTTP/WSS 信令和 API；按 Cloudflare 条款，**不要**通过 Tunnel 长时间转发音视频（通话走 TURN，P4 再做）。

### MQTT 客户端连接方式（P0）

- URL：`wss://couple.example.com/mqtt`（WebSocket 子协议 `mqtt`）。
- `username = userId`，`password = accessToken`（15 分钟有效，过期后需刷新 token 并重连）。
- 只能发布/订阅 `couple/{自己的 coupleId}/...`；P0 主题 `couple/{cid}/presence`（retained，`{userId, online, ts}`）。未配对用户可以连上但所有主题都被拒绝。

## API 速览（全部在 `/api/v1` 下）

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/health` | - | `{status, version, protocolVersion:1}` |
| POST | `/auth/register` `/auth/login` `/auth/refresh` | - | 返回 TokenPair（限流） |
| POST | `/auth/logout` | - | 吊销该 refresh token 所在家族，204 |
| POST | `/pair/code` · `/pair/join` | Bearer | 6 位码，10 分钟，单次有效 |
| GET | `/couple` | Bearer | Couple 或 404 `NOT_PAIRED` |
| POST | `/couple/unpair/request` · `/couple/unpair/confirm` | Bearer | 必须由另一方确认；软删除 couple |
| GET | `/bootstrap/pubkey` | - | `{alg:"Ed25519", publicKey}`（原始 32 字节公钥的标准 base64） |
| POST | `/admin/bootstrap` | admin | `{uri, json}` |
| GET/PUT | `/admin/config` | admin | 密钥只写、返回脱敏 |
| GET | `/admin/audit?limit&before` | admin | 按 ts 倒序，`before` 为 epoch ms（不含） |
| POST | `/internal/mqtt/auth` · `/internal/mqtt/acl` | 内部 | 仅供 EMQX |

### 实现细节（契约未写明处）

- **Refresh**：明文 token 为 32 字节随机数（base64url），库里只存 SHA-256。每次刷新都轮换；已用过的 token 再次出现 → 整个家族吊销并返回 401 `REFRESH_REUSED`。`deviceId` 必须与签发时一致（`DEVICE_MISMATCH`）。同一设备重新登录会吊销该设备旧的会话。
- **配对后 JWT 里的 `cid`**：access token 是无状态的，加入/解绑后旧 token 里的 `cid` 会过时，App 应在配对状态变化后调用 `/auth/refresh`。服务端所有判断（含 MQTT ACL）都以数据库为准，不信任 `cid` 声明。
- **错误码与 HTTP 状态**：`CODE_INVALID` / `CODE_EXPIRED` / `SELF_PAIR` → 400；`ALREADY_PAIRED` → 409；`NOT_PAIRED` → 404；`NOT_OTHER_MEMBER` → 403；`UNPAIR_REQUEST_NOT_FOUND` → 404；`UNPAIR_REQUEST_EXPIRED` → 400；`UNPAIR_REQUEST_CLOSED` → 409；`INVALID_CREDENTIALS` / `REFRESH_*` / `UNAUTHORIZED` → 401；`INVITE_CODE_INVALID` / `REGISTRATION_DISABLED` / `FORBIDDEN` → 403；`USERNAME_TAKEN` → 409；`VALIDATION_ERROR` / `BAD_REQUEST` / `CONFIG_INCOMPLETE` → 400；`RATE_LIMITED` → 429。
- **解绑申请**：7 天有效；新申请会作废旧的未确认申请。
- **二维码签名**：`s` 是对 `base64url解码(d)` 原始字节的签名（base64url 无填充）。校验时必须对 `d` 的字节验签，不能对重新序列化的 JSON 验签。
- **管理配置密钥**：键名匹配 `secret|token|password|passwd|credential|private_key|api_key`（不区分大小写，任意层级）即视为密钥。GET 返回 `****` + 末 4 位（长度 ≥ 12）或 `********`，未设置返回 `null`；PUT 时省略、传 `null` 或原样回传脱敏值 = 保留，传 `""` = 清除，其它值 = 覆盖。每次 PUT 使 `configVersion` +1。审计日志只记录变更了哪些密钥的名字。
- **密码**：Argon2id（m=19456 KiB, t=2, p=1，PHC 字符串格式）。
