# Couple 路线图（P0 → P5）

> 本文档是仓库的正式阶段定义。admin 侧边栏（`AdminLayout.vue`）与各 README 中引用的 P1–P5 以此为准。
> 状态标记：【已完成】 / 【进行中】 / 【待做】。更新于 2026-10-26。
>
> 接口契约见 [`../contract.md`](../contract.md)（权威）；OTA 设计与威胁模型见 [`ota-design.md`](ota-design.md)。

## 阶段总览

| 阶段 | 名称 | 状态 | 内容 |
|---|---|---|---|
| P0 | 基础设施 | 【已完成】 | 认证/配对/引导（扫码 TOFU）/管理后台/MQTT 在线状态/后台保活 |
| P0.5 | OTA 自动更新 | 【已完成】 | GitHub Release 镜像、签名 manifest、灰度/canary/自动暂停、健康上报、防降级 |
| P1 | 动态 + TA + 聊天 | 【进行中】 | 媒体存储、动态 Feed（点赞+评论）、TA 页、文本聊天、WxPusher 候补推送 |
| P2 | 媒体消息 + 插件系统 | 【待做】 | 聊天图片/语音/表情包、插件框架与首批插件、桌面小部件 |
| P3 | （并入 P1/P2，保留编号） | — | — |
| P4 | 通话 | 【待做】 | WebRTC 语音/视频、TURN 凭证、通话统计 |
| P5 | 主题与布局 | 【待做】 | 服务端布局下发与渲染、主题编辑器、布局编辑器 |

P3 编号保留不使用（历史上曾被 admin 侧边栏预留），避免与既有引用冲突。

---

## P0 基础基础设施【已完成】

- 后端（Ktor）：注册/登录/刷新轮换+家族吊销、配对码、解绑双确认、Ed25519 引导二维码、审计日志、内置 Moquette/外接 EMQX 双模式 MQTT、首启 setup 流程。
- App（Compose）：扫码引导（TOFU 公钥固定、30 秒回滚）、确认页、登录注册、配对、在线状态、「我的」页全部功能、OTA 自更新（Shizuku 静默安装）、后台保活诊断。
- Admin（Vue）：仪表盘、服务器配置、引导二维码、审计日志、发布管理（镜像/同步/发布/撤回/灰度比例/强更版本）。
- 部署：Windows 原生三服务（backend/updater/cloudflared）+ 推送即部署 + 健康检查自动回滚；Linux+Docker 备选。

## P0.5 OTA 自动更新【已完成】

签名清单（Ed25519）、防降级高水位、canary 分阶段发布、健康上报自动暂停与晋升、伴侣协议门槛（PEER_PROTOCOL/PEER_NEWER）。设计见 [`ota-design.md`](ota-design.md)。

## P1 动态 + TA + 聊天【进行中】

实施顺序（`feat/complete-p1` 分支，全部完成后合 `main`）：

1. **媒体存储层【已完成】**：`StorageService` 三提供方（local/MinIO/R2，全私有桶），上传端点（类型/大小白名单、EXIF/GPS 剥离、Bearer 代理下载仅同 couple 可读）、头像上传。
2. **动态 Feed【已完成】**：帖子（文字+图片）、点赞、评论、`before&limit` 分页、`couple/{cid}/feed` MQTT 推送；App 时间线/发布页/压缩上传 worker；feedHeader 纪念日倒数卡；空状态引导。
3. **server→client MQTT 推送基建【已完成】**：embedded 走 `internalPublish`，external 模式 EMQX HTTP publish 客户端（`EMQX_API_URL/KEY/SECRET`，缺失仅禁用推送）——聊天/插件/通话共用。ACL 已收紧：客户端只可发布 `presence`，`feed`/`chat` 仅可订阅。
4. **App 通知子系统【已完成】**：通知渠道、优先级、免打扰时段——聊天/插件/来电复用。未读角标随 P2 聊天媒体/桌面小部件一起做。
5. **TA 页【已完成】**：资料卡、重要日子、`privacy_toggle` 字段可见性控制（taCards 网格）。
6. **聊天 P1a【进行中】**：文本消息、引用回复、120 秒撤回、已读回执、离线 outbox（WorkManager）、`couple/{cid}/chat` MQTT 推送、App Room 缓存与上滑分页、DemoBackend 自动回复已完成（后端 V7）。待做：`couple/{cid}/typing` 话题（正在输入）、配对成功/解绑申请改 MQTT 推送（替代轮询与口头告知）、聊天记录导出、双机 e2e 脚本。
7. **WxPusher 候补推送【待做】**：App 被杀时微信提醒（仅「你有新消息」级别、不带正文、去抖聚合）；用户绑定 UID；同一通道兼作服务器运维告警。

