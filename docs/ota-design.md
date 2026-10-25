# Couple App OTA 目标设计（v2）

> 适用范围：Android App 自更新（`app/app/src/main/java/com/qimingnan/couple/data/update/*`、`ui/update/UpdateDialog.kt`），后端 `backend/.../release/*`，CI `.github/workflows/release.yml`。
> 协议以 `contract.md` 的 “Auto-update” 一节为准；本文写“为什么”和“目标形态”，contract 写“线上必须怎样”。
> 现状基线：提交 2f34521（App）、4ef5193（后端）、8a42f9c（CI）。
> 原则：自用、两台设备、深度优先而不是功能堆叠。

状态标记：**【已完成】** 基线里已有；**【本 PR 实现】** 本分支 `feat/ota-v2` 实现；**【缺口】** 已识别，未做。

---

## 0. 现状一览（基于代码）

| 环节 | 现在怎么做 | 位置 |
|---|---|---|
| 产物 | tag `vX.Y.Z` → CI 构建 release 签名 APK + `release.json`（sha256/size/signingCertSha256/minSupportedVersionCode/notes），上传 GitHub Release | `release.yml` app / github-release job |
| 镜像 | 后端定时拉 GitHub Releases，校验 size、sha256、APK v2/v3 签名证书 == release.json == 证书 pin（首次 TOFU） | `ReleaseMirror.kt`、`ApkSigningCert.kt` |
| 分发 | `GET /app/update` 按 PUBLISHED + rolloutPercent 哈希分桶选版本；`GET /app/releases/{vc}/apk` 支持 Range/If-Range/ETag | `ReleaseService.kt`、`ReleaseRoutes.kt` |
| 下载 | WorkManager + `.part` 断点续传（If-Range 用 sha256 ETag），完成后校验 size、sha256 | `ApkDownloader.kt`、`UpdateDownloadWorker.kt` |
| 校验 | 包名 == 自己；archive versionCode == 期望；APK 证书集合 == 已安装证书集合，且服务器给的 signingCertSha256 ∈ 集合 | `UpdateDownloadWorker.kt`、`UpdateLogic.checkCertificates` |
| 安装 | Shizuku 已授权 → `pm install -r -S`（stdin）+ `am start`；否则 PackageInstaller session（API31+ 请求 USER_ACTION_NOT_REQUIRED，API34+ 标注 PACKAGE_SOURCE_DOWNLOADED_FILE） | `ApkInstaller.kt` |
| 强更 | `force = current < newest.minSupportedVersionCode`，弹不可关闭对话框，响应缓存跨重启 | `UpdatePolicy.decide`、`UpdateRepository.kt`、`UpdateDialog.kt` |

**基线已经挡住的最重要的一件事**：App 只接受与自身签名证书集合完全一致的 APK（Android 系统本身也强制这一点）。所以“服务器被黑后推一个随便签名的恶意 APK”这条路在基线里已经走不通。v2 要解决的是剩下的、更隐蔽的问题（见 §1）。

---

## 1. 威胁模型

资产：两台手机上的 App（能读聊天/位置等私密数据）；APK 签名密钥（keystore）；发布密钥（本 PR 新增）；后端 + 管理员账号；Cloudflare Tunnel。

