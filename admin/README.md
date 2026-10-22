# Couple 管理后台（admin）

情侣 App 的 Web 管理后台，仅供管理员（`role=admin`）使用。接口以仓库根目录的 [`contract.md`](../contract.md) 为准。

技术栈：Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router + axios，界面为简体中文。

## 功能（P0）

| 页面 | 说明 |
|---|---|
| 登录 | 用户名 + 密码登录；JWT 中 `role` 不是 `admin` 时拒绝登录并立即注销刚签发的 refresh token |
| 仪表盘 | `GET /health`（状态、后端版本、协议版本、耗时）、签名公钥指纹、当前管理员的情侣配对状态（`GET /couple`）、当前会话信息 |
| 服务器配置 | `GET/PUT /admin/config`：后端地址、MQTT（wss://）、TURN（Cloudflare / coturn）、对象存储（本地 / MinIO / R2）。密钥字段只写、回显为脱敏值 |
| 引导二维码 | `POST /admin/bootstrap` 生成 `couple://bootstrap?d=…&s=…`，前端用 `qrcode` 渲染、显示解码后的 JSON、用服务器公钥在浏览器内校验 Ed25519 签名、下载 1024px PNG |
| 审计日志 | `GET /admin/audit?limit&before` 游标分页（上一页 / 下一页 / 回到最新），展开查看 meta |
| 主题 / 布局 / 插件 / 通话统计 | 侧边栏占位入口（禁用），分别在 P1–P5 实现 |

## 本地开发

需要 Node.js ≥ 20.19（CI 用 22）。

```bash
cd admin
cp .env.example .env.local   # 按需修改
npm install
npm run dev                  # http://localhost:5173
```

开发服务器会把 `/api` 代理到 `VITE_PROXY_TARGET`（默认 `http://localhost:8080`，即本地 Ktor 后端），所以 `VITE_API_BASE` 留空即可。

### 环境变量

| 变量 | 作用 |
|---|---|
| `VITE_API_BASE` | 后端根地址（**不含** `/api/v1`），构建时写入。留空 = 与后台同源，推荐用反向代理把 `/api` 转发给后端 |
| `VITE_PROXY_TARGET` | 仅 `npm run dev` 使用的代理目标 |

不要把任何密钥写进 `.env*`；这些变量会被打包进前端代码，公开可见。

## 常用命令

```bash
npm run type-check   # vue-tsc
npm run lint         # ESLint（flat config）
npm test             # Vitest 单元测试
npm run build        # 类型检查 + 生产构建，输出 dist/
```

## 鉴权与令牌刷新

- access token 15 分钟，refresh token 每次使用都会轮换；**重放旧 refresh token 会让后端吊销整个令牌族**。
- `src/api/http.ts` 的拦截器：
  - 请求前若 access 将在 30 秒内过期，先主动刷新；
  - 收到 401 时刷新一次并重试原请求（每个请求最多重试一次）；
  - 刷新是 **single-flight**：同一时刻的并发请求共享同一个刷新 Promise，绝不会并发提交同一枚 refresh token；
  - 跨标签页用 Web Locks API（`navigator.locks`）互斥，进入锁后若发现其它标签页已完成轮换就直接复用；
  - 刷新失败时清空本地令牌并跳回登录页。
- 令牌和浏览器级 `deviceId`（ULID）保存在 localStorage（`src/api/tokenStorage.ts`）。

## 与后端约定的细节（契约未写明的部分）

- **密钥只写**：`GET /admin/config` 返回脱敏值；`PUT` 时密钥字段缺省 / `null` / 脱敏值 = 保持不变，`""` = 清除，其他值 = 覆盖。后台只在用户输入了新值或勾选“清除”时才把该字段放进请求体。
- **TURN / 存储结构**（契约只写了 `{provider, ...}`）：

  ```jsonc
  "turn": {
    "provider": "cloudflare" | "coturn",
    "cloudflare": { "keyId": "", "apiToken": "<secret>" },
    "coturn": { "urls": ["turn:…"], "sharedSecret": "<secret>", "ttlSeconds": 86400 }
  },
  "storage": {
    "provider": "local" | "minio" | "r2",
    "endpoint": "", "bucket": "", "region": "", "publicBaseUrl": "",
    "accessKeyId": "", "secretAccessKey": "<secret>"
  }
  ```
  两种 TURN 提供方的配置都会提交保存，切换时不丢失。类型集中在 `src/types/api.ts`。
- **审计分页**：`before` 取上一页最后一条的 `ts`（毫秒，后端按 `ts < before` 过滤）；每次多取 1 条判断是否有下一页。
- **签名校验**：签名对象为 `d` 经 base64url 解码后的原始字节；公钥为 32 字节裸 Ed25519（标准 base64）。浏览器不支持 WebCrypto Ed25519 时显示“无法校验”，不影响生成。
- **配置版本**：后端在配置响应中额外返回 `configVersion` / `updatedAt`，页面会显示。

## 目录结构

```
src/
  api/          http.ts（axios + 刷新拦截器）、tokenStorage.ts、errors.ts、index.ts（接口）
  stores/       auth.ts（Pinia）
  views/        各页面
  layouts/      AdminLayout.vue（侧边栏 + 顶栏）
  components/   SecretInput.vue（只写密钥输入框）
  utils/        configForm.ts、bootstrap.ts、format.ts、base64url.ts
tests/          Vitest 单元测试
```

## 部署

`npm run build` 后把 `dist/` 交给任意静态服务器（Nginx / Caddy 等），并配置 SPA 回退到 `index.html`、把 `/api` 反向代理到后端。CI（`.github/workflows/admin.yml`）在 `admin/**` 变更时执行 `npm ci → type-check → lint → test → build` 并上传 `dist` 产物。
