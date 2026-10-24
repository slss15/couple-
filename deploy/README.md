# 部署与自动更新

> **生产主推 Windows 原生部署**（云 Windows 主机，没有虚拟化 → 不能跑 Docker/WSL2）：后端内置 MQTT broker 并直接托管管理后台，
> couple-backend / couple-updater / couple-cloudflared 三个 Windows 服务，从零配置见 [`windows/SETUP-windows.md`](windows/SETUP-windows.md)。
> 推 `main` → CI 产出 `couple-server-windows-x64-edge-<sha>.zip`（交叉 jlink 的 JRE 21 + 后端 + 管理后台 + updater + WinSW）发到滚动预发布 `edge`，
> 并通过签名 webhook 让 couple-updater 下载、校验 sha256、切换版本、健康检查（失败自动回滚）。打 tag 时 zip 也附在正式 Release 上。
> 下面描述的是可选的 Linux + Docker 方案（同一套 HMAC webhook，载荷不同，由仓库变量 `DEPLOY_TARGET` 选择）。

家里的服务器在 NAT4 后面：**没有入站端口，只能出站**。所以：

- 构建全部在 GitHub Actions 上完成，镜像推到 GHCR（`ghcr.io/slss15/couple-*`）；
- 服务器上的 `updater` 容器负责更新，两种触发方式共用同一套“拉取 → 重建 → 健康检查 → 失败回滚”流程：
  1. **推送即部署**（主路）：推到 `main` → Actions 测试、构建、推送 `:edge` → 通过 Cloudflare 入口（Caddy 或 cloudflared）调用签名 webhook `/_deploy/hook` → 服务器部署 → Actions 轮询 `/_deploy/status`，部署失败或被回滚时任务显示红叉；
  2. **定时轮询**（兜底）：每 `UPDATE_INTERVAL` 秒比对远端镜像 digest，webhook 漏了也会在一个周期内追上。
- 不用自托管 Runner（公开仓库有风险，见文末）。

```
git push main ──► Actions: test ─► build amd64+arm64 ─► push :sha-xxxxxxx / :edge
                                                     └─► POST https://<域名>/_deploy/hook (HMAC 签名)
                                                            │  Cloudflare ─► Caddy / cloudflared ─► updater:9000
                                                            ▼
                                     updater: pull @digest ─► compose up --force-recreate ─► 健康检查
                                                            ├─ 成功：记录 current
                                                            └─ 失败：回滚到上一个 digest，标记新 digest 为坏
Actions 轮询 GET /_deploy/status（最多约 10 分钟）─► 成功 ✓ / 回滚或失败 ✗
```

## 目录

| 文件 | 作用 |
|---|---|
| `windows/` | **主推：Windows 原生部署**——服务器 zip 打包脚本、WinSW 服务模板、`setup.ps1`（一键安装向导）/`install.ps1`/`uninstall.ps1`、cloudflared 配置模板、[`SETUP-windows.md`](windows/SETUP-windows.md) |
| `docker-compose.yml` | Linux/Docker 栈（可选）：mysql、backend、admin、emqx、updater、cloudflared（默认入口）；可选 `aaaa`（Caddy+DDNS）、`tunnel-token` |
| `.env.example` | 环境变量模板，复制成 `.env` |
| `cloudflared/config.yml` | 默认隧道的路由（本地管理，已提交；凭证 `cloudflared/credentials.json` 不提交） |
| `Caddyfile` | 可选 AAAA 方案的路由（同样顺序） |
| `updater/` | Docker 版更新器（Python 标准库 + docker CLI + compose 插件），含单元/集成测试；Windows 版在 `../backend/updater/`（Kotlin） |
| `scripts/deploy-hook.sh` | Actions 里调用 webhook 并等待结果的脚本（`windows` / `docker` 两种载荷） |

`backend/docker-compose.yml` 仍是**从源码构建**的开发栈；服务器上用本目录。

## 一、首次上线

```bash
# 服务器上（只需要本目录，不需要整个仓库）
git clone https://github.com/slss15/couple-.git && cd couple-/deploy
cp .env.example .env && vi .env          # 填密码、域名、DEPLOY_HOOK_SECRET 等
docker compose up -d                     # 默认带本地管理隧道（见 SETUP.md 第 1 节）
docker compose logs -f updater
```