| # | 攻击者 / 场景 | 能做什么 | 基线 | v2 对策 | 剩余风险 |
|---|---|---|---|---|---|
| T1 | 后端或 admin 账号被攻破 / Cloudflare 边缘 MITM（CF 终止 TLS，理论上能改内容） | 改 `/app/update` 响应：换 APK、换 sha256/size/notes、伪造 `force`、改 minSupported | APK 证书比对挡住**外来签名**的 APK【已完成】 | **签名清单**：App 只认被离线/CI Ed25519 发布密钥签过的 `release.json`，sha256/size/versionCode/证书全部以清单为准【本 PR 实现】 | 攻击者仍能**扣住**更新（freeze）或把已签名的旧版本当“最新”重放 → T2；可伪造 `force`/`reason` 造成打扰，但只能强更到**合法签名**的版本 |
| T2 | 降级 / 重放旧的有漏洞版本 | 把旧但合法签名的 release 重新发布或在响应里重放 | Android 拒绝安装 versionCode 更低的包（`pm install -r` 无 `-d`）【已完成，系统保证】；服务器只按 `> current` 选版本【已完成】 | App 记录 **highest-seen versionCode**（已安装过的 + 验签通过的清单里见过的最高值），低于它的一律拒绝；服务器维护“已发布最高水位”，拒绝发布更低版本【本 PR 实现】 | 首次安装/清除数据后 highest-seen 丢失（回到系统保证的“不低于已安装”）；freeze 攻击无法从客户端检测（没有可信时间源），靠伴侣交叉检查（§4 peer 信息）部分缓解 |
| T3 | APK 签名 keystore 泄漏（开发机备份、旧电脑） | 签出“合法”APK | 无防护：证书比对会放行 | 发布密钥与 keystore **分离**；只有 keystore 没有发布密钥签不出能被 App 接受的清单【本 PR 实现】 | 两把钥匙都在 GitHub Actions secrets 里：**GitHub 账号/CI 被攻破 = 两把都丢**。建议把 `OTA_SIGNING_KEY` 放进受保护的 GitHub Environment（required reviewer = 自己）【缺口：需用户在 GitHub 设置】 |
| T4 | 发布密钥泄漏 | 签任意清单，但 APK 仍需 keystore 签名 | — | 同时需要 T3 才能推送恶意代码；轮换方案：发一个新版本把新公钥编译进去（App 只信编译期 pin 的公钥），旧公钥随旧版本退役 | 轮换窗口内旧版本仍信旧公钥。没有在线吊销（两台设备，可接受） |
| T5 | 坏版本（不是攻击，是 bug） | 崩溃 / 连不上 MQTT，两人都失联 | 只能手动 WITHDRAWN | 分阶段：先我后伴侣 + 安装后健康上报 + 失败自动暂停【本 PR 实现】；前向修复（§7） | 若我的手机本身也挂了，靠 admin web 手动暂停 |
| T6 | 两人版本不兼容 | 一方升级后协议不通 | 无 | protocolVersion / minPeerProtocol 闸门 + peer 版本上报【本 PR 实现】 | 一方长期不开 App 时服务器只有旧的上报 |
| T7 | 本机其他 App 篡改下载文件 | — | APK 存在 App 私有目录 `filesDir/updates`，安装前再验证【已完成】 | — | Shizuku 授权给了其他恶意 App 时，它本来就能以 shell 身份装任何东西（超出范围） |

**不做的事**（刻意）：TUF 全套角色/时间戳元数据、在线吊销、证书透明。两台设备，成本收益不划算；freeze 风险在文档里明示即可。

---

## 2. 签名发布清单（release manifest）【本 PR 实现】

### 2.1 密钥

- **发布密钥**：Ed25519，32 字节 seed，GitHub secret `OTA_SIGNING_KEY`（base64）。与 bootstrap 二维码密钥（后端 `BOOTSTRAP_PRIVATE_KEY`，在服务器上）**完全独立**：服务器被攻破不能签发布清单。
- **公钥**：GitHub 仓库 variable `OTA_PUBLIC_KEY`（base64 raw 32 字节，公开信息）。构建时通过 gradle property `couple.otaPublicKey` 或环境变量 `OTA_PUBLIC_KEY` 写进 `BuildConfig.OTA_PUBLIC_KEY`。release 构建要求必须有；debug 构建没有时跳过验签并打一行日志。
- 生成：`deploy/scripts/ota-keygen.sh`（openssl，输出 seed 和公钥，seed 不落盘）；签名：`deploy/scripts/ota-sign.sh`（CI 调用，本地也能跑）。
- 后端可选设置环境变量 `OTA_PUBLIC_KEY`：设置后镜像时也验签（纵深防御，**不是**信任根；信任根是 App 里编译期 pin 的公钥）。

