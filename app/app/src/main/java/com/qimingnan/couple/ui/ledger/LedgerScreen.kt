package com.qimingnan.couple.ui.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qimingnan.couple.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.ledger.AmountExpression
import com.qimingnan.couple.data.ledger.Money
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.data.ledger.LedgerLogic
import com.qimingnan.couple.data.ledger.LoanRecord
import com.qimingnan.couple.data.settings.GlassStyle
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.GlyphAvatar
import com.qimingnan.couple.ui.components.LedgerEntryRow
import com.qimingnan.couple.ui.components.MonthSwitcher
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.LongPressReorderableColumn
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.settings.SettingsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class LedgerSubTab(val label: String) {
    Stats("统计"),
    Detail("明细"),
    Accounts("账户"),
}

/**
 * 顶部两段切换（统计 / 账户）。记一笔不再是二级页，改成贴底的抽屉，
 * 双击底栏「记账」图标直接打开；切换二级页有三种方式——点这里、
 * 长按底栏「记账」图标后左右滑、在页面内容上左右滑。
 */
@Composable
private fun LedgerSegmented(current: LedgerSubTab, onChange: (LedgerSubTab) -> Unit) {
    val t = LocalCoupleTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = t.spacing.lg, vertical = t.spacing.sm)
            .clip(t.shapes.pill).background(t.colors.surfaceContainer)
            .padding(t.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        LedgerSubTab.entries.forEach { sub ->
            val selected = sub == current
            Surface(
                onClick = { if (!selected) onChange(sub) },
                shape = t.shapes.pill,
                // 选中态按主题分开：
                // 浅色下用 surface 白底（安静，不和下面的结余大卡抢）；
                // 深色下 surface(#1A1316) 比轨道 surfaceContainer(#261D20) 更暗，白底会变成
                // 「凹进去的洞」、文字对比度也只有 1.0，所以深色改用 primaryContainer 这一对。
                // 浅色下用 35% 的 primaryContainer 淡染：纯白底压奶油轨道实测只有 1.10:1，
                // 看不出选中；35% 淡染后是 1.36:1，且 onPrimaryContainer 文字仍有 7.9:1。
                // 深色下 surface 比轨道更暗（会变成"凹进去的洞"），直接用容器色。
                color = if (!selected) Color.Transparent
                else if (t.isDark) t.colors.primaryContainer else t.colors.primaryContainer.copy(alpha = 0.35f),
                contentColor = if (selected) t.colors.onPrimaryContainer else t.colors.onSurfaceVariant,
                border = null,
                // 浅色下白底压奶油轨道只有 1.10~1.13:1，光靠底色差和发丝边撑不住"选中"，
                // 加一层 2dp 投影把块真正抬起来（深色下选中块本来就有 1.2+，阴影不影响）。
                shadowElevation = if (selected) 2.dp else 0.dp,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = t.spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sub.label, style = t.typography.label)
                }
            }
        }
    }
}

