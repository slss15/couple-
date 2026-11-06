package com.qimingnan.couple.ui.ledger

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qimingnan.couple.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.ledger.AmountExpression
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.data.ledger.LedgerLogic
import com.qimingnan.couple.data.ledger.LoanKind
import com.qimingnan.couple.data.ledger.LoanRecord
import com.qimingnan.couple.data.ledger.LoanTotals
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.GlyphAvatar
import com.qimingnan.couple.ui.components.LedgerEntryRow
import com.qimingnan.couple.ui.components.MonthSwitcher
import com.qimingnan.couple.ui.components.OptionCard
import com.qimingnan.couple.ui.components.PickedSummaryPill
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.StitchProgressCapsules
import com.qimingnan.couple.ui.settings.SettingsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class LedgerSubTab(val label: String) {
    Stats("统计"),
    Record("记账"),
    Accounts("账户"),
}

/**
 * Ordinary personal ledger screen (PR4), Stitch visual pass.
 *
 * Personal and private: only reachable from the plugin centre, never from
 * feed, chat or TA. Money is entered in yuan and converted to cents with
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
    subTab: LedgerSubTab = LedgerSubTab.Record,
    autoOpenWizard: Boolean = false,
) {
    val t = LocalCoupleTokens.current
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val loans by vm.loans.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    // 首页「记一笔」直达向导第 1 步：MainShell 把这个信号置真时打开弹窗。
    LaunchedEffect(autoOpenWizard) { if (autoOpenWizard) showQuick = true }
    var showAccounts by remember { mutableStateOf(false) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    LaunchedEffect(subTab) { if (subTab != LedgerSubTab.Accounts) selectedAccountId = null }
    // 账户按设置里保存的顺序排，没排过的按添加顺序跟在后面。
    val orderedAccounts = remember(accounts, settings.accountOrder) {
        val order = settings.accountOrder
        accounts.sortedBy { a -> order.indexOf(a.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    val accountById = remember(accounts) { accounts.associateBy { it.id } }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.colors.onSurface) }
            Text("记账", style = t.typography.headline, color = t.colors.onSurface)
            Spacer(Modifier.weight(1f))
            if (subTab == LedgerSubTab.Record) Button(onClick = { showQuick = !showQuick }) { Text(if (showQuick) "收起" else "立即记账") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            when (subTab) {
                LedgerSubTab.Record -> {
                    if (showQuick) QuickEntryDialog(vm, orderedAccounts, entries, onDismiss = { showQuick = false })
                    // 本月一眼：结余大字 + 收入/支出两个小格（单人，不分摊）。
                    SectionCard("本月 · ${month.year}年${month.monthValue}月") {
                        Text("结余 ${centsText(totals.netCents)}", style = t.typography.headline, color = t.colors.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                            Text("收入 ${centsText(totals.incomeCents)}", style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                            Text("支出 ${centsText(totals.expenseCents)}", style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                        }
                        Button(onClick = { showQuick = true }, modifier = Modifier.fillMaxWidth()) { Text("立即记账 · 快捷向导") }
                    }
                    Button(onClick = { showForm = !showForm }) { Text(if (showForm) "收起详细记账" else "详细记账 / 转账") }
                    if (showForm) EntryForm(vm, orderedAccounts)
                    SectionCard("账目") {
                        val monthEntries = entries.filter { java.time.YearMonth.from(it.occurredOn) == month }
                            .sortedByDescending { it.occurredOn }
                        if (monthEntries.isEmpty()) {
                            Text("还没有账目。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        }
                        val today = LocalDate.now()
                        monthEntries.groupBy { it.occurredOn }.toSortedMap(compareByDescending { it }).forEach { (date, dayEntries) ->
                            val header = when (date) {
                                today -> "今天 · ${date.monthValue}月${date.dayOfMonth}日"
                                today.minusDays(1) -> "昨天 · ${date.monthValue}月${date.dayOfMonth}日"
                                else -> "${date.monthValue}月${date.dayOfMonth}日"
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(header, style = t.typography.label, color = t.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("共${dayEntries.size}笔", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                            }
                            dayEntries.forEach { e ->
                                LedgerEntryRow(
                                    glyph = entryGlyph(e),
                                    title = when (e.type) {
                                        LedgerEntryType.EXPENSE -> "支出 · ${e.categoryId ?: "已删除分类"}"
                                        LedgerEntryType.INCOME -> "收入 · ${e.categoryId ?: "已删除分类"}"
                                        LedgerEntryType.TRANSFER -> "转账"
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
                LedgerSubTab.Stats -> {
                    MonthSwitcher(
                        label = "${month.year}年${month.monthValue}月",
                        onPrev = { vm.setMonth(month.minusMonths(1)) },
                        onNext = { vm.setMonth(month.plusMonths(1)) },
                    )
                    SectionCard("本月汇总") {
                        Text("结余 ${centsText(totals.netCents)}", style = t.typography.headline, color = t.colors.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                            Column(Modifier.weight(1f)) {
                                Text("本月总收入", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                Text(centsText(totals.incomeCents), style = t.typography.title, color = t.colors.onSurface)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("本月总支出", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                Text(centsText(totals.expenseCents), style = t.typography.title, color = t.colors.onSurface)
                            }
                        }
                    }
                    BudgetCard(
                        spentCents = totals.expenseCents,
                        budgetCents = settings.monthlyBudgetCents,
                        onSaveBudget = { settingsVm.setMonthlyBudget(it) },
                    )
                    val monthEntries = entries.filter { java.time.YearMonth.from(it.occurredOn) == month }
                    SectionCard("支出分类") {
                        val byCat = monthEntries.filter { it.type == LedgerEntryType.EXPENSE }
                            .groupBy { (it.categoryId ?: "其他").substringBefore("·") }
                            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
                            .entries.sortedByDescending { it.value }
                        if (byCat.isEmpty()) Text("本月还没有支出。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        val totalExpense = totals.expenseCents.coerceAtLeast(1)
                        byCat.forEach { (cat, cents) ->
                            CategoryShareRow(cat, cents, cents.toFloat() / totalExpense.toFloat())
                        }
                    }
                    SectionCard("收入分类") {
                        val byCat = monthEntries.filter { it.type == LedgerEntryType.INCOME }
                            .groupBy { (it.categoryId ?: "其他").substringBefore("·") }
                            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
                            .entries.sortedByDescending { it.value }
                        if (byCat.isEmpty()) Text("本月还没有收入。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        val totalIncome = totals.incomeCents.coerceAtLeast(1)
                        byCat.forEach { (cat, cents) ->
                            CategoryShareRow(cat, cents, cents.toFloat() / totalIncome.toFloat())
                        }
                    }
                }
                LedgerSubTab.Accounts -> {
                    if (selectedAccount != null) {
                        AccountDetail(
                            vm = vm,
                            account = selectedAccount,
                            entries = entries,
                            loans = loans,
                            onBack = { selectedAccountId = null },
                        )
                    } else {
                        val total = LedgerLogic.totalAssets(accounts, entries)
                        SectionCard("总资产") {
                            Text(centsText(total), style = t.typography.headline, color = t.colors.primary)
                            Text("${accounts.size} 个账户", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                        if (accounts.isNotEmpty()) SectionCard("资产分布") {
                            val withBalance = accounts.map { a -> a to vm.balance(a, entries) }
                                .sortedByDescending { it.second }
                            withBalance.forEach { (a, bal) ->
                                val share = if (total > 0) (bal.toDouble() / total.toDouble() * 100).toInt() else 0
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        AccountIcon(a.type, Modifier.size(32.dp), a.bankCode)
                                        Text(a.name, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                        if (total > 0) Text("$share%", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                        Text(centsText(bal), style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.padding(start = 8.dp))
                                    }
                                    if (total > 0) RatioBar(share / 100f)
                                }
                            }
                        }
                        Text("我的账户", style = t.typography.title, color = t.colors.onSurface)
                        orderedAccounts.forEachIndexed { index, a ->
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { settingsVm.moveAccount(a.id, -1, accounts.map { it.id }) },
                                    enabled = index > 0,
                                ) { Text("↑ 上移") }
                                TextButton(
                                    onClick = { settingsVm.moveAccount(a.id, 1, accounts.map { it.id }) },
                                    enabled = index < orderedAccounts.lastIndex,
                                ) { Text("↓ 下移") }
                            }
                        }
                        if (accounts.isEmpty()) Text("还没有账户，先在下方添加一个账户。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        Button(onClick = { showAccounts = !showAccounts }) { Text(if (showAccounts) "收起账户" else "添加账户") }
                        if (showAccounts) AccountForm(vm)
                        LoanSection(vm, orderedAccounts, loans)
                    }
                }
            }
            Text("个人私密账本，不会出现在动态、聊天或 TA 页。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        }
    }
}

private fun centsText(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} 元"
}

/** 行内紧凑金额：¥32.00（不带“元”字，配 +/− 号用）。 */
private fun compactMoney(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    return "¥${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

private fun centsTextStatic(cents: Long): String = centsText(cents)

/** 账目行的字形：分类首字，转账用“转”。 */
private fun entryGlyph(e: LedgerEntry): String = when (e.type) {
    LedgerEntryType.TRANSFER -> "转"
    else -> (e.categoryId ?: "其").firstOrNull()?.toString() ?: "其"
}

/** 大类首字 + 一句话说明（Stitch 第 4 步大类卡片的副标题，单人版）。 */
private val CATEGORY_DESCRIPTIONS = mapOf(
    "餐饮" to "早午晚餐、外卖零食、饮料下午茶",
    "交通" to "地铁、打车、公交、加油停车",
    "购物" to "超市买菜、生活用品、服饰穿搭",
    "居住" to "房租水电、燃气宽带、家居家电",
    "娱乐" to "电影游戏、演出聚会、旅行度假",
    "医疗" to "药品、体检、就医问诊",
    "教育" to "书籍课程、考证培训",
    "其他" to "不可归类杂项",
    "工资" to "月薪、奖金",
    "兼职" to "兼职收入",
    "理财收益" to "利息、分红",
    "红包" to "红包、礼金",
    "退款" to "退款返还",
)

/** 统计页分类行：字形 + 大类名 + 金额 + 占比条。 */
@Composable
private fun CategoryShareRow(category: String, cents: Long, fraction: Float) {
    val t = LocalCoupleTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphAvatar(category.firstOrNull()?.toString() ?: "其", modifier = Modifier.size(40.dp))
            Text(category, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text("${(fraction * 100).toInt()}%", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            Text(centsText(cents), style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.padding(start = 8.dp))
        }
        RatioBar(fraction)
        Text(CATEGORY_DESCRIPTIONS[category] ?: "", style = t.typography.caption, color = t.colors.onSurfaceVariant)
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
            if (!editing) {
                Button(onClick = { editing = true }) { Text("设置预算") }
            } else {
                AmountField(value = text, onValueChange = { text = it }, label = "预算（元）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cents = runCatching { BigDecimal(text).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
                        if (cents > 0) onSaveBudget(cents)
                        editing = false
                    }) { Text("保存预算") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            }
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
        if (editing) {
            AmountField(value = text, onValueChange = { text = it }, label = "预算（元）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cents = runCatching { BigDecimal(text).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
                    if (cents > 0) onSaveBudget(cents)
                    editing = false
                }) { Text("保存预算") }
                TextButton(onClick = { editing = false }) { Text("取消") }
            }
        }
    }
}