- `DEPLOY_HOOK_SECRET`：`openssl rand -hex 32` 生成，**服务器 `.env` 和 GitHub Secret 填同一个值**。留空则 webhook 关闭，只剩轮询。
- `COUPLE_TAG`：`edge`（默认，每次推 main 都部署）/ `stable`（只跟正式版）/ `0.2.0`（固定版本，配合 `UPDATE_ENABLED=false` 完全手动）。
- 入口路由变化（相对原来的 `backend/deploy/Caddyfile`）：
  - 新增 `/_deploy/hook`、`/_deploy/status` → `updater:9000`，其他 `/_deploy/*` 一律 404；
  - `/api/*` → backend；`/mqtt*` → emqx；`/api/v1/internal/*` 仍然 404；
  - 其余路径 → admin 前端（同域访问，前端的 `/api` 请求也由 admin 的 nginx 转给 backend）。
  - 路由写在 `cloudflared/config.yml`（本地管理隧道）；只有用备选的 token 模式时，才需要在 Zero Trust 面板里按同样**顺序**手工添加 Public Hostname。
- **Bot Fight Mode 必须关闭**（免费版无法用 WAF 规则跳过，会拦 Actions 的 curl 和 App 的非浏览器客户端）；`/api/*`、`/mqtt`、`/_deploy/*` 上不要有任何质询。

## 二、GitHub Secrets（Settings → Secrets and variables → Actions）

| Secret | 用途 | 必需 |
|---|---|---|
| `DEPLOY_HOOK_URL` | 服务器公网地址，例如 `https://couple.example.com`（不带路径） | 推送即部署 |
| `DEPLOY_HOOK_SECRET` | 与服务器 `.env` / `couple.env` 相同的 HMAC 密钥 | 推送即部署 |
| `SIGNING_KEYSTORE_BASE64` | 发布签名 keystore 的 base64 | 打 tag 发版 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 | 打 tag 发版 |
| `SIGNING_KEY_ALIAS` | key 别名 | 打 tag 发版 |
| `SIGNING_KEY_PASSWORD` | key 密码 | 打 tag 发版 |

仓库**变量** `DEPLOY_TARGET`：`windows`（默认，未设置即是）= webhook 部署 Windows 包；`docker` = webhook 部署镜像。两种产物每次推 main 都会构建（Windows zip 发到滚动预发布 `edge`，镜像推到 GHCR），只是 webhook 只通知其中一种。

推镜像用的是自带的 `GITHUB_TOKEN`（`packages: write`），不需要额外配置。
没配 `DEPLOY_HOOK_*` 时镜像照样推送，只是改由服务器轮询拉取（Actions 里会有一条黄色警告）。
缺任何一个签名 Secret，发版任务会直接失败并提示缺哪个——**绝不会发布未签名或 debug 签名的 APK**。

### 生成签名 keystore（只做一次，务必备份）

```bash
keytool -genkeypair -v -keystore couple-release.jks -alias couple \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Couple, O=slss15"
base64 -w0 couple-release.jks > couple-release.jks.b64   # macOS: base64 -i couple-release.jks
# 把 .b64 的内容填到 SIGNING_KEYSTORE_BASE64，然后删除 .b64；.jks 离线备份，不要提交到仓库
```

keystore 丢了就再也无法给已安装的 App 推送升级（签名不一致），只能卸载重装。

## 三、GHCR 镜像可见性

首次推送后包默认是 **private**。二选一：

1. **改成公开**（推荐，仓库本来就公开）：GitHub 个人主页 → Packages → `couple-backend` / `couple-admin` → Package settings → Change visibility → Public。
2. **保持私有**：建一个只有 `read:packages` 权限的 classic PAT，在服务器 `.env` 里填 `GHCR_USER=<GitHub 用户名>`、`GHCR_TOKEN=<PAT>`。updater 用它登录拉取和查 digest。手动 `docker compose pull` 前也要 `docker login ghcr.io`。

## 四、正式发版（tag）

```bash
git tag -a v0.2.0 -m "修复若干问题

minSupportedVersionCode: 100"     # 可选：低于此 versionCode 的客户端强制升级
git push origin v0.2.0
```

`release.yml` 会：