### 2.2 清单格式

`release.json`（CI 生成，字段只增不改，旧字段语义不变）：

```json
{
  "schema": 2,
  "packageName": "com.qimingnan.couple",
  "versionName": "0.3.0", "versionCode": 300,
  "sha256": "<apk hex>", "size": 12345678,
  "signingCertSha256": "<hex>",
  "minSupportedVersionCode": 0,
  "protocolVersion": 1, "minPeerProtocol": 1,
  "minSdk": 26,
  "rollout": "staged",
  "notes": "...", "publishedAt": 1760000000000
}
```

`release.json.sig` = 标准 base64 的 Ed25519 签名，**签的是 release.json 文件的原始字节**（不做 JSON 规范化，避免跨语言规范化不一致这一整类 bug）。

### 2.3 传递链

1. CI：单独一个步骤持有 `OTA_SIGNING_KEY`，把 seed 拼成 PKCS#8 DER 写到 `$RUNNER_TEMP`，`openssl pkeyutl -sign -rawin` 签名，用 `OTA_PUBLIC_KEY` 回验，`if: always()` 删除密钥文件。
2. 后端镜像：下载 `release.json` 与 `release.json.sig`，**原样**存（`manifest_b64` = 原始字节 base64，`manifest_sig` = 签名原文），不再重新序列化。配置了 `OTA_PUBLIC_KEY` 时验签失败拒收（`SIGNATURE_MISSING` / `SIGNATURE_INVALID`）。
3. `/app/update`：`latest.manifest`、`latest.manifestSig` 原样返回。
4. App：验签 → 解析清单 → 要求 `packageName == 本包名（去掉 .debug 后缀）`、`versionCode/sha256/size/signingCertSha256` 与 `latest` 一致 → 下载与校验一律使用清单里的值。任何一步失败：不提示更新，消息“更新信息签名无效，已忽略”。
5. 下载后仍保留基线检查：APK 证书集合 == 已安装证书集合（**同时**满足清单签名和 APK 签名，才安装）。

---

## 3. 防降级【本 PR 实现】

| 侧 | 规则 |
|---|---|
| App | `highestSeen = max(已安装 versionCode, 历次验签通过的清单 versionCode)`，存 SharedPreferences。`latest.versionCode < highestSeen` 或 `<= 已安装` → 拒绝。只有验签通过的清单才能抬高 highestSeen（否则被黑的服务器可以塞一个超大 versionCode 把更新永久卡死）。 |
| 后端 | `server_settings['release.highWater']` = 曾经 PUBLISHED 过的最大 versionCode。PATCH 成 PUBLISHED 且 `versionCode < highWater` → 409 `DOWNGRADE_REFUSED`；autoPublish 镜像遇到更低版本只存 MIRRORED 并审计。 |
| 系统 | Android 不允许装更低 versionCode（我们的 `pm install` 不带 `-d`）。 |

取舍：撤回 v5 后不能“回退发布” v4——按 §7 的原则，回退就是发 v6。

---

## 4. 伴侣兼容（protocol gate）【本 PR 实现】