/** 每种账户类型一个图标：现金、储蓄卡、支付宝、微信、其他。
 * 储蓄卡选了银行就显示那家银行的 logo，否则显示统一的银行楼图标。 */
@Composable
private fun AccountIcon(type: LedgerAccountType, modifier: Modifier = Modifier, bankCode: String? = null) {
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
        modifier.clip(CircleShape).background(t.colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bank = BankCatalog.byCode(bankCode)
        if (bank != null) {
            Image(painterResource(bank.drawableRes), contentDescription = bank.name, modifier = Modifier.size(28.dp))
        } else if (iconChar != null) {
            Text(iconChar, fontFamily = iconFont, fontSize = 28.sp, color = t.colors.onPrimaryContainer)
        } else {
            Icon(icon, contentDescription = accountTypeLabel(type), tint = t.colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
    }
}

private fun parseCents(text: String): Long =
    runCatching { BigDecimal(text).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)

/** 圆形图标头像（核心库图标用）：支出/收入两张大卡用。 */
@Composable
private fun IconAvatar(icon: ImageVector, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Box(
        modifier.size(48.dp).clip(CircleShape).background(t.colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
    }
}

/**
 * 账户详情：点开一个账户能看到它的账目，还能改名、改类型、改期初余额、
 * 删除账户。删除是软删除，账目本身留在明细里；有账目的账户删除前会先提示笔数。
 */
@Composable
private fun AccountDetail(
    vm: LedgerViewModel,
    account: LedgerAccount,
    entries: List<LedgerEntry>,
    loans: List<LoanRecord>,
    onBack: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val myEntries = entries.filter { it.accountId == account.id || it.fromAccountId == account.id || it.toAccountId == account.id }
    val myLoans = loans.filter { it.accountId == account.id }
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
    Button(onClick = { editing = !editing }) { Text(if (editing) "收起修改" else "修改信息") }
    if (editing) AccountEditForm(vm, account, onSaved = { editing = false })
    if (myLoans.isNotEmpty()) SectionCard("这个账户的借贷") {
        myLoans.forEach { loan ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${loan.kind.label} · ${loan.label.ifBlank { "未写用途" }}", style = t.typography.body, color = t.colors.onSurface)
                    Text("已还 ${centsText(loan.repaidCents)} · 还剩 ${centsText(loan.remainingCents)}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
                Text(centsText(loan.amountCents), style = t.typography.body, color = t.colors.onSurface)
            }
        }
    }
    SectionCard("这个账户的账目（${myEntries.size} 笔）") {
        if (myEntries.isEmpty()) Text("这个账户还没有账目。", style = t.typography.body, color = t.colors.onSurfaceVariant)
        myEntries.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
    if (!confirmDelete) {
        Button(onClick = { confirmDelete = true }) { Text("删除账户") }
    } else {
        Text(
            if (myEntries.isEmpty()) "确定删除这个账户吗？删除后不能恢复。"
            else "这个账户下还有 ${myEntries.size} 笔账，删除账户后这些账目仍保留在明细里。确定删除吗？",
            style = t.typography.body, color = t.colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.deleteAccount(account.id); onBack() }) { Text("确定删除") }
            Button(onClick = { confirmDelete = false }) { Text("取消") }
        }
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
        AmountField(value = initial, onValueChange = { initial = it }, label = "期初余额（元）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.forEach { ty ->
                FilterChip(selected = type == ty, onClick = { type = ty }, label = { Text(accountTypeLabel(ty)) })
            }
        }
        if (type == LedgerAccountType.BANK) {
            BankPicker(bank = bank, onPick = { bank = it; bankManual = true })
        }
        Button(onClick = {
            val cents = parseCents(initial).takeIf { it != 0L } ?: account.initialBalanceCents
            if (name.isNotBlank()) vm.saveAccount(
                account.copy(name = name, type = type, initialBalanceCents = cents, bankCode = if (type == LedgerAccountType.BANK) bank?.code else null),
            )
            onSaved()
        }) { Text("保存修改") }
    }
}

/**
 * 金额输入框：点一下拉起内置数字键盘，不拉系统键盘。文字输入不用这个，
 * 仍用系统键盘。键盘在输入框下面展开，正在输的数字一直看得见。
 * 模块内共享：纪念日旅行计划的目标金额也用它。
 * AmountKeypad 仍是本文件私有，AmountField 是唯一的对外入口。
 */
@Composable
fun AmountField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var showKeypad by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { showKeypad = !showKeypad },
        )
        if (showKeypad) AmountKeypad(value = value, onValueChange = onValueChange, onDone = { showKeypad = false })
    }
}

