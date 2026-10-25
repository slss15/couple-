# Couple — Android App（P0）

两人自用的情侣 App 安卓端。应用名 **Couple** 是占位名，改名只需修改
`app/src/main/res/values/strings.xml` 里的 `app_name`。

跨模块接口以仓库根目录的 [`../contract.md`](../contract.md) 为准。

## 技术栈

| 方面 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0、Jetpack Compose（Material 3）、Navigation Compose |
| 依赖注入 | Hilt（KSP） |
| 本地存储 | Room（资料/配对缓存）、DataStore（服务器配置）、EncryptedSharedPreferences + Android Keystore（token） |
| 网络 | Retrofit + OkHttp + kotlinx.serialization |
| 扫码 | CameraX + ML Kit Barcode（模型随 APK 打包，不依赖 Play 服务） |
| 后台任务 | WorkManager（配置回滚兜底） |
| 实时 | Eclipse Paho MQTT v3（WSS） |
| 签名校验 | BouncyCastle Ed25519（Android 33 以下没有系统 Ed25519） |

`minSdk 26`，`targetSdk/compileSdk 35`，包名 `com.qimingnan.couple`（debug 包加 `.debug` 后缀，可与正式版共存）。

## 构建

需要 JDK 17 和 Android SDK（platform 35、build-tools 35.0.0）。

```bash
cd app
./gradlew assembleDebug testDebugUnitTest
# APK：app/build/outputs/apk/debug/app-debug.apk
```

发版：打 `vX.Y.Z` tag 后由 `../.github/workflows/release.yml` 构建签名 APK（versionCode = X*10000+Y*100+Z）并发布 GitHub Release，详见 [`../deploy/README.md`](../deploy/README.md)。本地 `assembleRelease` 默认 0.1.0，未设置 `SIGNING_*` 环境变量时产出未签名 APK。

CI：`../.github/workflows/app.yml`，只在 `app/**` 变更时运行 `assembleDebug + testDebugUnitTest`，产物上传 APK 和测试报告。

## 首次启动流程

```
扫码（或手动输入）→ 校验签名 → 30 秒健康检查 → 登录/注册 → 配对 → 主界面
```

### 1. 引导配置（二维码）

- 二维码内容：`couple://bootstrap?d=<base64url(json)>&s=<base64url(ed25519签名)>`，
  json = `{v:1, baseUrl, mqttUrl, configVersion, issuedAt}`。签名对象是 `d` 解码后的原始 JSON 字节。
- **TOFU 固定公钥**：首次扫码时从 `GET {baseUrl}/api/v1/bootstrap/pubkey` 取公钥（兼容 raw 32 字节或 X.509 SPKI 的 base64），
  之后只认这把公钥，其他公钥签的配置一律拒绝。“我的 → 服务器配置”里显示公钥指纹（SHA-256 前 8 字节），两人可以对一下。
- **防降级**：已签名配置的 `configVersion` 只能升不能降。
- **30 秒回滚**：新配置先写入为“试用”，同时保留上一份；每 2 秒请求一次 `/health`，30 秒内成功才提交，否则自动回滚。
  协议版本不一致（`protocolVersion != 1`）立即回滚。首次配置失败时连同暂定的公钥一起撤销。
  App 在试用期被杀也没关系：下次启动和 WorkManager 兜底任务都会补做判断。
- **确认页（R7）**：扫码、深链或粘贴链接后先进确认页，显示服务器主机名、API/MQTT 地址和公钥指纹
  （SHA-256 前 8 字节，四位一组 `ab12 cd34 ef56 7890`，与管理后台首页显示的字节相同），确认后才写入。
  健康检查通过后本机记住“已信任的主机名 + 公钥指纹”。之后若主机名或公钥不同，显示红色强提醒，必须勾选明确确认才可继续；
  不经确认，其他公钥签名的配置仍按 TOFU 规则拒绝。确认更换公钥后若 30 秒试用失败，恢复原公钥。
- 除了 App 内扫码，系统相机扫到 `couple://bootstrap` 链接也能直接拉起 App。
- **调试入口**：“手动输入”页可以粘贴 couple:// 链接，或者直接填写后端/MQTT 地址（不校验签名，但仍做健康检查与回滚）。
  debug 包允许 `http://` / `ws://`（方便连模拟器里的 `10.0.2.2`），release 包只允许 `https://` / `wss://`。