1. 校验 tag 必须是 `vX.Y.Z`（Y、Z ≤ 99），`versionCode = X*10000 + Y*100 + Z`（0.2.0 → 200）；
2. 构建多架构镜像并打 `X.Y.Z`、`X.Y`；
3. 构建签名 APK，用 `apksigner` 校验签名、拒绝 debug 证书、核对 versionCode/versionName；
4. 以上全部成功后才把镜像的 `stable` 指向这个版本；
5. 创建 GitHub Release，附件 `couple-X.Y.Z.apk` 和 `release.json`：

```json
{"versionName":"0.2.0","versionCode":200,"sha256":"<apk 的 hex sha256>","size":12345678,
 "signingCertSha256":"<签名证书 hex sha256>","minSupportedVersionCode":100,
 "notes":"修复若干问题","publishedAt":1767225600000}
```

tag 注释的正文就是 `notes`（`minSupportedVersionCode:` 那一行会被去掉）。也可以在 Actions 页面手动运行 `release` 并指定已有 tag 和 `minSupportedVersionCode`。

本地构建：`cd app && ./gradlew assembleRelease` 默认版本 0.1.0；带 `SIGNING_*` 环境变量（`SIGNING_STORE_FILE` 指向 .jks）才会签名，否则产出未签名 APK。R8 混淆/资源压缩已开启。

## 五、服务器自动更新细节

| 变量 | 默认 | 说明 |
|---|---|---|
| `UPDATE_ENABLED` | `true` | `false` 时轮询和 webhook 都不部署（webhook 返回 503） |
| `UPDATE_CHANNEL` | `COUPLE_TAG` 或 `edge` | 跟踪的镜像 tag |
| `UPDATE_INTERVAL` | `300` | 轮询间隔（秒，最少 30） |
| `UPDATE_WINDOW` | 空 | 轮询只在此时段执行，如 `03:00-05:00`（按 `TZ`），可跨午夜；webhook 不受限 |
| `HEALTH_TIMEOUT` | `90` | 健康检查超时（秒） |
| `DEPLOY_HOOK_SECRET` | 空 | webhook 的 HMAC 密钥，空则关闭 webhook |
| `GHCR_USER` / `GHCR_TOKEN` | 空 | 私有包时使用 |

- 健康检查：backend `GET http://backend:8080/api/v1/health` 返回 200；admin `GET http://admin:8080/` 返回 200。
- 失败处理：把上一个 digest 重新打回 `:<channel>` 并重建；新 digest 记为坏，轮询会跳过它，直到出现**不同的新 digest**。（webhook 显式推送同一个坏 digest 时仍会再试一次。）
- 状态：`/state/updater.json`（卷 `updater-state`），含 `current`、`deploys`、`bad`、`lastCheck`、`lastResult`、`lastError`；同时尽力 POST 到 backend `POST /api/v1/internal/updater/status`（头 `X-Internal-Token: ${MQTT_HOOK_SECRET}`），失败忽略。
  公网可见的 `GET /_deploy/status` 只返回 digest、状态和错误类别（如 `health_timeout`、`pull_failed`），不含任何密钥或日志。
- webhook 安全：
  - 请求体 `{"service","image","digest","sha","ts"}`，头 `X-Deploy-Timestamp`、`X-Deploy-Signature = hex(HMAC-SHA256(secret, "<ts>.<body>"))`；
  - 拒绝：签名不对、时间差超过 300 秒、请求体 `ts` 与头不一致、重放（同一签名，重启后仍记得）、镜像不是 `ghcr.io/slss15/couple-<service>`、服务不是 backend/admin、digest 格式不对；
  - 校验通过立即返回 202，后台按 digest 拉取部署；
  - updater 挂载了 docker.sock（等同宿主机 root），所以它只监听内网、只暴露这两个路径、请求体上限 8 KB，并且只会部署白名单内仓库的镜像。**密钥泄露时立刻在两边轮换。**
- 只有 backend 和 admin 会被自动更新；mysql/emqx/caddy 等用 `docker compose pull && docker compose up -d` 手动升级。updater 改了代码后：`git pull && docker compose up -d --build updater`。
- updater 在容器内以 `/compose` 为项目目录执行 `docker compose up --no-deps`，所以 **backend/admin 不要加 bind mount**（命名卷没问题），`.env` 放在本目录。

### 数据库迁移规则（重要）

