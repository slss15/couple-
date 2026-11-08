# Couple 路线图（P0 → P5）

> 本文档是仓库的正式阶段定义。admin 侧边栏（`AdminLayout.vue`）与各 README 中引用的 P1–P5 以此为准。
> 状态标记：【已完成】 / 【进行中】 / 【待做】。更新于 2026-11-06（对齐 PR #5–#19 合并后的实际代码）。
>
> 接口契约见 [`../contract.md`](../contract.md)（权威）；OTA 设计与威胁模型见 [`ota-design.md`](ota-design.md)。

## 阶段总览

| 阶段 | 名称 | 状态 | 内容 |
|---|---|---|---|
| P0 | 基础设施 | 【已完成】 | 认证/配对/引导（扫码 TOFU）/管理后台/MQTT 在线状态/后台保活 |
| P0.5 | OTA 自动更新 | 【已完成】 | 签名 manifest、灰度/canary/自动暂停、防降级、国内镜像直连、包瘦身 |
| P1 | 动态 + TA + 聊天 | 【已完成】 | 媒体存储、动态 Feed、TA 页、聊天（文本/图片/表情包/定时消息/导出）、WxPusher |
| P2 | 插件系统 | 【进行中】 | 插件宿主、纪念日（农历）、记账（向导+独立 Tab+账户页）已上线；经期/小部件/Admin 插件页待做 |
| P3 | （并入 P1/P2，保留编号） | — | — |
| P4 | 通话 | 【待做】 | WebRTC 语音/视频、TURN 凭证、通话统计 |
| P5 | 主题与布局 | 【进行中】 | App 端主题设置已上线；服务端布局下发/编辑器待做 |

P3 编号保留不使用（历史上曾被 admin 侧边栏预留），避免与既有引用冲突。

> 当前基线：后端 Flyway **V10**（新增从 V11 起）；App Room **v6**（插件各自带表：Anniversary v5 / Ledger v6）。

---

## P0 基础设施【已完成】

- 后端（Ktor）：注册/登录/刷新轮换+家族吊销、配对码、解绑双确认、Ed25519 引导二维码、审计日志、内置 Moquette 0.18.1/外接 EMQX 双模式 MQTT（遗嘱消息走 ACL）、首启 setup 流程。
- App（Compose）：扫码引导（TOFU 公钥固定、30 秒回滚）、确认页、登录注册、配对、在线状态、「我的」页全部功能、OTA 自更新（Shizuku 静默安装 + `-i` installer of record）、后台保活诊断。
- Admin（Vue）：仪表盘、服务器配置、引导二维码、审计日志、发布管理（镜像/同步/发布/撤回/灰度/canary/暂停）。
- 部署：Windows 原生三服务 + 推送即部署 + 健康检查自动回滚 + 每日备份（backup.ps1）+ cloudflared 掉线自动重启；Linux+Docker 备选。

## P0.5 OTA 自动更新【已完成】

签名清单（Ed25519）、防降级高水位、canary 分阶段发布、健康上报自动暂停与晋升、伴侣协议门槛。国内网络：GitHub 直连更新检查 + gh-proxy 镜像候选、APK 下载镜像 + 断点续传超时重试、R8 包瘦身。设计见 [`ota-design.md`](ota-design.md)（§9 缺口已全部关闭）。

## P1 动态 + TA + 聊天【已完成】

1. **媒体存储层**：StorageService 三提供方（local/MinIO/R2，私有桶 + 最小 SigV4）、上传（白名单/EXIF 剥离/缩略图）、Bearer 代理下载、头像上传。
2. **动态 Feed**：帖子/点赞/评论/分页/MQTT 推送；时间线/发布页/离线 outbox；feedHeader 纪念日卡；空状态引导。
3. **server→client MQTT 推送基建**：embedded internalPublish + EMQX HTTP publish；ACL 读写分离（客户端只可发布 `presence`/`typing`，`feed`/`chat`/`events` 仅订阅；`user/{uid}/events` 仅本人）。
4. **通知子系统**：feed/chat/calls 渠道、免打扰时段、前台可见性；WxPusher 微信候补推送（App 被杀场景，绑定与开关在设置页）。
5. **TA 页**：资料卡、重要日子（V6 important_days）、生日隐私开关（服务端过滤）。
6. **聊天**：文本/图片/表情包（type image/sticker，表情包搜索代理）、正在输入、逐条已读回执、120 秒撤回、离线 outbox、定时消息（V9 规则表 + 30 秒调度 + 解绑清理）、聊天记录导出 JSON/TXT（V10）。**聊天入口在「我的」**（已从底栏移除，PR #16）。
7. **配对/解绑 MQTT 事件**：配对码页轮询降为 15 秒兜底，解绑申请弹确认框。