- 每个构建带两个整数：`PEER_PROTOCOL_VERSION`（我对伴侣说的协议）和 `MIN_PEER_PROTOCOL`（我能听懂的最低对端协议），定义在 `app/gradle.properties`（`couple.protocolVersion` / `couple.minPeerProtocol`），CI 从同一处读出写进清单的 `protocolVersion` / `minPeerProtocol`。
- 注意：这是 **App↔App** 协议，与 `/health` 里的 `protocolVersion`（客户端↔服务器，`BuildConfig.PROTOCOL_VERSION`）是两回事，互不影响。
- 兼容 ⇔ `A.protocol >= B.minPeer && B.protocol >= A.minPeer`。
- **上报**：复用 `GET /app/update`（启动/登录时本来就调用），加参数 `protocolVersion`、`minPeerProtocol`、`sdkInt`；健康上报（§5）也会刷新。服务器以 **token 里的 did** 记到 `device_reports`（不信任 query 里的 deviceId，防止替别人上报）。
- **响应**多出 `reason` 与 `peer`：
  - `peer = {versionCode, protocolVersion, minPeerProtocol, reportedAt, compatible}`（伴侣最近一次上报的设备；未配对或从未上报为 null）。
  - `reason = "MIN_SUPPORTED"`：低于最低支持版本 → 强更。
  - `reason = "PEER_PROTOCOL"`：**伴侣已经升级到我听不懂的协议**（`self.protocol < peer.minPeer`）→ 强更到满足 `protocolVersion >= peer.minPeer` 的最新已发布版本（忽略灰度/阶段，暂停的除外）。只有协议真的断了才强更。
  - `reason = "PEER_NEWER"`：伴侣版本比我新且我有可用更新 → 普通提示“对方已更新到 x”。
- **服务器阻止跟不上的强更**：PATCH 发布（或抬高 minSupportedVersionCode）时，如果某台近 30 天上报过的设备会被强更（`versionCode < minSupported` 或 `protocol < release.minPeerProtocol`），但它的 `sdkInt < release.minSdk`（装不上）→ 409 `PEER_CANNOT_FOLLOW`，body 列出设备；`overridePeerCheck: true` 可强行发布（审计）。
- App 端协议常量升级规则写进 contract：破坏性协议变更必须 **先发一个能同时说新旧协议的版本**（`minPeerProtocol` 不变），等两台都装上，再发提高 `minPeerProtocol` 的版本（expand → contract，和数据库迁移同一个思路）。

---

## 5. 分阶段灰度 + 健康上报 + 自动暂停【本 PR 实现】

- 两台设备，百分比灰度没有意义。保留 `rolloutPercent`（兼容），新增 **stage**：`CANARY | ALL`。
  - 清单 `rollout: "staged"` → 镜像后 stage = CANARY；`"all"` 或旧清单 → ALL。
  - `ReleaseSettings.canaryDeviceIds`（admin 设置，一般就是我的手机 deviceId）。CANARY 阶段只对 canary 设备可见。
- **健康上报** `POST /app/health-report {versionCode, fromVersionCode, status: OK|CRASH|FAILED, mqttConnected, detail, protocolVersion, minPeerProtocol, sdkInt}`：
  - App 在安装前写下 `pendingHealth = {from, to}`；新版本首次启动后，**第一次 MQTT 连接成功** → 上报 OK。
  - 新版本进程未捕获异常 → 写崩溃标记，下次启动上报 CRASH（带异常类名，不带堆栈里的私密内容）。
  - 新版本启动 10 分钟仍未连上 MQTT → 上报 FAILED（`mqttConnected=false`）。
  - 安装失败（PackageInstaller/Shizuku 返回失败）不上报为版本问题，只在本机提示。
- **自动暂停**：收到某版本的 CRASH/FAILED 且该版本 PUBLISHED、未暂停 → `paused_at`、`pause_reason` 落库，审计 `release.auto_paused`。暂停的版本对所有设备不可见（包括强更）。admin 在发布列表看到 pausedAt/pauseReason，PATCH `{paused:false}` 恢复。
- **自动晋级**：CANARY 版本收到 canary 设备的 OK 且该版本没有任何失败上报 → stage = ALL，审计 `release.promoted`，伴侣下次检查就能看到（带 `PEER_NEWER` 提示）。
- **“通知 admin”**：本 PR = 审计日志 + 发布列表状态；【缺口】主动推送（Server 酱 / Bark / 邮件 webhook，或者通过 MQTT 给 admin 设备发一条）。
- 【缺口】“下载了但一直没回报”的超时判定（需要定时任务）；当前靠“没有 OK 就不晋级”兜底——不报不坏，但不会扩散。