## P2 媒体消息 + 插件系统【待做】

1. **聊天媒体消息**：image/voice（opus）/emoji 类型、缩略图、表情包管理、`chatToolbar` 的 emoji/voice 转正（新增 `RECORD_AUDIO`）。
2. **插件系统**（第一方注册制，明确不做第三方动态加载）：
   - manifest `{id, name, icon, allowedZones, entry, privacy}`，服务端下发启用状态/配置/排序；
   - 卡片位 = `feedHeader` / `taCards` / `meSections`（对应 App `LayoutSpec` 既有 zone）；
   - 数据各用类型化表（expand-only 迁移），不搞通用 JSON 大仓；
   - 添加入口：卡片区末尾「+ 添加插件」卡片 + 「我的 → 插件管理」（启停/拖动排序/逐个配置）；
   - 插件卡片统一走 `CoupleTokens`，不允许自带颜色，深浅色自动适配。
3. **首批插件**：纪念日（yearly 循环倒数）、记账（条目/分类/谁付的/月度统计，是否结算/AA 契约定）、经期记录（默认仅本人可见、可选共享状态、本地提醒）。
4. **桌面小部件**（Glance）：纪念日倒数、对方在线状态。
5. Admin 插件页：启用状态/使用统计/默认配置。

## P4 通话【待做】

- MQTT 信令 `couple/{cid}/call`（offer/answer/ICE/挂断）+ 状态机；
- TURN 临时凭证端点（默认 Cloudflare TURN；Windows 主机无法自建 coturn）；ICE 直连优先、TURN 兜底；
- 通话 UI（`callControls`：接听/挂断/静音）、来电通知 + 前台服务、通话记录表；
- 未接来电 WxPusher 提醒；Admin 通话统计页。

## P5 主题与布局【待做】

- 后端 layout 配置端点（与 App `LayoutValidator` 同一套规则）、布局作为配置存取；
- App 服务端布局渲染（从只消费 bottomBar 扩展到全部 zone，插件卡片按布局落位）；
- Admin 主题编辑器（`CoupleTokens` 参数化配色）+ 布局编辑器（zone 内拖放/增删，锁定项不可动）。

## 跨阶段约定

- **契约先行**：每个功能先在 `contract.md` 写权威章节再写代码；客户端↔服务端 `protocolVersion` 保持 1（增量变更不动既有字段）。伴侣协议（`app/gradle.properties` 的 `couple.protocolVersion`）破坏性变更需分两版发布（见 ota-design.md §4）。
- **Flyway 只扩不缩**（当前 V7，新增从 V8 起）。
- **可达性策略**：常驻前台服务 + MQTT QoS1 为主路，WxPusher 微信为候补（App 被杀场景）；不接 FCM（国内无 GMS）与厂商推送 SDK。
- **隐私底线**：媒体仅同 couple 可读（Bearer 代理，不用公开 URL）；上传剥离 EXIF/GPS；经第三方的推送不透传消息正文。
- **不做清单**：第三方插件动态加载、FCM、Web/桌面端；应用锁与截图屏蔽记 backlog（本轮不做）。
- **部署节奏**：P1–P5 全部工作在长分支完成、测试全绿后一次性合 `main`（合入即触发服务器自动部署），随后打 tag 走正式发版。

## 遗留决策点（不阻塞开发）

| 决策 | 需要时间 |
|---|---|
| App 最终名称 + 图标 | P5 收尾前 |
| 记账是否要结算/AA | P2 契约定稿时 |
| WxPusher 应用注册（APP_TOKEN）+ 两人 UID | P1 聊天联调前 |
| TURN 提供方确认 | P4（默认 Cloudflare） |