/**
 * 内置数字键盘：1–9、0、小数点、删除、C 清空、确认，外加 ÷ × − + 四个运算符，
 * 可以先算式再得出金额（例如 32+10×2）。最多两位小数，只有一个小数点
 * （每段数字各算各的）；算式不对、除数为 0 时不提交，只提示。
 * 点「确认」才把算好的金额写回输入框；点外面收起键盘不影响已填的数字。
 */
@Composable
private fun AmountKeypad(
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
            // 开头不能连续输 0：输 0 再输 5 得到 0.5，不是 05。
            if (d == '0') return
            expr = "0.$d"
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
private fun AccountForm(vm: LedgerViewModel, onDone: () -> Unit = {}) {
    val t = LocalCoupleTokens.current
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
        AmountField(value = initial, onValueChange = { initial = it }, label = "期初余额（元）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.forEach { ty ->
                FilterChip(selected = type == ty, onClick = { type = ty }, label = { Text(accountTypeLabel(ty)) })
            }
        }
        if (type == LedgerAccountType.BANK) {
            BankPicker(bank = bank, onPick = { bank = it; bankManual = true })
        }
        Button(onClick = {
            if (name.isNotBlank()) vm.saveAccount(
                LedgerAccount(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    type = type,
                    initialBalanceCents = parseCents(initial),
                    bankCode = if (type == LedgerAccountType.BANK) bank?.code else null,
                ),
            )
            onDone()
        }) { Text("保存账户") }
    }
}