---

## 6. 安装体验与策略

| 主题 | 设计 | 状态 |
|---|---|---|
| 断点续传 | `.part` + Range + If-Range(sha256 ETag)；ETag 不匹配从头下；WorkManager 重试 5 次指数退避 | 【已完成】 |
| 网络/充电/空闲 | 用户点“立即更新”：任意网络（CONNECTED）。后台预下载（规划）：仅 UNMETERED + 电量不低；计费网络（流量）下只提示不下载；强更忽略偏好但在流量网络下显示包大小让用户确认 | 前台【已完成】；后台预下载与偏好开关【缺口】 |
| Shizuku 静默 | `pm install -r -S` 经 Shizuku（shell uid）。**注意**：没带 `-i <本包名>`，installer of record 会变成 shell，之后不走 Shizuku 时 PackageInstaller 的 USER_ACTION_NOT_REQUIRED 失效 → 每次都弹确认 | 【已完成】；`-i` 修正【缺口，小】 |
| MIUI / HyperOS | shell 安装受开发者选项 “USB 安装” 约束（关闭时返回 `INSTALL_FAILED_USER_RESTRICTED`，开启往往要求登录小米账号/插 SIM）；HyperOS“纯净模式”会拦截未知来源安装，需要用户在系统里放行；系统安装器会插入自家“安全检测”页面，USER_ACTION_NOT_REQUIRED 基本不生效 | 机型行为来自社区报告，**需实机验证**（§8 矩阵） |
| ColorOS / OxygenOS / realme | 未知来源安装可能要求输入锁屏/账号密码（“安装验证”）；shell 安装可能弹“通过 USB 安装”确认；部分版本有“禁止权限监控”开关影响 shell | 同上，需实机验证 |
| 其它（vivo OriginOS、华为/荣耀） | vivo 有“安装检测”与 USB 安装确认；华为/荣耀“纯净模式”默认拦截 | 同上 |
| PackageInstaller 回退 | 无 REQUEST_INSTALL_PACKAGES 授权 → 打开“安装未知应用”设置页，回到 App 自动继续 | 【已完成】 |
| Android 14+ 更新归属 | session 上 `setRequestUpdateOwnership(true)` + 权限 `ENFORCE_UPDATE_OWNERSHIP`：之后别的安装器（应用商店、文件管理器）想覆盖安装要用户额外确认，防止 ROM 商店“替你更新”成同名异签包（异签本来就装不上，主要防止同签旧包被别处覆盖） | 【本 PR 实现】（一行 + 一条权限） |
| 首次安装 | 第一次是浏览器/文件管理器装的，installer of record 不是我们 → 第一次自更新一定弹确认，之后才可能免确认 | 写进用户说明【已完成于本文】 |

---

## 7. 回滚的现实

- Android 不能在不卸载的情况下降级（卸载 = 丢本地数据），所以**没有客户端回滚**。策略：
  1. **向后兼容**：新版本必须能读旧数据、能和旧协议的伴侣通信（§4 expand→contract）。Room 迁移同样只增不删。
  2. **服务器开关**：后端 `/admin/config` 已有 configVersion 下发机制；功能开关挂在服务器配置上，坏功能远程关掉。【缺口：通用 feature flag 字段】
  3. **暂停扩散**：auto-pause 保证坏版本最多坏一台（canary）。【本 PR 实现】
  4. **前向修复**：hotfix 走 vX.Y.Z+1，正常 tag 流程，必要时 `minSupportedVersionCode` 强推。服务器端高水位保证不会“回退发布”。
  5. **最后手段**：手动卸载重装旧版（会丢数据），只在 App 完全打不开时用。
- 后端本身的回滚已有：server-updater 健康检查失败回退上一镜像 + expand-only 迁移【已完成】。

