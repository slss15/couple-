# Couple UI/UX Demo

单文件 HTML 原型，无外部依赖，直接用浏览器打开 `index.html`。

## 页面直达

- `index.html#home`：首页
- `index.html#feed`：动态
- `index.html#ledger`：记账
- `index.html#me`：我的
- `index.html?sheet=1`：打开“记一笔”底部抽屉
- `index.html?sheet=1&step=2&amount=42`：直接查看分类步骤
- `index.html?sheet=1&step=3&amount=42`：直接查看确认步骤

## 设计取舍

- 主底栏固定为：首页 / 动态 / 记账 / 我的
- 纪念日、TA、经期放在首页快捷区
- 记账内部使用顶部三段切换，不再出现第二套底栏
- 记账页提供“快速记账”提示；双击底部「记账」Tab 直接打开记账流程
- 记一笔采用 3 步抽屉：金额/账户 → 分类/最近使用 → 确认/日期备注
- 分类带图标，细分类随一级分类切换，可“不细分”
- 备注支持文字和照片，照片最多 9 张
- 支持“保存并再记一笔”，减少连续记账的重复操作
- “我的”页先展示关系，再展示设置，技术诊断折叠
- 奶油纸感、陶土色强调、墨色文字；浅色/深色两套 token
- 动效：页面入场、Tab 切换、底部抽屉、主题切换

## 预览

- `preview-desktop.png`
- `preview-mobile-home.png`
- `preview-mobile-feed.png`
- `preview-mobile-ledger.png`
- `preview-mobile-me.png`
- `preview-mobile-sheet.png`

## Three UI Directions

- `three-directions.html`：同一套情侣 App 的三种视觉方向
- `three-directions.html#aurora`：未来玻璃 / Aurora
- `three-directions.html#brutal`：野兽派 / Brutal
- `three-directions.html#editorial`：编辑杂志 / Editorial
- `preview-three-directions.png`：三方向同屏预览


## Ledger（记账：统计 / 明细 / 账户 + 记一笔抽屉）

- `ledger-swipe.html`：记账页三段切换（统计 / 明细 / 账户），三种切换方式——点顶部、长按底栏「记账」图标后左右划、在内容上左右划
- `ledger-swipe.html#0` / `#1` / `#2`：直接打开某一页；`#sheet` 拉开记一笔抽屉；`#0liquid` 以液态玻璃渲染
- 记一笔是贴底抽屉（ModalBottomSheet），不占二级页；双击底栏「记账」图标或点右上角「记一笔」打开
- 头部「毛玻璃 / 液态玻璃」胶囊可在两种底栏质感间一键切换（App 里同位置也有，设置 → 底栏质感里也有）
- 阈值与 App 一致：切二级页 16% 宽度，已在边界时超过 40% 大幅滑动切相邻底栏
- `preview-ledger-swipe.png`（统计）/ `preview-ledger-detail.png`（明细）/ `preview-ledger-accounts.png`（账户）/ `preview-ledger-sheet.png`（抽屉）/ `preview-ledger-liquid.png`（液态玻璃）

## Calm Daily

- `calm.html`：温暖克制版，单手机日常界面
- `preview-calm-desktop.png`：桌面预览
- `preview-calm-mobile.png`：移动端预览