/**
 * 记一笔弹窗向导：一共 6 步，每步只问一件事。
 * 金额 → 支出或收入 → 选账户 → 一级分类 → 细分子类 → 确认。
 * 点选项直接进下一步，第 2 步之后有“上一步”可回改；改上游（类型/大类）时
 * 下游（大类/详细）自动清掉；点取消、点弹窗外面、按返回键都是关掉不保存；
 * 只有第 6 步点「记账」才写库，分类存成“大类·详细类型”（如 餐饮·午饭）。
 */
@Composable
private fun QuickEntryDialog(
    vm: LedgerViewModel,
    accounts: List<LedgerAccount>,
    entries: List<LedgerEntry>,
    onDismiss: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    var step by remember { mutableStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<LedgerEntryType?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val amountCents = parseCents(amount)
    val titles = listOf("金额", "支出或收入", "选账户", "一级分类", "细分子类", "确认")
    val picked = buildString {
        if (amountCents > 0) append(centsText(amountCents))
        type?.let {
            if (isNotEmpty()) append(" · ")
            append(if (it == LedgerEntryType.EXPENSE) "支出" else "收入")
        }
        accountId?.let { id ->
            accounts.firstOrNull { a -> a.id == id }?.let {
                if (isNotEmpty()) append(" · ")
                append(it.name)
            }
        }
        category?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
        detail?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = t.colors.surface, shape = t.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                Text("记一笔", style = t.typography.headline, color = t.colors.onSurface)
                Text("第 ${step + 1} 步 / 共 6 步", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                StitchProgressCapsules(step = step, total = 6)
                if (picked.isNotEmpty()) PickedSummaryPill(picked)
                Text(titles[step], style = t.typography.title, color = t.colors.onSurface)
                when (step) {
                    0 -> {
                        Text(
                            if (amountCents > 0) centsText(amountCents) else "¥0.00",
                            style = t.typography.display,
                            color = t.colors.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("10" to BigDecimal(10), "50" to BigDecimal(50), "100" to BigDecimal(100)).forEach { (label, delta) ->
                                OutlinedButton(onClick = { amount = AmountExpression.addAmount(amount, delta) }) { Text("+$label") }
                            }
                            OutlinedButton(onClick = { amount = AmountExpression.roundUpToHundred(amount) }) { Text("整百凑整") }
                        }
                        AmountKeypad(
                            value = amount,
                            onValueChange = { amount = it },
                            onDone = { if (parseCents(amount) > 0) step = 1 },
                            confirmLabel = "下一步 ›",
                            requirePositive = true,
                        )
                    }
                    1 -> {
                        OptionCard(
                            title = "支出",
                            subtitle = "花出去的钱",
                            selected = type == LedgerEntryType.EXPENSE,
                            onClick = { type = LedgerEntryType.EXPENSE; category = null; detail = null; step = 2 },
                            leading = { IconAvatar(Icons.Filled.ShoppingCart) },
                        )
                        OptionCard(
                            title = "收入",
                            subtitle = "进来的钱",
                            selected = type == LedgerEntryType.INCOME,
                            onClick = { type = LedgerEntryType.INCOME; category = null; detail = null; step = 2 },
                            leading = { IconAvatar(Icons.Filled.AddCircle) },
                        )
                    }
                    2 -> {
                        if (accounts.isEmpty()) {
                            Text("还没有账户，先去账户页建一个账户再来记账。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        }
                        accounts.forEach { a ->
                            OptionCard(
                                title = a.name,
                                subtitle = "余额 ${centsText(vm.balance(a, entries))}",
                                selected = accountId == a.id,
                                onClick = { accountId = a.id; step = 3 },
                                leading = { AccountIcon(a.type, Modifier.size(48.dp), a.bankCode) },
                            )
                        }
                    }
                    3 -> {
                        val cats = if (type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE
                        cats.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { cat ->
                                    Surface(
                                        color = t.colors.surfaceContainer,
                                        shape = t.shapes.large,
                                        modifier = Modifier.weight(1f).clickable { category = cat; detail = null; step = 4 },
                                    ) {
                                        Column(Modifier.padding(t.spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            GlyphAvatar(cat.first().toString())
                                            Text(cat, style = t.typography.title, color = t.colors.onSurface)
                                            Text(CATEGORY_DESCRIPTIONS[cat] ?: "", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    4 -> {
                        val subs = LedgerSubcategories.forCategory(category ?: "")
                        if (subs.isEmpty()) {
                            Text("这个大类没有细分子类。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        }
                        subs.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { sub ->
                                    Surface(
                                        color = t.colors.surfaceContainer,
                                        shape = t.shapes.large,
                                        modifier = Modifier.weight(1f).clickable { detail = sub; step = 5 },
                                    ) {
                                        Column(Modifier.padding(t.spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            GlyphAvatar(sub.first().toString())
                                            Text(sub, style = t.typography.title, color = t.colors.onSurface)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    5 -> {
                        Surface(color = t.colors.surfaceContainer, shape = t.shapes.large, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(centsText(amountCents), style = t.typography.display, color = t.colors.primary)
                                val acc = accounts.firstOrNull { it.id == accountId }
                                Text("账户：${acc?.name ?: "未选"}", style = t.typography.body, color = t.colors.onSurface)
                                Text(
                                    "用途：${if (type == LedgerEntryType.EXPENSE) "支出" else "收入"} · $category · $detail",
                                    style = t.typography.body, color = t.colors.onSurface,
                                )
                                Text("日期：今天（${LocalDate.now()}）", style = t.typography.body, color = t.colors.onSurface)
                            }
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("备注（选填）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val t2 = type ?: return@Button
                                vm.saveEntry(
                                    LedgerEntry(
                                        id = UUID.randomUUID().toString(),
                                        ownerId = "",
                                        accountId = accountId,
                                        type = t2,
                                        categoryId = "$category·$detail",
                                        amountCents = amountCents,
                                        fromAccountId = null,
                                        toAccountId = null,
                                        occurredOn = LocalDate.now(),
                                        note = note,
                                    ),
                                )
                                onDismiss()
                            },
                            enabled = amountCents > 0 && accountId != null && category != null && detail != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("记账") }
                    }
                }
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) { Text("‹ 上一步") }
                }
            }
        }
    }
}

/**
 * 详细记账 / 转账：完整表单。转账只在两个账户之间移钱，不计入收支，
 * 不影响总资产，也不能选分类。备注选填，存下来随账目显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryForm(vm: LedgerViewModel, accounts: List<LedgerAccount>, onDone: () -> Unit = {}) {
    val t = LocalCoupleTokens.current
    var kind by remember { mutableStateOf(LedgerEntryType.EXPENSE) }
    var amount by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var fromId by remember { mutableStateOf<String?>(null) }
    var toId by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LedgerEntryType.EXPENSE, LedgerEntryType.INCOME, LedgerEntryType.TRANSFER).forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { kind = k },
                    label = { Text(when (k) { LedgerEntryType.EXPENSE -> "支出"; LedgerEntryType.INCOME -> "收入"; LedgerEntryType.TRANSFER -> "转账" }) },
                )
            }
        }
        AmountField(value = amount, onValueChange = { amount = it }, label = "金额（元）")
        if (kind == LedgerEntryType.TRANSFER) {
            Text("从哪个账户转出", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    FilterChip(selected = fromId == a.id, onClick = { fromId = a.id }, label = { Text(a.name) })
                }
            }
            Text("转到哪个账户", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    FilterChip(selected = toId == a.id, onClick = { toId = a.id }, label = { Text(a.name) })
                }
            }
        } else {
            Text("用哪个账户", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.name) })
                }
            }
            Text("一级分类", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (kind == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE).forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c; detail = null }, label = { Text(c) })
                }
            }
            if (category != null) {
                Text("二级分类", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerSubcategories.forCategory(category!!).forEach { s ->
                        FilterChip(selected = detail == s, onClick = { detail = s }, label = { Text(s) })
                    }
                }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("备注（选填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val amountCents = parseCents(amount)
        val valid = amountCents > 0 && when (kind) {
            LedgerEntryType.TRANSFER -> fromId != null && toId != null && fromId != toId
            else -> accountId != null && category != null && detail != null
        }
        Button(
            onClick = {
                vm.saveEntry(
                    LedgerEntry(
                        id = UUID.randomUUID().toString(),
                        ownerId = "",
                        accountId = if (kind == LedgerEntryType.TRANSFER) null else accountId,
                        type = kind,
                        categoryId = if (kind == LedgerEntryType.TRANSFER) null else "$category·$detail",
                        amountCents = amountCents,
                        fromAccountId = if (kind == LedgerEntryType.TRANSFER) fromId else null,
                        toAccountId = if (kind == LedgerEntryType.TRANSFER) toId else null,
                        occurredOn = LocalDate.now(),
                        note = note,
                    ),
                )
                onDone()
            },
            enabled = valid,
        ) { Text("保存") }
    }
}

/**
 * 信用与负债：借入 / 借出、已还多少、还剩多少。
 * 记账表里没有借贷字段，数据库版本这次不升，记录先存在本机设置里。
 * 还款、按对方汇总、和账目联动（还款自动记一笔账）要下一步给记账加借贷字段
 * 或单独的 loans 表才能做，那时再升数据库版本。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoanSection(vm: LedgerViewModel, accounts: List<LedgerAccount>, loans: List<LoanRecord>) {
    val t = LocalCoupleTokens.current
    val summary = LoanTotals.summarize(loans)
    var showForm by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(LoanKind.BORROWED) }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    SectionCard("信用与负债") {
        Text("借入 ${centsText(summary.borrowedCents)} · 借出 ${centsText(summary.lentCents)}", style = t.typography.body, color = t.colors.onSurface)
        Text("已还 ${centsText(summary.repaidCents)} · 还剩 ${centsText(summary.remainingCents)}", style = t.typography.body, color = t.colors.onSurface)
        Text(
            "借贷记录先存在本机设置里，不动数据库；还款自动记账、按对方汇总要下一步再做。",
            style = t.typography.caption, color = t.colors.onSurfaceVariant,
        )
        if (!showForm) {
            Button(onClick = { showForm = true }) { Text("记一笔借贷") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoanKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
            }
            AmountField(value = amount, onValueChange = { amount = it }, label = "金额（元）")
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("对方 / 用途，如 向妈妈借的") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("关联账户（选填）", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    FilterChip(selected = accountId == a.id, onClick = { accountId = if (accountId == a.id) null else a.id }, label = { Text(a.name) })
                }
            }
            val cents = parseCents(amount)
            Button(
                onClick = {
                    vm.addLoan(LoanRecord(id = UUID.randomUUID().toString(), kind = kind, accountId = accountId, amountCents = cents, label = label))
                    amount = ""; label = ""; accountId = null; showForm = false
                },
                enabled = cents > 0,
            ) { Text("保存借贷") }
        }
        loans.forEach { loan ->
            var repayOpen by remember { mutableStateOf(false) }
            var repayAmount by remember { mutableStateOf("") }
            var confirmDelete by remember { mutableStateOf(false) }
            val accName = accounts.firstOrNull { it.id == loan.accountId }?.name
            Surface(color = t.colors.surfaceContainer, shape = t.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(t.spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${loan.kind.label} · ${loan.label.ifBlank { "未写用途" }}", style = t.typography.body, color = t.colors.onSurface)
                            Text(
                                (accName?.let { "关联 $it · " } ?: "") + "已还 ${centsText(loan.repaidCents)} · 还剩 ${centsText(loan.remainingCents)}",
                                style = t.typography.caption, color = t.colors.onSurfaceVariant,
                            )
                        }
                        Text(centsText(loan.amountCents), style = t.typography.body, color = t.colors.onSurface)
                    }
                    if (loan.settled) {
                        Text("已还清", style = t.typography.caption, color = t.colors.primary)
                    } else if (!repayOpen) {
                        TextButton(onClick = { repayOpen = true }) { Text("还一点") }
                    } else {
                        AmountField(value = repayAmount, onValueChange = { repayAmount = it }, label = "还多少钱（元）")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val c = parseCents(repayAmount)
                                if (c > 0) vm.repayLoan(loan.id, c)
                                repayAmount = ""; repayOpen = false
                            }) { Text("确认还款") }
                            TextButton(onClick = { repayOpen = false }) { Text("取消") }
                        }
                    }
                    if (!confirmDelete) {
                        TextButton(onClick = { confirmDelete = true }) { Text("删除这笔") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.deleteLoan(loan.id) }) { Text("确定删除") }
                            TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}