/**
 * Ordinary personal ledger screen (PR4), Stitch visual pass.
 *
 * Personal and private: the plugin has its own entries, budgets and accounts.
 * Money is entered in yuan and converted to cents with
 * BigDecimal, never floating point. A transfer moves money between accounts
 * and is neither income nor expense. A negative balance after an entry only
 * warns, it does not block. There is no couple logic here: no joint vault,
 * no AA split, no payer, no sync to TA — the Stitch "couple ledger" screens
 * were used for layout only, and every joint/dual label was replaced with
 * its single-person equivalent (my budget, my accounts, my spending).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    subTab: LedgerSubTab = LedgerSubTab.Stats,
    /** 顶部两段切换回传：记账是普通底栏 Tab 后，二级页改由页面自己切。 */
    onSubTabChange: (LedgerSubTab) -> Unit = {},
    /** 已经在第一个/最后一个二级页还大幅左/右滑时，切到相邻底栏 Tab（-1 / +1）。 */
    onSwipeToTab: (Int) -> Unit = {},
    autoOpenWizard: Boolean = false,
    onWizardDismissed: () -> Unit = {},
) {
    val t = LocalCoupleTokens.current
    val scrollState = rememberScrollState()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val loans by vm.loans.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var showQuick by remember { mutableStateOf(false) }
    // 首页「记一笔」直达向导第 1 步：MainShell 把这个信号置真时打开弹窗。
    LaunchedEffect(autoOpenWizard) { if (autoOpenWizard) showQuick = true }
    // 每分钟对一次表：跨天/跨月后统计页和账目列表要跟着走，否则新账目落在新月份却看不见。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            vm.syncMonthToToday()
        }
    }
    var showAccounts by remember { mutableStateOf(false) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    LaunchedEffect(subTab) { if (subTab != LedgerSubTab.Accounts) selectedAccountId = null }
    androidx.activity.compose.BackHandler(enabled = selectedAccountId != null) { selectedAccountId = null }
    // 账户按设置里保存的顺序排，没排过的按添加顺序跟在后面。
    val orderedAccounts = remember(accounts, settings.accountOrder) {
        val order = settings.accountOrder
        accounts.sortedBy { a -> order.indexOf(a.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    val accountById = remember(accounts) { accounts.associateBy { it.id } }
    var selectedEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    // 点一行账目弹详情弹窗（看/改/删）：放在账户详情全屏 early return 之前，
    // 记账子页和账户详情页点开的行都能弹出来。
    selectedEntry?.let { e ->
        EntryDetailDialog(entry = e, vm = vm, accountById = accountById, onDismiss = { selectedEntry = null })
    }

    // 账户详情全屏呈现（自带唯一返回键）：此前它叠在记账页头下面，出现双返回键，
    // 外层大标题与间距又在详情上方留出一大块空白。详情现在占满整屏、可滚动，适配小屏。
    if (subTab == LedgerSubTab.Accounts) {
        accounts.firstOrNull { it.id == selectedAccountId }?.let { detail ->
            Column(
                modifier.fillMaxSize().background(t.colors.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = t.spacing.lg, vertical = t.spacing.md),
                verticalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                if (detail.type.isLoan) {
                    LoanAccountDetail(vm, detail, accounts.filterNot { it.type.isLoan }, loans, entries, { selectedAccountId = null })
                } else {
                    AccountDetail(vm = vm, account = detail, entries = entries, onBack = { selectedAccountId = null }, onEntryClick = { selectedEntry = it })
                }
            }
            return
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(
                // 有返回键时让图标左边缘落在内容的 16dp 上；没有返回键时标题自己对齐 16dp。
                start = if (showBack) t.spacing.xs else t.spacing.lg,
                end = t.spacing.lg,
                top = t.spacing.sm,
                bottom = t.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.colors.onSurface) }
            // 标题用 weight(fill = false)：Row 先量非加权子项（两个胶囊），标题拿剩下的宽度。
            // 这样把字号调到 1.3x（或系统字体再放大）时，是标题变省略号，而不是把胶囊挤出屏幕。
            Text(
                "记账",
                style = t.typography.headline,
                color = t.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(t.spacing.sm))
            // 底栏质感快捷开关：设置 → 底栏质感里也有，但藏得深。这里一键在
            // 毛玻璃 / 液态玻璃之间切，切完立刻能看见底栏的变化。
            val nextGlass = if (settings.glassStyle == GlassStyle.Liquid) GlassStyle.Frosted else GlassStyle.Liquid
            Surface(
                onClick = { settingsVm.setGlassStyle(nextGlass) },
                modifier = Modifier.minimumInteractiveComponentSize(),
                shape = t.shapes.pill,
                // 实底而不是「透明 + 描边」：整圈 outline 在两个主题下都像画了个框，没有材质感。
                color = t.colors.surfaceContainer,
                contentColor = t.colors.onSurfaceVariant,
            ) {
                Text(
                    settings.glassStyle.label,
                    style = t.typography.caption,
                    modifier = Modifier.padding(horizontal = t.spacing.sm, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(t.spacing.xs))
            // 记一笔是抽屉：这里留一个常驻入口，双击底栏「记账」图标也能直接打开。
            // 描边而不是实心 primaryContainer：顶部两段切换的选中态已经是实心强调色，
            // 两个橙色块上下挨着会互相抢，主次反而分不出来。
            Surface(
                onClick = { showQuick = true },
                modifier = Modifier.minimumInteractiveComponentSize(),
                shape = t.shapes.pill,
                color = t.colors.primaryContainer,
                contentColor = t.colors.onPrimaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("记一笔", style = t.typography.label)
                }
            }
        }
        LedgerSegmented(subTab, onSubTabChange)
        // 三种切换方式：点顶部、长按底栏「记账」图标后左右滑、在页面内容上左右滑。
        Box(
            Modifier.fillMaxSize().ledgerSwipe(
                index = subTab.ordinal,
                count = LedgerSubTab.entries.size,
                onMove = { onSubTabChange(LedgerSubTab.entries[it]) },
                onSwitchTab = onSwipeToTab,
            ),
        ) {
            Column(
                // 底栏浮在内容上，末尾留出它的高度，否则最后一项被压在栏下面。
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(t.spacing.lg)
                    .padding(bottom = com.qimingnan.couple.ui.components.LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
            ) {
                operationError?.let { Text(it, color = t.colors.error) }
                when (subTab) {
                    LedgerSubTab.Stats -> {
                        MonthSwitcher(
                            label = "${month.year}年${month.monthValue}月",
                            onPrev = { vm.setMonth(month.minusMonths(1)) },
                            onNext = { vm.setMonth(month.plusMonths(1)) },
                        )
                        BalanceHero(totals.netCents, totals.incomeCents, totals.expenseCents)
                        BudgetCard(
                            spentCents = totals.expenseCents,
                            budgetCents = settings.monthlyBudgetCents,
                            onSaveBudget = { settingsVm.setMonthlyBudget(it) },
                        )
                        TrendCard(entries, month)
                        // 分类聚合是 O(n log n) 的分组+排序，不能每次重组都算一遍
                        // （Compose 官方最佳实践第一条就是「用 remember 缓存开销大的计算」）。
                        val slices = remember(entries, month) { categorySlices(entries, month) }
                        CategoryBreakdownCard("支出分类", slices.expense, totals.expenseCents, "本月还没有支出。")
                        CategoryBreakdownCard("收入分类", slices.income, totals.incomeCents, "本月还没有收入。")
                    }
                    LedgerSubTab.Detail -> {
                        MonthSwitcher(
                            label = "${month.year}年${month.monthValue}月",
                            onPrev = { vm.setMonth(month.minusMonths(1)) },
                            onNext = { vm.setMonth(month.plusMonths(1)) },
                        )
                        MonthSummaryStrip(totals.incomeCents, totals.expenseCents, totals.netCents)
                        MonthEntriesCard(month, entries, accountById) { selectedEntry = it }
                    }
                    LedgerSubTab.Accounts -> {
                        // 账户详情已提前全屏呈现（见函数开头），这里只剩列表。
                        val total = LedgerLogic.totalAssets(accounts, entries, loans)
                            // 和统计页的结余卡同一套底，两个二级页的大数字长得一样。
                            TintCard {
                                Text("总资产", style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
                                Text(
                                    centsText(total),
                                    style = t.typography.display.copy(letterSpacing = 0.sp),
                                    color = t.colors.onPrimaryContainer,
                                    maxLines = 1,
                                )
                                Text("${accounts.size} 个账户 · 含借出待收回、扣除借入待还", style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
                            }
                            if (accounts.isNotEmpty()) SectionCard("资金账户分布") {
                                val withBalance = accounts.filterNot { it.type.isLoan }.map { a -> a to vm.balance(a, entries) }
                                    .sortedByDescending { it.second }
                                val cashTotal = withBalance.sumOf { it.second }
                                withBalance.forEach { (a, bal) ->
                                    val share = if (cashTotal > 0) (bal.toDouble() / cashTotal.toDouble() * 100).toInt() else 0
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            AccountIcon(a.type, Modifier.size(32.dp), a.bankCode)
                                            Text(a.name, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                            if (cashTotal > 0) Text("$share%", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                            Text(centsText(bal), style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.padding(start = 8.dp))
                                        }
                                        if (cashTotal > 0) RatioBar(share / 100f)
                                    }
                                }
                            }
                            Text("我的账户", style = t.typography.title, color = t.colors.onSurface)
                            if (accounts.size > 1) Text("长按账户，浮起后拖动调整顺序，松手保存。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                            LongPressReorderableColumn(
                                items = orderedAccounts,
                                itemKey = { it.id },
                                spacing = t.spacing.md,
                                scrollState = scrollState,
                                onOrderChange = { order, onSaved -> settingsVm.saveAccountOrder(order, accounts.map { it.id }, onSaved) },
                            ) { a, _ ->
                                Surface(
                                    color = t.colors.surfaceContainer,
                                    shape = t.shapes.large,
                                    modifier = Modifier.fillMaxWidth().clickable { selectedAccountId = a.id },
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(t.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                                        AccountIcon(a.type, Modifier.size(48.dp), a.bankCode)
                                        Column(Modifier.weight(1f).padding(start = t.spacing.md)) {
                                            Text(a.name, style = t.typography.title, color = t.colors.onSurface)
                                            Text(accountTypeLabel(a.type), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                        }
                                        Text(centsText(vm.balance(a, entries)), style = t.typography.title, color = t.colors.onSurface)
                                    }
                                }
                            }
                            if (accounts.isEmpty()) Text("还没有账户，先在下方添加一个账户。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                            Button(onClick = { showAccounts = true }) { Text("添加账户") }
                            if (showAccounts) {
                                // 新建账户表单改到弹窗里（仿借贷编辑弹窗）：表单自带「保存账户」按钮，弹窗只补一个「取消」。
                                AlertDialog(
                                    onDismissRequest = { showAccounts = false },
                                    title = { Text("添加账户") },
                                    text = {
                                        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AccountForm(vm, onDone = { showAccounts = false })
                                        }
                                    },
                                    confirmButton = {},
                                    dismissButton = { TextButton(onClick = { showAccounts = false }) { Text("取消") } },
                                )
                            }
                    }
                }
                Text("个人私密账本，不会出现在动态、聊天或 TA 页。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
        }
    }
    if (showQuick) {
        LedgerEntryWizard(vm, orderedAccounts, entries, onDismiss = { showQuick = false; onWizardDismissed() })
    }
}

internal fun centsText(cents: Long): String = Money.decimal(cents) + " 元"

private fun compactMoney(cents: Long): String = Money.decimal(cents)

private fun centsTextStatic(cents: Long): String = centsText(cents)

/** 账目行的字形：分类首字，转账用“转”。 */
private fun entryGlyph(e: LedgerEntry): String = when (e.type) {
    LedgerEntryType.TRANSFER -> "转"
    else -> (e.categoryId ?: "其").firstOrNull()?.toString() ?: "其"
}

/** 统计页一行分类：名字、金额、笔数。 */
private data class CategorySlice(val name: String, val cents: Long, val count: Int)

/**
 * 大数字卡的底：surfaceContainer 再叠一层从上往下的主色淡染。
 *
 * 不直接把 primaryContainer 当渐变起点，有两个原因：① linepuppy 的 #FF793F 上
 * 12sp 小字只有 2.9:1，达不到 4.5:1；② 顶部两段切换的选中态已经是实心主色，
 * 卡片再压一块同样饱和的橙，两块会互相抢。淡染到 0.28 后小字是 8.4:1。
 */
@Composable
private fun TintCard(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalCoupleTokens.current
    Column(
        Modifier.fillMaxWidth()
            .clip(t.shapes.large)
            .background(t.colors.surfaceContainer)
            .background(
                Brush.verticalGradient(
                    listOf(t.colors.primaryContainer.copy(alpha = 0.28f), Color.Transparent),
                ),
            )
            .padding(t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
        content = content,
    )
}

/** 本月结余大卡：渐变底 + 结余大字 + 收入/支出两列 + 支出占收入的比例条。 */
@Composable
private fun BalanceHero(netCents: Long, incomeCents: Long, expenseCents: Long) {
    val t = LocalCoupleTokens.current
    val ratio = when {
        incomeCents > 0 -> (expenseCents.toFloat() / incomeCents.toFloat()).coerceIn(0f, 1f)
        expenseCents > 0 -> 1f
        else -> 0f
    }
    TintCard {
        Text("本月结余", style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
        Text(
            centsText(netCents),
            // display 自带 4sp 字距，金额位数一多会顶到卡片边缘，这里收掉字距。
            style = t.typography.display.copy(letterSpacing = 0.sp),
            color = t.colors.onPrimaryContainer,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
            Column(Modifier.weight(1f)) {
                Text("收入", style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
                Text(centsText(incomeCents), style = t.typography.title, color = t.colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text("支出", style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
                Text(centsText(expenseCents), style = t.typography.title, color = t.colors.onPrimaryContainer)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(6.dp).clip(t.shapes.pill)
                .background(t.colors.onPrimaryContainer.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(t.shapes.pill)
                    .background(t.colors.primary),
            )
        }
        Text(
            when {
                incomeCents <= 0 && expenseCents > 0 -> "这个月只有支出。"
                incomeCents <= 0 -> "这个月还没有收支。"
                expenseCents > incomeCents -> "支出已超过收入 ${centsText(expenseCents - incomeCents)}"
                else -> "支出占收入 ${(ratio * 100).toInt()}% · 还剩 ${centsText(incomeCents - expenseCents)}"
            },
            style = t.typography.caption,
            color = t.colors.onPrimaryContainer.copy(alpha = 0.86f),
        )
    }
}

/**
 * 分类占比的配色，按"相邻两色不能撞"排。
 * 不放 secondary：它和 success 都是绿的，并排出现在一条堆叠条里分不出来；
 * 也不放 error：红色放在支出分类里会被读成"超支报警"。
 */
@Composable
private fun categoryPalette(): List<Color> {
    val t = LocalCoupleTokens.current
    return listOf(
        t.colors.primary,
        t.colors.primaryContainer,
        t.colors.success,
        t.colors.warning,
        t.colors.outline,
        t.colors.secondary,
    )
}

/** 近 6 个月支出柱状图：给统计页一点纵向节奏，也顺手能看出趋势。 */
@Composable
private fun TrendCard(entries: List<LedgerEntry>, month: java.time.YearMonth) {
    val t = LocalCoupleTokens.current
    val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }
    // 六个月的支出是 6 次全表扫描，缓存住；entries 或月份变了才重算。
    val sums = remember(entries, month) {
        months.map { m ->
            entries.filter {
                it.type == LedgerEntryType.EXPENSE && it.loanAction == null &&
                    java.time.YearMonth.from(it.occurredOn) == m
            }.sumOf { it.amountCents }
        }
    }
    val max = sums.maxOrNull() ?: 0L
    if (max <= 0L) return
    SectionCard("近 6 个月支出") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            months.forEachIndexed { index, m ->
                val ratio = (sums[index].toFloat() / max.toFloat()).coerceIn(0.04f, 1f)
                val current = m == month
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(ratio)
                                .clip(t.shapes.medium)
                                .background(
                                    // 不用 primaryContainer：它和卡片底 surfaceContainer 同亮度，
                                    // 深色下柱子和底几乎同色，整张图只剩当月一根。
                                    if (current) t.colors.primary else t.colors.primary.copy(alpha = 0.45f),
                                ),
                        )
                    }
                    Text(
                        "${m.monthValue}月",
                        style = t.typography.caption,
                        color = if (current) t.colors.primary else t.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 分类占比的横向堆叠条：一眼看出钱主要花在哪几类。 */
@Composable
private fun StackedShareBar(fractions: List<Float>) {
    val t = LocalCoupleTokens.current
    val palette = categoryPalette()
    Row(
        Modifier.fillMaxWidth().height(8.dp).clip(t.shapes.pill),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fractions.forEachIndexed { index, fraction ->
            if (fraction <= 0f) return@forEachIndexed
            Box(
                Modifier.weight(fraction).fillMaxHeight().clip(t.shapes.pill)
                    .background(palette[index % palette.size]),
            )
        }
    }
}

/** 一张分类卡：堆叠占比条 + 每一类的金额、笔数、占比条。 */
@Composable
private fun CategoryBreakdownCard(
    title: String,
    slices: List<CategorySlice>,
    totalCents: Long,
    emptyText: String,
) {
    val t = LocalCoupleTokens.current
    SectionCard(title) {
        if (slices.isEmpty()) {
            Text(emptyText, style = t.typography.body, color = t.colors.onSurfaceVariant)
            return@SectionCard
        }
        StackedShareBar(slices.map { if (totalCents > 0) it.cents.toFloat() / totalCents.toFloat() else 0f })
        slices.forEachIndexed { index, slice ->
            val fraction = if (totalCents > 0) slice.cents.toFloat() / totalCents.toFloat() else 0f
            CategoryShareRow(slice.name, slice.cents, fraction, slice.count, index)
        }
    }
}

/** 统计页分类行：字形 + 大类名 + 笔数/占比 + 金额 + 占比条。 */
@Composable
private fun CategoryShareRow(category: String, cents: Long, fraction: Float, count: Int, index: Int) {
    val t = LocalCoupleTokens.current
    val palette = categoryPalette()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphAvatar(category.firstOrNull()?.toString() ?: "其", modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(start = t.spacing.sm)) {
                Text(category, style = t.typography.body, color = t.colors.onSurface)
                Text("$count 笔 · ${(fraction * 100).toInt()}%", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            Text(centsText(cents), style = t.typography.body, color = t.colors.onSurface)
        }
        Row(
            // 轨道不能用 surfaceContainer：这张卡自己就是 surfaceContainer，会完全看不见。
            Modifier.fillMaxWidth().height(4.dp).clip(t.shapes.pill)
                .background(t.colors.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(t.shapes.pill).background(palette[index % palette.size]),
            )
        }
    }
}

/** 一次遍历算出本月支出/收入分类切片；纯函数，调用方用 remember 缓存。 */
private data class MonthSlices(val expense: List<CategorySlice>, val income: List<CategorySlice>)

private fun categorySlices(entries: List<LedgerEntry>, month: java.time.YearMonth): MonthSlices {
    val monthEntries = entries.filter { java.time.YearMonth.from(it.occurredOn) == month && it.loanAction == null }
    fun slicesOf(type: LedgerEntryType) = monthEntries
        .filter { it.type == type }
        .groupBy { (it.categoryId ?: "其他").substringBefore("·") }
        .map { (cat, list) -> CategorySlice(cat, list.sumOf { it.amountCents }, list.size) }
        .sortedByDescending { it.cents }
    return MonthSlices(slicesOf(LedgerEntryType.EXPENSE), slicesOf(LedgerEntryType.INCOME))
}

/** 明细页顶部的三格汇总：收入 / 支出 / 结余，让人先看到结论再往下翻账目。 */
@Composable
private fun MonthSummaryStrip(incomeCents: Long, expenseCents: Long, netCents: Long) {
    val t = LocalCoupleTokens.current
    @Composable
    fun cell(label: String, cents: Long, accent: Boolean, modifier: Modifier) {
        Surface(
            color = t.colors.surfaceContainer,
            contentColor = if (accent) t.colors.primary else t.colors.onSurface,
            shape = t.shapes.medium,
            border = BorderStroke(1.dp, t.colors.outline.copy(alpha = 0.10f)),
            modifier = modifier,
        ) {
            Column(Modifier.padding(horizontal = t.spacing.md, vertical = t.spacing.sm)) {
                Text(label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Text(compactMoney(cents), style = t.typography.title, maxLines = 1)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        cell("收入", incomeCents, false, Modifier.weight(1f))
        cell("支出", expenseCents, false, Modifier.weight(1f))
        cell("结余", netCents, true, Modifier.weight(1f))
    }
}

/** 本月账目明细：按天分组，点一行看详情/改/删。 */
@Composable
private fun MonthEntriesCard(
    month: java.time.YearMonth,
    entries: List<LedgerEntry>,
    accountById: Map<String, LedgerAccount>,
    onEntryClick: (LedgerEntry) -> Unit,
) {
    val t = LocalCoupleTokens.current
    SectionCard("账目明细") {
        // 同上：整月账目的过滤 + 排序，缓存起来。
        val monthEntries = remember(entries, month) {
            entries.filter { java.time.YearMonth.from(it.occurredOn) == month }.sortedByDescending { it.occurredOn }
        }
        if (monthEntries.isEmpty()) {
            Text("这个月还没有账目。", style = t.typography.body, color = t.colors.onSurfaceVariant)
            return@SectionCard
        }
        val today = LocalDate.now()
        monthEntries.groupBy { it.occurredOn }.toSortedMap(compareByDescending { it }).forEach { (date, dayEntries) ->
            val header = when (date) {
                today -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
                today.minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
            // 每天给一个小计，翻明细的时候不用自己心算。
            val dayNet = dayEntries.sumOf { e ->
                when (e.type) {
                    LedgerEntryType.INCOME -> e.amountCents
                    LedgerEntryType.EXPENSE -> -e.amountCents
                    LedgerEntryType.TRANSFER -> 0L
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(header, style = t.typography.label, color = t.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    "共${dayEntries.size}笔 · 净 " + (if (dayNet >= 0) "+" else "") + compactMoney(dayNet),
                    style = t.typography.caption,
                    color = if (dayNet >= 0) t.colors.onSurfaceVariant else t.colors.onSurface,
                )
            }
            dayEntries.forEach { e ->
                LedgerEntryRow(
                    onClick = { onEntryClick(e) },
                    glyph = entryGlyph(e),
                    title = when {
                        // 借贷资金流水：categoryId 里已经写着「还钱·个人·张三」，
                        // 再套一层「支出 ·」会变成「支出 · 还钱·个人·张三」，读着别扭。
                        e.loanAction != null -> e.categoryId ?: "借贷流水"
                        e.type == LedgerEntryType.TRANSFER -> "转账"
                        e.type == LedgerEntryType.EXPENSE -> "支出 · ${e.categoryId ?: "已删除分类"}"
                        else -> "收入 · ${e.categoryId ?: "已删除分类"}"
                    },
                    subtitle = when (e.type) {
                        LedgerEntryType.TRANSFER -> "${accountById[e.fromAccountId]?.name ?: "?"} → ${accountById[e.toAccountId]?.name ?: "?"}"
                        else -> accountById[e.accountId]?.name ?: ""
                    },
                    amountText = when (e.type) {
                        LedgerEntryType.EXPENSE -> "-${compactMoney(e.amountCents)}"
                        LedgerEntryType.INCOME -> "+${compactMoney(e.amountCents)}"
                        LedgerEntryType.TRANSFER -> compactMoney(e.amountCents)
                    },
                    note = e.note,
                )
            }
        }
    }
}

/**
 * 本月个人预算：没设时可以设一个，设了就显示已用/剩余和进度条；
 * 用到 80% 提醒注意，满 100% 提醒已超。预算存在设置 DataStore 里，
 * 是单人自己的预算，不和任何人分摊。
 */
@Composable
private fun BudgetCard(spentCents: Long, budgetCents: Long, onSaveBudget: (Long) -> Unit) {
    val t = LocalCoupleTokens.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(budgetCents) {
        text = if (budgetCents > 0) BigDecimal(budgetCents).divide(BigDecimal(100)).toPlainString() else ""
    }
    SectionCard("本月个人预算") {
        if (budgetCents <= 0) {
            Text("设一个本月支出预算，超了会提醒你。", style = t.typography.body, color = t.colors.onSurfaceVariant)
            Button(onClick = { editing = true }) { Text("设置预算") }
            return@SectionCard
        }
        val progress = LedgerLogic.budgetProgress(spentCents, budgetCents) ?: 0.0
        Text("已用 ${centsText(spentCents)} / 预算 ${centsText(budgetCents)}", style = t.typography.body, color = t.colors.onSurface)
        RatioBar(progress.toFloat())
        Text(
            when {
                progress >= 1.0 -> "已超预算 ${centsText(spentCents - budgetCents)}，这个月悠着点。"
                progress >= 0.8 -> "已用 ${(progress * 100).toInt()}%，快到预算了。"
                else -> "还剩 ${centsText(budgetCents - spentCents)}，进度 ${(progress * 100).toInt()}%。"
            },
            style = t.typography.caption, color = t.colors.onSurfaceVariant,
        )
        TextButton(onClick = { editing = true }) { Text("改预算") }
    }
    // 预算金额改到弹窗里填：AmountField 的内置数字键盘（底部弹层）已在借贷编辑弹窗里这样用过。
    if (editing) {
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false; error = null },
            title = { Text(if (budgetCents > 0) "改预算" else "设置预算") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    AmountField(value = text, onValueChange = { text = it; error = null }, label = "预算（元）")
                    // 以前这里 `if (cents > 0)` 才保存，填 0 或填错都直接关弹窗，
                    // 于是预算一旦设过就永远清不掉。现在 0 = 取消预算，格式错就留在弹窗里提示。
                    Text("填 0 表示取消预算。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = text.trim()
                    val cents = if (raw.isEmpty()) 0L else Money.parseCents(raw)
                    if (cents == null) {
                        error = "金额格式不对，例如 1200 或 1200.50"
                    } else {
                        onSaveBudget(cents)
                        editing = false
                    }
                }) { Text("保存预算") }
            },
            dismissButton = { TextButton(onClick = { editing = false; error = null }) { Text("取消") } },
        )
    }
}

/** 每种账户类型一个图标：现金、储蓄卡、支付宝、微信、其他。
 * 储蓄卡选了银行就显示那家银行的 logo，否则显示统一的银行楼图标。 */
@Composable
internal fun AccountIcon(type: LedgerAccountType, modifier: Modifier = Modifier, bankCode: String? = null) {
    val t = LocalCoupleTokens.current
    // 只用核心图标库里已有的图标：现金是圆币、储蓄卡是银行楼、支付宝是付款、
    // 微信是聊天头像、其他是加号。扩展图标库不在依赖里，加它会让包变大。
    // 支付宝/微信/现金用 iconfont 项目（5251132）里的图标：支付宝、微信、零钱；
    // 字体文件打包在 res/font/iconfont.ttf，不走在线链接。
    // 储蓄卡有银行 logo 就显示银行 logo，否则显示银行楼；其他显示加号。
    val iconFont = FontFamily(Font(R.font.iconfont))
    val iconChar: String? = when (type) {
        LedgerAccountType.CASH -> "\ue600"
        LedgerAccountType.ALIPAY -> "\ue607"
        LedgerAccountType.WECHAT -> "\ue608"
        else -> null
    }
    val icon: ImageVector = when (type) {
        LedgerAccountType.BANK -> Icons.Filled.Home
        else -> Icons.Filled.Add
    }
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Bank vectors retain their original fills. Font glyphs are inherently monochrome;
        // use a neutral foreground, not a theme-coloured badge or a filter over the artwork.
        val bank = if (type == LedgerAccountType.BANK) BankCatalog.byCode(bankCode) else null
        if (type.isLoan) {
            Text(if (type == LedgerAccountType.LENT) "借出" else "借入", style = t.typography.label, color = t.colors.onSurface)
        } else if (bank != null) {
            Image(painterResource(bank.drawableRes), contentDescription = bank.name, modifier = Modifier.fillMaxSize(0.85f))
        } else if (iconChar != null) {
            Text(iconChar, fontFamily = iconFont, fontSize = 28.sp, color = t.colors.onSurface)
        } else {
            Icon(icon, contentDescription = accountTypeLabel(type), tint = t.colors.onSurface, modifier = Modifier.fillMaxSize(0.7f))
        }
    }
}

internal fun parseCents(text: String): Long = Money.parseCents(text) ?: 0L

/**
 * 账户详情：点开一个账户能看到它的账目，还能改名、改类型、改期初余额、
 * 删除账户。删除是软删除，账目本身留在明细里；有账目的账户删除前会先提示笔数。
 */
@Composable
private fun AccountDetail(
    vm: LedgerViewModel,
    account: LedgerAccount,
    entries: List<LedgerEntry>,
    onBack: () -> Unit,
    onEntryClick: (LedgerEntry) -> Unit = {},
) {
    val t = LocalCoupleTokens.current
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val myEntries = entries.filter { it.accountId == account.id || it.fromAccountId == account.id || it.toAccountId == account.id }
    val balance = vm.balance(account, entries)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回账户列表", tint = t.colors.onSurface) }
        Text("账户详情", style = t.typography.headline, color = t.colors.onSurface)
    }
    Surface(color = t.colors.surfaceContainer, shape = t.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(t.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AccountIcon(account.type, Modifier.size(56.dp), account.bankCode)
            Column(Modifier.weight(1f).padding(start = t.spacing.md)) {
                Text(account.name, style = t.typography.title, color = t.colors.onSurface)
                Text(accountTypeLabel(account.type), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Text("期初 ${centsTextStatic(account.initialBalanceCents)}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            Text(centsTextStatic(balance), style = t.typography.headline, color = t.colors.primary)
        }
    }
    Button(onClick = { editing = true }) { Text("修改信息") }
    if (editing) {
        // 修改表单改到弹窗里（仿借贷编辑弹窗）：表单自带「保存修改」按钮，弹窗只补一个「取消」。
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("修改信息") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountEditForm(vm, account, onSaved = { editing = false })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
    SectionCard("这个账户的账目（${myEntries.size} 笔）") {
        if (myEntries.isEmpty()) Text("这个账户还没有账目。", style = t.typography.body, color = t.colors.onSurfaceVariant)
        myEntries.forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { onEntryClick(e) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (e.type) {
                            LedgerEntryType.EXPENSE -> "支出 · ${e.categoryId ?: "已删除分类"}"
                            LedgerEntryType.INCOME -> "收入 · ${e.categoryId ?: "已删除分类"}"
                            LedgerEntryType.TRANSFER -> "转账"
                        },
                        style = t.typography.body, color = t.colors.onSurface,
                    )
                    Text("${e.occurredOn}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    if (e.note.isNotBlank()) Text(e.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
                Text(
                    when (e.type) {
                        LedgerEntryType.EXPENSE -> "-${centsTextStatic(e.amountCents)}"
                        LedgerEntryType.INCOME -> "+${centsTextStatic(e.amountCents)}"
                        LedgerEntryType.TRANSFER -> centsTextStatic(e.amountCents)
                    },
                    style = t.typography.body, color = t.colors.onSurface,
                )
            }
        }
    }
    Button(onClick = { confirmDelete = true }) { Text("删除账户") }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除账户？") },
            text = {
                Text(
                    if (myEntries.isEmpty()) "确定删除这个账户吗？删除后不能恢复。"
                    else "这个账户下还有 ${myEntries.size} 笔账，删除账户后这些账目仍保留在明细里。确定删除吗？",
                    style = t.typography.body, color = t.colors.onSurface,
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteAccount(account.id); onBack() }) { Text("确定删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** 银行选择：只在储蓄卡账户上出现。输名字自动认银行，手动选过之后改名字不再自动改。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankPicker(bank: BankInfo?, onPick: (BankInfo?) -> Unit) {
    val t = LocalCoupleTokens.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = bank?.name ?: "不选",
            onValueChange = {},
            readOnly = true,
            label = { Text("银行") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不选") },
                onClick = { onPick(null); expanded = false },
            )
            BankCatalog.BANKS.forEach { b ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(b.drawableRes), contentDescription = b.name, modifier = Modifier.size(24.dp))
                            Text(b.name, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.padding(start = 8.dp))
                        }
                    },
                    onClick = { onPick(b); expanded = false },
                )
            }
        }
    }
}

private fun accountTypeLabel(type: LedgerAccountType): String = when (type) {
    LedgerAccountType.CASH -> "现金"
    LedgerAccountType.BANK -> "储蓄卡"
    LedgerAccountType.ALIPAY -> "支付宝"
    LedgerAccountType.WECHAT -> "微信"
    LedgerAccountType.OTHER -> "其他"
    LedgerAccountType.LENT -> "借出账户"
    LedgerAccountType.BORROWED -> "借入账户"
}

/**
 * 账目详情弹窗：记账子页和账户详情页点一行账目弹出。看完整信息、改金额/
 * 分类/备注、删账目（删除前再弹一次确认）。转账只改金额和备注，不改分类；
 * 借贷还款记录不能在这里改（saveEntry 会拒，只能走还钱流程），只看信息、能删。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDetailDialog(
    entry: LedgerEntry,
    vm: LedgerViewModel,
    accountById: Map<String, LedgerAccount>,
    onDismiss: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    var confirmDelete by remember { mutableStateOf(false) }
    val isLoanEntry = entry.loanId != null
    // 借入/借出本金是借贷明细的一部分，单独删会被后端规则挡掉（LoanLedgerRepository.deleteEntry
    // 直接 error）。还款记录则可以删——删了会把 repaidCents 退回去。
    val isLoanPrincipal = entry.loanAction == com.qimingnan.couple.data.ledger.LoanAction.BORROW ||
        entry.loanAction == com.qimingnan.couple.data.ledger.LoanAction.LEND
    var amountText by remember(entry) { mutableStateOf(BigDecimal(entry.amountCents).divide(BigDecimal(100)).toPlainString()) }
    var note by remember(entry) { mutableStateOf(entry.note) }
    var category by remember(entry) { mutableStateOf(entry.categoryId?.substringBefore("·") ?: "") }
    var detail by remember(entry) { mutableStateOf(entry.categoryId?.substringAfter("·", "") ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val typeLabel = when (entry.type) {
        LedgerEntryType.EXPENSE -> "支出"
        LedgerEntryType.INCOME -> "收入"
        LedgerEntryType.TRANSFER -> "转账"
    }
    val accountText = when (entry.type) {
        LedgerEntryType.TRANSFER -> "${accountById[entry.fromAccountId]?.name ?: "?"} → ${accountById[entry.toAccountId]?.name ?: "?"}"
        else -> accountById[entry.accountId]?.name ?: ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账目详情") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$typeLabel · ${entry.occurredOn}", style = t.typography.body, color = t.colors.onSurface)
                if (accountText.isNotBlank()) Text("账户：$accountText", style = t.typography.body, color = t.colors.onSurfaceVariant)
                if (isLoanEntry) {
                    Text("借贷还款记录请使用还钱流程修改，这里只能查看或删除。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    Text("金额 ${centsText(entry.amountCents)}", style = t.typography.body, color = t.colors.onSurface)
                } else {
                    AmountField(value = amountText, onValueChange = { amountText = it }, label = "金额（元）")
                    if (entry.type != LedgerEntryType.TRANSFER) {
                        Text("分类", style = t.typography.label, color = t.colors.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (entry.type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE).forEach { c ->
                                FilterChip(selected = category == c, onClick = { category = c; detail = "" }, label = { Text(c) })
                            }
                        }
                        Text("详细类型", style = t.typography.label, color = t.colors.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LedgerSubcategories.forCategory(category.ifBlank { "其他" }).forEach { d ->
                                FilterChip(selected = detail == d, onClick = { detail = d }, label = { Text(d) })
                            }
                        }
                    }
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
                if (!isLoanPrincipal) {
                    TextButton(onClick = { confirmDelete = true }) { Text("删除这条账目", color = t.colors.error) }
                }
            }
        },
        confirmButton = {
            if (!isLoanEntry) {
                TextButton(onClick = {
                    val cents = Money.parseCents(amountText)
                    if (cents == null || cents <= 0) { error = "金额必须大于 0"; return@TextButton }
                    val categoryId = if (entry.type == LedgerEntryType.TRANSFER) null
                        else if (category.isBlank()) entry.categoryId
                        else if (detail.isBlank()) category else "$category·$detail"
                    vm.saveEntry(entry.copy(amountCents = cents, categoryId = categoryId, note = note)) { problem ->
                        if (problem == null) onDismiss() else error = problem
                    }
                }) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条账目？") },
            text = { Text("删除后不能恢复。", style = t.typography.body, color = t.colors.onSurface) },
            confirmButton = {
                TextButton(onClick = { vm.deleteEntry(entry.id); confirmDelete = false; onDismiss() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** 修改账户信息：改名、改类型、改期初余额，保存时用同一个 id 覆盖。 */
@Composable
private fun AccountEditForm(vm: LedgerViewModel, account: LedgerAccount, onSaved: () -> Unit) {
    val t = LocalCoupleTokens.current
    var name by remember { mutableStateOf(account.name) }
    var initial by remember { mutableStateOf(BigDecimal(account.initialBalanceCents).divide(BigDecimal(100)).toPlainString()) }
    var type by remember { mutableStateOf(account.type) }
    var bank by remember { mutableStateOf(BankCatalog.byCode(account.bankCode)) }
    var bankManual by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (!bankManual) BankCatalog.detectBank(it)?.let { found -> bank = found }
            },
            label = { Text("账户名") },
            modifier = Modifier.fillMaxWidth(),
        )
        var initialError by remember { mutableStateOf<String?>(null) }
        AmountField(value = initial, onValueChange = { initial = it; initialError = null }, label = "期初余额（元）")
        initialError?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.filterNot { it.isLoan }.forEach { ty ->
                FilterChip(selected = type == ty, onClick = { type = ty }, label = { Text(accountTypeLabel(ty)) })
            }
        }
        if (type == LedgerAccountType.BANK) {
            BankPicker(bank = bank, onPick = { bank = it; bankManual = true })
        }
        Button(onClick = {
            // 以前是 `takeIf { it != 0L } ?: account.initialBalanceCents`：想把期初余额改成 0
            // 会被静默换回旧值，填错了也一样，弹窗照常关。现在非法输入留在弹窗里提示。
            val raw = initial.trim()
            val cents = if (raw.isEmpty()) 0L else Money.parseCents(raw)
            when {
                cents == null -> initialError = "金额格式不对，例如 1200 或 1200.50"
                name.isBlank() -> initialError = "请填写账户名"
                else -> {
                    vm.saveAccount(
                        account.copy(name = name, type = type, initialBalanceCents = cents, bankCode = if (type == LedgerAccountType.BANK) bank?.code else null),
                    )
                    onSaved()
                }
            }
        }) { Text("保存修改") }
    }
}

/**
 * 金额输入框：点一下拉起内置数字键盘（贴屏幕底部的底部弹层，像系统键盘一样），
 * 不拉系统键盘。文字输入仍用系统键盘。算式与结果预览在键盘面板顶部，一直看得见。
 * 模块内共享：纪念日旅行计划的目标金额、借贷金额、预算、期初余额都用它。
 * AmountField 与分步向导复用同一个 AmountKeypad。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var showKeypad by remember { mutableStateOf(false) }
    val t = LocalCoupleTokens.current
    Column(modifier) {
        // enabled=false：readOnly 的 TextField 自己会消费点击（光标/焦点），外层 clickable 永远收不到，
        // 内置键盘就拉不起来。禁用后点击穿透到 clickable；disabled 配色保持与可输入态一致。
        OutlinedTextField(
            value = value,
            onValueChange = {},
            enabled = false,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { showKeypad = true },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = t.colors.onSurface,
                disabledBorderColor = t.colors.onSurfaceVariant,
                disabledLabelColor = t.colors.onSurfaceVariant,
                disabledContainerColor = t.colors.surface,
            ),
        )
    }
    if (showKeypad) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showKeypad = false },
            containerColor = t.colors.surfaceContainer,
        ) {
            Column(Modifier.padding(horizontal = t.spacing.md)) {
                AmountKeypad(value = value, onValueChange = onValueChange, onDone = { showKeypad = false })
                Spacer(Modifier.height(t.spacing.md))
            }
        }
    }
}

/**
 * 内置数字键盘：1–9、0、小数点、删除、C 清空、确认，外加 ÷ × − + 四个运算符，
 * 可以先算式再得出金额（例如 32+10×2）。最多两位小数，只有一个小数点
 * （每段数字各算各的）；算式不对、除数为 0 时不提交，只提示。
 * 点「确认」才把算好的金额写回输入框；点外面收起键盘不影响已填的数字。
 */
@Composable
internal fun AmountKeypad(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    confirmLabel: String = "确认",
    requirePositive: Boolean = false,
) {
    val t = LocalCoupleTokens.current
    var expr by remember(value) { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    val evaluated = remember(expr) { AmountExpression.evaluate(expr) }

    fun pressDigit(d: Char) {
        error = null
        if (expr == "0") {
            // 前导 0 不隐式插入小数点：0 后按 5 是 5；0.5 必须显式按小数点。
            if (d == '0') return
            expr = d.toString()
            return
        }
        if (expr.isEmpty() && d == '0') {
            expr = "0"
            return
        }
        if (AmountExpression.canAppendDigit(expr, d)) expr += d
    }
    fun pressDot() {
        error = null
        if (!AmountExpression.canAppendDot(expr)) return
        expr = when {
            expr.isEmpty() -> "0."
            expr.last() in "+-×÷" -> expr + "0."
            else -> expr + "."
        }
    }
    fun pressOperator(op: Char) {
        error = null
        if (expr.isEmpty()) return
        expr = if (expr.last() in "+-×÷") expr.dropLast(1) + op else expr + op
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 正在输的算式 + 算好的结果预览。
        Text(
            if (expr.isBlank()) "0" else expr,
            style = t.typography.headline,
            color = t.colors.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        if (evaluated != null && expr.any { it in "+-×÷" }) {
            Text("＝ ${evaluated.toPlainString()}", style = t.typography.body, color = t.colors.primary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
        val digitColors = ButtonDefaults.buttonColors(containerColor = t.colors.surfaceContainer, contentColor = t.colors.onSurface)
        val opColors = ButtonDefaults.buttonColors(containerColor = t.colors.surfaceVariant, contentColor = t.colors.onSurface)
        @Composable
        fun Key(label: String, colors: androidx.compose.material3.ButtonColors = digitColors, weight: Float = 1f, onClick: () -> Unit) {
            Button(onClick = onClick, colors = colors, shape = t.shapes.large, modifier = Modifier.weight(weight).height(52.dp)) {
                Text(label, style = t.typography.title)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("1") { pressDigit('1') }
            Key("2") { pressDigit('2') }
            Key("3") { pressDigit('3') }
            Key("÷", onClick = { pressOperator('÷') }, colors = opColors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("4") { pressDigit('4') }
            Key("5") { pressDigit('5') }
            Key("6") { pressDigit('6') }
            Key("×", onClick = { pressOperator('×') }, colors = opColors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("7") { pressDigit('7') }
            Key("8") { pressDigit('8') }
            Key("9") { pressDigit('9') }
            Key("−", onClick = { pressOperator('-') }, colors = opColors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(".") { pressDot() }
            Key("0") { pressDigit('0') }
            Key("删除") { expr = expr.dropLast(1); error = null }
            Key("+", onClick = { pressOperator('+') }, colors = opColors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("C 清空", onClick = { expr = ""; error = null })
            Button(
                onClick = {
                    val result = AmountExpression.evaluate(expr)
                    if (result == null) {
                        error = "算式不对，先改一下"
                        return@Button
                    }
                    if (Money.parseCents(result.toPlainString()) == null) {
                        error = "金额超过可保存范围，请调整算式"
                        return@Button
                    }
                    onValueChange(result.toPlainString())
                    error = null
                    onDone()
                },
                // 金额为 0 时下一步置灰：向导第 1 步金额必须大于 0 才能进下一步。
                enabled = !requirePositive || (evaluated != null && evaluated > BigDecimal.ZERO),
                shape = t.shapes.large,
                modifier = Modifier.weight(2f).height(52.dp),
            ) { Text(confirmLabel, style = t.typography.title) }
        }
    }
}

/** 新建账户：输名字自动认银行（没手动选过的时候），手动选过之后改名字不再自动改。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AccountForm(vm: LedgerViewModel, onDone: () -> Unit = {}, allowLoanTypes: Boolean = true) {
    val t = LocalCoupleTokens.current
    val accountId = remember { UUID.randomUUID().toString() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var initial by remember { mutableStateOf("0") }
    var type by remember { mutableStateOf(LedgerAccountType.CASH) }
    var bank by remember { mutableStateOf<BankInfo?>(null) }
    var bankManual by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (!bankManual) BankCatalog.detectBank(it)?.let { found -> bank = found }
            },
            label = { Text("账户名，如 工资卡") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!type.isLoan) AmountField(value = initial, onValueChange = { initial = it }, label = "期初余额（元）")
        else Text("借贷余额由个人/平台的借款与还款明细计算。建好账户后点进账户添加明细。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.filter { allowLoanTypes || !it.isLoan }.forEach { ty ->
                FilterChip(selected = type == ty, onClick = { type = ty }, label = { Text(accountTypeLabel(ty)) })
            }
        }
        if (type == LedgerAccountType.BANK) {
            BankPicker(bank = bank, onPick = { bank = it; bankManual = true })
        }
        error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
        Button(onClick = {
            saving = true
            vm.saveAccount(
                LedgerAccount(
                    id = accountId, name = name.trim(), type = type,
                    initialBalanceCents = if (type.isLoan) 0 else parseCents(initial),
                    bankCode = if (type == LedgerAccountType.BANK) bank?.code else null,
                ),
            ) { problem -> saving = false; error = problem; if (problem == null) onDone() }
        }, enabled = !saving && name.isNotBlank() && (type.isLoan || initial.isBlank() || Money.parseCents(initial) != null)) { Text(if (saving) "保存中…" else "保存账户") }
    }
}