- 已生效的服务器地址变化后，本地 token 自动作废并回到登录页。

### 2. 登录 / 注册

- 注册需要服务器的注册码（`inviteCode` 字段，对应后端 `REGISTRATION_CODE`）。
- 每台设备首次启动生成一个 ULID 作为 `deviceId`，随登录/刷新一起提交。
- access token 15 分钟；遇到 401 时 OkHttp `Authenticator` 用 refresh token 换新（服务端每次轮换），
  并发请求只刷新一次；新的 refresh token 用 `commit()` 同步落盘，避免进程被杀后复用旧 token 导致整族吊销。
  刷新被服务端拒绝（4xx）→ 清空本地会话，提示重新登录。

### 3. 配对

- 一方点“生成配对码”，显示 6 位数字、10 分钟倒计时和“有效至 HH:mm”，并每 3 秒查询一次 `GET /couple`；
  服务器与手机时钟相差过大时按“收到时刻 + 10 分钟”计算；
- 另一方输入配对码 → `POST /pair/join`；
- 配对成功后强制刷新 token（拿到带 `cid` 的 access token）。
- 解绑：“我的 → 解除配对”，一方“申请解绑”得到申请编号，另一方“确认 TA 的解绑申请”（编号可留空，由服务端取最新一条）。

### 4. 主界面

底部四个 Tab：**聊天 / 动态 / TA / 我的**。P0 只有“我的”是真页面：资料、配对信息与 TA 在线状态、
MQTT 连接状态、服务器配置（地址、版本、签名状态、公钥指纹、重新扫码）、解绑、退出登录。

### 5. MQTT（R6：可靠连接）

- `username = userId`，`password = accessToken`，clientId = `a-{userId}-{deviceId}`（固定，符合服务端 clientId 绑定规则）；
- `cleanSession=false` + QoS1 订阅：离线期间（Doze、切网、进程被杀）服务端保留会话并排队 QoS1 消息；在途 QoS1 发送落盘，重启后补发；
- `keepAlive = 30s`（Cloudflare 约 100 秒断开空闲 WebSocket）；
- 默认网络变化（Wi-Fi ↔ 移动数据、断网恢复）立即重连；其他失败按 1s → 60s 指数退避，±20% 抖动；断网时不空转重试；
- 连上后订阅并发布 retained 的 `couple/{cid}/presence`：`{userId, online:true, ts, mid}`；遗嘱消息为 `online:false`；
  `mid` 为 ULID 消息 ID（可选字段，旧客户端缺省时用确定性 ID），接收端按 mid 去重、按发送者保持顺序（过期状态丢弃）；
- 每条消息记录发送 ts / 接收 ts：回环（自己发出的）与 TA→我 两组 p50/p95，以及重连、切网、去重计数，
  显示在“我的 → 后台保活 · 连接诊断”，并写入 logcat（tag `CoupleMqtt`）；
- 不用 Paho 自带的自动重连（每次重连都要换最新 token），鉴权失败时先强制刷新 token。

### 6. 后台保活（我的 → 后台保活）

- 体检：通知权限、忽略电池优化、后台限制、流量节省、待机分组、精确闹钟（当前未使用）；返回页面自动重新检查；
- 识别厂商（小米/HyperOS、华为、荣耀、OPPO/一加/realme、vivo、三星），一键跳转自启动 / 电池设置，失败回落到应用详情；
- Shizuku 已授权时一键执行 `dumpsys deviceidle whitelist +<pkg>` 与 `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow`；
- 可选“常驻连接”前台服务（`specialUse`，低重要性静音通知），默认关闭。
- 真机测试脚本：`app/scripts/reliability-test.sh -a <发送方序列号> -b <接收方序列号>`（debug 包，USB 连接），
  依次测 Doze（force-idle）、待机分组、后台限制、Wi-Fi/飞行模式/移动数据切换下的送达率与延迟，结果写入 CSV。

## 主题 token