回滚只换回旧的**程序**，**不会撤销已执行的 Flyway 迁移**。因此发布流程要求迁移**只扩不缩（expand-only）**：

- 可以：新增表、新增可空列或带默认值的列、新增索引；
- 禁止在同一版本里：删除/重命名列或表、改列类型、加 NOT NULL 约束到已有列；
- 真要删，分两次发版：先发不再使用该列的版本，稳定后再发删除迁移。

这样新版本迁移后健康检查失败、回滚到旧程序时，旧程序依然能在新表结构上运行。

## 六、手动固定版本 / 回滚

```bash
# 固定到某个正式版并停止自动更新
sed -i 's/^COUPLE_TAG=.*/COUPLE_TAG=0.2.0/; s/^UPDATE_ENABLED=.*/UPDATE_ENABLED=false/' .env
docker compose pull backend admin && docker compose up -d backend admin updater

# 回到某次 main 推送的镜像（Actions 日志/Packages 页里能看到 sha-xxxxxxx）
docker pull ghcr.io/slss15/couple-backend:sha-1a2b3c4
docker tag  ghcr.io/slss15/couple-backend:sha-1a2b3c4 ghcr.io/slss15/couple-backend:edge
docker compose up -d --no-deps --force-recreate backend
# 记得先设 UPDATE_ENABLED=false 并重建 updater，否则下一轮轮询会再升回 edge 最新版

# 恢复自动更新
sed -i 's/^COUPLE_TAG=.*/COUPLE_TAG=edge/; s/^UPDATE_ENABLED=.*/UPDATE_ENABLED=true/' .env
docker compose up -d updater

# 查看状态
docker compose exec updater cat /state/updater.json
curl -s https://couple.example.com/_deploy/status | jq
```

## 七、其他方案

### Watchtower（不推荐，作对比）

```bash
docker run -d --name watchtower -v /var/run/docker.sock:/var/run/docker.sock \
  containrrr/watchtower --interval 300 couple-backend-1 couple-admin-1
```

一行就能定时拉新镜像，但它**不做健康检查、不会回滚**，新版本起不来服务就一直挂着；也没有 webhook 结果回传给 Actions。我们的 updater 多了健康检查 + 自动回滚 + 坏版本跳过 + 推送即部署。

### GitHub 自托管 Runner（可行，但公开仓库有风险）

自托管 Runner 是**出站长轮询** GitHub 的，所以 NAT4 后面也能用：在服务器上装 runner，部署 job 用 `runs-on: self-hosted` 直接 `docker compose pull && up -d`。

**风险**：公开仓库里，任何人都能提 PR；如果某个 workflow 在 PR 上用到了自托管 Runner，陌生人的代码就会在你家服务器上执行（而且 runner 通常能访问 docker.sock，等于 root）。GitHub 官方也建议**不要在公开仓库上使用自托管 Runner**。

如果一定要用：
- **先把仓库改成私有**（推荐）；
- 或者至少：Settings → Actions → 要求所有外部贡献者的 workflow 需审批；runner 只放在单独的 runner group、只允许 `main` 分支上的特定 workflow；runner 用非 root 用户、不进 docker 组，部署通过受限的 sudo 脚本完成。

当前方案（GitHub 托管 Runner 构建 + 签名 webhook + 轮询兜底）不需要在服务器上运行任何来自仓库的代码，公开仓库也安全。

## 八、CI 一览

| workflow | 触发 | 用 Secrets？ | 部署？ |
|---|---|---|---|
| `backend.yml` / `admin.yml` / `app.yml` | PR、推 main | 否 | 否（只构建+测试） |
| `deploy-backend.yml`（+ `_image-deploy.yml`） | 推 main（backend/**、admin/**）、手动 | 是 | 是（`:edge` + webhook） |
| `release.yml` | 推 `v*.*.*` tag、手动 | 是 | 推 `stable`（服务器跟 stable 时自动更新）+ GitHub Release |

## 九、本地验证

```bash
python3 -m unittest -v deploy/updater/test_updater.py   # HMAC（有效/签名错/过期/重放）、部署/回滚、HTTP+deploy-hook.sh 集成
shellcheck deploy/scripts/deploy-hook.sh admin/docker/40-couple-config.sh
actionlint .github/workflows/*.yml
(cd deploy && docker compose config -q)                 # 需先填好 .env
```
