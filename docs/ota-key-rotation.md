# OTA 发布密钥轮换演练手册

> 对应 [`ota-design.md`](ota-design.md) T4（发布密钥泄漏 / 计划轮换）。App 只信**编译期 pin 的公钥**
> （`BuildConfig.OTA_PUBLIC_KEY`），没有在线吊销——所以轮换 = 「让新公钥随一个新版本发出去，再切换签名钥匙」。
> 旧版本永远信旧公钥：这就是轮换窗口，窗口内两把钥匙都要当活密钥对待。

## 0. 预备知识（一次读）

- 私钥 `OTA_SIGNING_KEY` = Ed25519 seed 的 base64，只在 CI 签名步骤可见（GitHub Environment `ota-release`，建议 required reviewer）。
- 公钥 `OTA_PUBLIC_KEY` = 同一密钥的公半部分（base64 raw 32 字节），仓库 variable，构建时写进 App。
- 两把钥匙彼此独立：keystore（APK 签名）丢了是另一场灾难（见 deploy/README.md「生成签名 keystore」）；本手册只管发布清单密钥。
- 生成/签名脚本：`deploy/scripts/ota-keygen.sh`、`deploy/scripts/ota-sign.sh`。

## 1. 计划内轮换（每 12 个月，或怀疑弱随机时）

1. `deploy/scripts/ota-keygen.sh` 生成新对：`NEW_SIGNING_KEY`（进密码管理器 + 稍后填 Secret）、`NEW_PUBLIC_KEY`。
2. 发一个**带新公钥**的普通版本：
   - 仓库 variable `OTA_PUBLIC_KEY` 改成 `NEW_PUBLIC_KEY`（新构建的 App pin 新公钥）；
   - 用**旧** `OTA_SIGNING_KEY` 签这个版本的 `release.json`（此刻 CI 用的还是旧 Secret）；
   - 打 tag `vX.Y.(Z+1)` 发布。两人都升级完成前，旧版本仍信旧公钥——这正是设计。
3. 两人手机都确认升级成功（admin「设备」页看两台 versionCode ≥ 新版本）后：
   - GitHub Secret `OTA_SIGNING_KEY` 换成 `NEW_SIGNING_KEY`（Environment `ota-release` 会要求你审批一次，正好当演练）；
   - 跑一次 `release` workflow（任意已有 tag 手动触发）验证新钥签名 → App 能验签通过。
4. 把旧 seed 从密码管理器标记为「已退役」，但**不要立刻销毁**：保持一个发布周期（下次轮换后再删），用于给旧版本
   签最后一版过渡（见 §2 情形 3）。

## 2. 泄漏应急轮换（怀疑或确认私钥外泄）

分三种情形，处置速度不同：

| 情形 | 判定 | 处置 |
|---|---|---|
| 1. 仅怀疑（CI 日志误晒、密码管理器告警） | 无法证明未泄漏 | 按 §1 走计划轮换，24h 内完成 |
| 2. 确认泄漏且攻击者**没有** keystore | 可签恶意清单，但 APK 装不上（证书比对挡住） | 仍按 §1 走，但加一步：管理后台把当前 `PUBLISHED` 版本全部 WITHDRAWN，切新钥后再发布最新版 |
| 3. 确认泄漏且 keystore **同时**泄漏 | 攻击者可签完整恶意更新 | **立即**：WITHDRAWN 全部版本 → 用旧钥给一个内置新公钥的过渡版本签名并强制（tag 注释 `minSupportedVersionCode: 当前`）→ 两人立刻手动安装过渡版 → 之后 keystore 重签发（这是卸载重装级事故，另见 deploy/README.md keystore 一节） |

## 3. 演练清单（建议每半年空跑一次，不真换钥）

- [ ] 从密码管理器取出 seed，`deploy/scripts/ota-sign.sh` 手动签一份 `release.json`，用仓库里的 `OTA_PUBLIC_KEY` 回验通过
- [ ] GitHub Environment `ota-release` 的 required reviewer 仍在（不是被 GitHub 界面改版后悄悄失效）
- [ ] 手动触发一次旧 tag 的 `release` workflow 走到签名步骤（跑完取消 Publish 之前的部分没有副作用——Publish 在签名之后，手动运行时取消即可）
- [ ] 本文档的步骤与当时的 workflow 仍然对得上（workflow 改版后同步更新这里）

## 4. 已知边界（刻意不做）

- 无在线吊销：两台设备、私钥只管清单签名，CRL/OCSP 的成本不成比例；靠 §2 情形表的 WITHDRAWN + 强更组合兜底。
- 无密钥阈值签名（多钥共签）：单人操作没有第二把钥匙的持有者。
- 旧版本在轮换窗口内仍信旧公钥：文档化接受（T4 剩余风险），靠「两人都升级后才切 Secret」把窗口压到最短。