## P2 插件系统【进行中】

已上线（第一方内置，不做动态加载；数据隔离契约见 `data/plugin/Plugin.kt`——插件不碰 token/MQTT/聊天表，各自拥有带前缀的 Room 表与 DataStore 键，离线 outbox 模式同步就绪）：

1. **插件宿主**：PluginRegistry + 设置页启停开关 + 隐藏 Tab/首页 Tab 设置（PR #10、#15）。
2. **纪念日插件**（COUPLE scope，Room v5）：阳历+农历、倒数列表（PR #11）。
3. **记账插件**（PERSONAL scope，Room v6）：账户/支出/收入/转账/月预算、引导式向导、独立底栏 Tab（stats/record/accounts 子页）、账户资产分布与详情（PR #12、#17–#19）。

待做：

1. **经期记录插件**（PERSONAL，默认仅本人可见、可选共享状态、本地提醒）。
2. **桌面小部件**（Glance）：纪念日倒数、对方在线状态、记账快捷记一笔。
3. **Admin 插件页**：启用状态/使用统计（插件目前为 App 本地数据，无服务端表——若要统计需先定上报契约）。
4. **聊天语音消息**（`RECORD_AUDIO` + opus，P2 遗留项）。
5. **双机 e2e 脚本**：reliability-test.sh 扩展聊天收发验证。

## P4 通话【待做】

- MQTT 信令 `couple/{cid}/call`（offer/answer/ICE/挂断）+ 状态机；
- TURN 临时凭证端点（默认 Cloudflare TURN；Windows 主机无法自建 coturn）；ICE 直连优先、TURN 兜底；
- 通话 UI（`callControls`：接听/挂断/静音）、来电通知 + 前台服务、通话记录表；
- 未接来电 WxPusher 提醒；Admin 通话统计页。

## P5 主题与布局【进行中】

已完成（App 端）：设置页主题色、深浅色、字号；linepuppy 主题（PR #16）。

待做：

- 后端 layout 配置端点（与 App `LayoutValidator` 同一套规则）+ 布局存取；
- App 服务端布局渲染（插件 Tab 的显隐/排序已有本地设置，可演进为服务端下发）；
- Admin 主题编辑器 + 布局编辑器。

## 跨阶段约定

- **契约先行**：功能先在 `contract.md` 写权威章节再写代码；`protocolVersion` 保持 1（增量变更不动既有字段）。伴侣协议破坏性变更分两版发布（ota-design.md §4）。
- **Flyway 只扩不缩**（当前 V10，新增从 V11 起）；App Room 各插件只追加自己的表与版本。
- **可达性策略**：常驻前台服务 + MQTT QoS1 为主路，WxPusher 微信为候补；不接 FCM 与厂商推送 SDK。
- **隐私底线**：媒体仅同 couple 可读（Bearer 代理）；上传剥离 EXIF/GPS；WxPusher 不透传消息正文。
- **不做清单**：第三方插件动态加载、FCM、Web/桌面端；应用锁与截图屏蔽记 backlog。
- **部署节奏**：日常走 feat/* 分支 + PR（CI 全绿后合并，合 main 即自动部署）；阶段性打 tag 走正式发版。

## 遗留决策点（不阻塞开发）

| 决策 | 需要时间 |
|---|---|
| 通话是否启动（P4 是剩余最大块） | 随时 |
| 经期插件的数据共享粒度（仅状态 vs 完整日志） | 开发前 |
| 桌面小部件优先级（纪念日/在线状态/记账快捷） | 开发前 |
| App 最终名称 + 图标 | 发版前 |
| TURN 提供方确认（默认 Cloudflare） | 通话启动前 |