---

## 8. 证据与测试矩阵

### 8.1 自动化（本 PR 带的测试）

| 层 | 用例 | 文件 |
|---|---|---|
| 后端 JUnit | 签名清单：带签名镜像成功且字节级原样返回；配置公钥后缺签名/坏签名/重新序列化拒收；OpenSSL 脚本产出的测试向量能被 JDK 验证 | `OtaV2Test` |
| 后端 | 防降级：高水位以下 PATCH PUBLISHED → 409 | `OtaV2Test` |
| 后端 | 设备上报 + peer：`/app/update` 返回 peer；伴侣协议更高 → `PEER_PROTOCOL` 强更；伴侣版本新 → `PEER_NEWER` | `OtaV2Test` |
| 后端 | PEER_CANNOT_FOLLOW：minSdk 高于设备 sdkInt 的强更被拒，override 放行 | `OtaV2Test` |
| 后端 | 健康上报：CRASH → 自动暂停，暂停版本不再下发，admin 恢复/手动暂停；canary OK → 晋级 ALL，非 canary 的 OK 不晋级；没配 canary 时 staged 直接 ALL | `OtaV2Test` |
| App JVM | 清单验签（正确/篡改/错钥/字段不一致/未 pin 跳过） | `ManifestVerifierTest` |
| App | 防降级 highestSeen 规则 | `UpdateLogicTest` |
| App | 健康上报状态机（pending→WATCH、崩溃标记→CRASH、未安装/过期/已装更新→清理；崩溃详情不含消息文本） | `HealthLogicTest` |
| App | OpenSSL 测试向量能被 BouncyCastle 验证（CI 签、App 验，跨实现一致） | `ManifestVerifierTest` |
| CI | 签名脚本自检：签完立刻用 `OTA_PUBLIC_KEY` 回验，不匹配则发布失败 | `release.yml` |

### 8.2 手工 / 实机（发布前一次，ROM 大版本升级后再跑）

| 场景 | 我的手机 | 伴侣手机 | 期望 |
|---|---|---|---|
| Shizuku 静默安装 | ✔ | ✔ | 无弹窗，App 自动重启，健康 OK |
| 无 Shizuku，首次自更新 | ✔ | ✔ | 弹系统确认（MIUI/ColorOS 记录额外页面/密码要求） |
| 无 Shizuku，第二次自更新 | ✔ | ✔ | 记录是否免确认（USER_ACTION_NOT_REQUIRED 在该 ROM 是否生效） |
| 下载中切飞行模式/杀进程 | ✔ | | 恢复后从断点续传，sha256 正确 |
| 篡改服务器返回的 sha256 / manifest | ✔ | | 不提示更新，日志“签名无效” |
| canary 崩溃（故意 crash 构建） | ✔ | | 版本自动暂停，伴侣看不到；审计有 `release.auto_paused` |
| 协议断代（minPeerProtocol+1） | 先升 | 后升 | 伴侣收到不可关闭的强更，理由“对方已升级，需要更新才能继续通信” |
| 发布更低版本 | admin | | 409 DOWNGRADE_REFUSED |

每次实机结果记到 `docs/ota-device-matrix.md`（品牌、ROM 版本、Android 版本、结果、截图）。【缺口：首轮实机数据待填】

---

## 9. 本 PR 之后剩下的缺口（按价值排序）

1. GitHub Environment 保护 `OTA_SIGNING_KEY`（required reviewer）——用户操作，零代码。
2. Shizuku `pm install -i <pkg>` 保持 installer of record。
3. 主动通知 admin（Bark/Server 酱 webhook）。
4. 后台预下载 + Wi-Fi/充电偏好。
5. 健康上报超时的服务端定时判定。
6. 通用 feature flag。
7. 实机矩阵首轮数据。
8. 发布公钥轮换演练（文档化步骤：新版本内置新旧两把公钥 → 下个版本移除旧钥）。