- `theme/CoupleTokens.kt`：`CoupleTokens(colors, shapes, spacing, typography)`，通过 `LocalCoupleTokens` 提供；
  Material3 的 `ColorScheme`/`Typography` 也从 token 派生。
- 颜色字面量**只允许**出现在 `theme/Palette.kt`。单测 `ThemeTokensTest` 会：
  - 校验浅色、深色两套色板所有“文字/背景”组合对比度 ≥ 4.5:1，图标/边框 ≥ 3:1；
  - 扫描 `src/main/java`，发现 `Color(0x…)`、`Color.Red` 之类的硬编码就失败（plan 里的 Detekt 规则之前的临时替代）。
- 点击区域最小 48dp（`spacing.minTouchTarget`）。

## 布局 JSON

`layout/LayoutSpec.kt` + `layout/LayoutValidator.kt`（P0 stub，后端应实现同一套规则）：

```json
{"version":1,"zones":[{"id":"bottomBar","items":[{"id":"me_entry","order":3,"visible":true,"size":"M"}]}]}
```

- 区（zone）：`topBar / bottomBar / feedHeader / chatToolbar / callControls / taHeader / taCards / meSections`，各有数量上限和网格宽度；
- 尺寸：`S / M / L` 分别占 1 / 2 / 4 格；
- 锁定项（不可删除、隐藏、移动）：`send`、`back`、`hangup`、`sharing_indicator`、`privacy_toggle`、`me_entry`；
- 校验失败时 `LayoutValidator.orDefault()` 回退默认布局（安全模式）。

## 目录结构

```
app/src/main/java/com/qimingnan/couple/
├── CoupleApp.kt / MainActivity.kt
├── core/            Json、Base64Url、ULID、Clock
├── data/
│   ├── bootstrap/   二维码解析、Ed25519 校验、TOFU、健康检查与回滚、WorkManager 兜底
│   ├── auth/        token 存储（加密）、刷新、会话
│   ├── net/         Retrofit 接口、拦截器、Authenticator、错误码
│   ├── couple/      配对 / 解绑
│   ├── db/          Room
│   ├── mqtt/        MQTT over WSS + presence
│   └── AppCoordinator.kt  进程级编排（何时连 MQTT、换服务器后清会话）
├── di/              Hilt 模块
├── theme/           token、色板、对比度
├── layout/          布局模型与校验器
└── ui/              bootstrap / auth / pairing / main / me / components
```

## 单元测试

| 测试 | 覆盖 |
|---|---|
| `BootstrapUriParserTest` | 链接解析、签名通过/篡改/换钥、SPKI 公钥、版本与明文地址限制 |
| `BootstrapManagerTest` | TOFU 固定、拒绝他人签名、30 秒回滚（虚拟时间）、窗口内恢复、首次失败撤销公钥、协议不兼容、防降级、进程被杀后恢复、手动配置 |
| `TokenRefreshTest` | 401 自动刷新与轮换、5 个并发 401 只刷新一次、刷新被拒清空会话、baseUrl 改写（MockWebServer） |
| `LayoutValidatorTest` | 默认布局合法、锁定项隐藏/删除/移动、重复、容量与网格宽度、未知区、非法 JSON、回退默认 |
| `ThemeTokensTest` | 对比度、48dp、禁止硬编码颜色 |
| `MqttReliabilityTest` | 退避与 ±20% 抖动边界、去重与顺序、p50/p95、clientId、网络切换判定 |
| `TrustCheckTest` | 指纹格式（与管理后台同字节）、主机名规范化、主机/公钥变化判定、确认页流程、换钥失败回滚 |
| `PairCodeExpiryTest` | 配对码过期时间与时钟偏差 |
| `OemKeepAliveTest` | 厂商识别、自启动/电池页面候选、Shizuku 命令（拒绝注入） |

## 已知限制（P0）

- 聊天 / 动态 / TA 三个 Tab 只是占位。
- 没有 FCM；MQTT 只在进程存活时连接（可在“后台保活”中开启常驻连接前台服务）。
- 发码方通过轮询 `GET /couple` 发现配对成功（P1 改为 MQTT 通知）。
- 解绑申请编号需要口头告诉对方（P1 通过 MQTT 推送给对方）。
