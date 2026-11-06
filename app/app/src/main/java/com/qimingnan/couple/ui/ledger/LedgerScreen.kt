package com.qimingnan.couple.ui.ledger

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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.data.ledger.LedgerLogic
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import java.math.BigDecimal
import java.time.LocalDate

enum class LedgerSubTab(val label: String) {
    Stats("统计"),
    Record("记账"),
    Accounts("账户"),
}

/**
 * Ordinary personal ledger screen (PR4).
 *
 * Personal and private: this screen is only reachable from the plugin
 * centre, never from feed, chat or TA. Money is entered in yuan and
 * converted to cents with BigDecimal, never floating point. A transfer
 * moves money between accounts and is neither income nor expense.
 * A negative balance after an entry only warns, it does not block.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    subTab: LedgerSubTab = LedgerSubTab.Record,
) {
    val t = LocalCoupleTokens.current
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId }
    LaunchedEffect(subTab) { if (subTab != LedgerSubTab.Accounts) selectedAccountId = null }

    fun centsText(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} 元"
    }

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
                    if (showQuick) QuickEntryForm(vm, accounts, onSaved = { showQuick = false })
                    Button(onClick = { showForm = !showForm }) { Text(if (showForm) "收起详细记账" else "详细记账 / 转账") }
                    if (showForm) EntryForm(vm, accounts)
                    SectionCard("账目") {
                        if (entries.isEmpty()) {
                            Text("还没有账目。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        }
                        entries.filter { java.time.YearMonth.from(it.occurredOn) == month }.forEach { e ->
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
                                }
                                Text(
                                    when (e.type) {
                                        LedgerEntryType.EXPENSE -> "-${centsText(e.amountCents)}"
                                        LedgerEntryType.INCOME -> "+${centsText(e.amountCents)}"
                                        LedgerEntryType.TRANSFER -> centsText(e.amountCents)
                                    },
                                    style = t.typography.body, color = t.colors.onSurface,
                                )
                            }
                        }
                    }
                }
                LedgerSubTab.Stats -> {
                    SectionCard("本月 · ${month.year}年${month.monthValue}月") {
                        Text("结余 ${centsText(totals.netCents)}", style = t.typography.headline, color = t.colors.primary)
                        Text("收入 ${centsText(totals.incomeCents)}", style = t.typography.body, color = t.colors.onSurface)
                        Text("支出 ${centsText(totals.expenseCents)}", style = t.typography.body, color = t.colors.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                            Button(onClick = { vm.setMonth(month.minusMonths(1)) }) { Text("上月") }
                            Button(onClick = { vm.setMonth(month.plusMonths(1)) }) { Text("下月") }
                        }
                    }
                    val monthEntries = entries.filter { java.time.YearMonth.from(it.occurredOn) == month }
                    SectionCard("支出分类") {
                        val byCat = monthEntries.filter { it.type == LedgerEntryType.EXPENSE }
                            .groupBy { (it.categoryId ?: "其他").substringBefore("·") }
                            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
                            .entries.sortedByDescending { it.value }
                        if (byCat.isEmpty()) Text("本月还没有支出。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        byCat.forEach { (cat, cents) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                                Text(centsText(cents), style = t.typography.body, color = t.colors.onSurface)
                            }
                        }
                    }
                    SectionCard("收入分类") {
                        val byCat = monthEntries.filter { it.type == LedgerEntryType.INCOME }
                            .groupBy { (it.categoryId ?: "其他").substringBefore("·") }
                            .mapValues { (_, list) -> list.sumOf { it.amountCents } }
                            .entries.sortedByDescending { it.value }
                        if (byCat.isEmpty()) Text("本月还没有收入。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                        byCat.forEach { (cat, cents) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                                Text(centsText(cents), style = t.typography.body, color = t.colors.onSurface)
                            }
                        }
                    }
                }
                LedgerSubTab.Accounts -> {
                    if (selectedAccount != null) {
                        AccountDetail(
                            vm = vm,
                            account = selectedAccount,
                            entries = entries,
                            onBack = { selectedAccountId = null },
                        )
                    } else {
                        val total = LedgerLogic.totalAssets(accounts, entries)
                        SectionCard("总资产") {
                            Text(centsText(total), style = t.typography.headline, color = t.colors.primary)
                            Text("${accounts.size} 个账户", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                        if (accounts.isNotEmpty()) SectionCard("资产分布") {
                            accounts.forEach { a ->
                                val bal = vm.balance(a, entries)
                                val share = if (total > 0) (bal.toDouble() / total.toDouble() * 100).toInt() else 0
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        AccountIcon(a.type, Modifier.size(32.dp))
                                        Text(a.name, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                        Text("$share%", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                        Text(centsText(bal), style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.padding(start = 8.dp))
                                    }
                                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.colors.surface)) {
                                        Box(Modifier.fillMaxWidth((share / 100f).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(t.colors.primary))
                                    }
                                }
                            }
                        }
                        accounts.forEach { a ->
                            Surface(
                                color = t.colors.surfaceContainer,
                                shape = t.shapes.large,
                                modifier = Modifier.fillMaxWidth().clickable { selectedAccountId = a.id },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(t.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                                    AccountIcon(a.type, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f).padding(start = t.spacing.md)) {
                                        Text(a.name, style = t.typography.title, color = t.colors.onSurface)
                                        Text(accountTypeLabel(a.type), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                    }
                                    Text(centsText(vm.balance(a, entries)), style = t.typography.title, color = t.colors.onSurface)
                                }
                            }
                        }
                        if (accounts.isEmpty()) Text("还没有账户，先在下方添加一个账户。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        Button(onClick = { showAccounts = !showAccounts }) { Text(if (showAccounts) "收起账户" else "添加账户") }
                        if (showAccounts) AccountForm(vm)
                    }
                }
            }
            Text("个人私密账本，不会出现在动态、聊天或 TA 页。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        }
    }
}

/** 每种账户类型一个图标：现金、储蓄卡、支付宝、微信、其他。 */
@Composable
private fun AccountIcon(type: LedgerAccountType, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    // 只用核心图标库里已有的图标：现金是圆币、储蓄卡是银行楼、支付宝是付款、
    // 微信是聊天头像、其他是加号。扩展图标库不在依赖里，加它会让包变大。
    val icon: ImageVector = when (type) {
        LedgerAccountType.CASH -> Icons.Filled.AddCircle
        LedgerAccountType.BANK -> Icons.Filled.Home
        LedgerAccountType.ALIPAY -> Icons.Filled.Send
        LedgerAccountType.WECHAT -> Icons.Filled.Face
        LedgerAccountType.OTHER -> Icons.Filled.Add
    }
    Box(
        modifier.clip(CircleShape).background(t.colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = accountTypeLabel(type), tint = t.colors.onPrimaryContainer, modifier = Modifier.size(28.dp)) }
}

/**
 * 账户详情：点开一个账户能看到它的账目，还能改名、改类型、改期初余额、
 * 删除账户。删除是软删除，账目本身留在明细里；有账目的账户删除前会先提示笔数。
 */
@Composable
private fun AccountDetail(vm: LedgerViewModel, account: LedgerAccount, entries: List<LedgerEntry>, onBack: () -> Unit) {
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
            AccountIcon(account.type, Modifier.size(56.dp))
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

private fun centsTextStatic(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} 元"
}

/** 修改账户信息：改名、改类型、改期初余额，保存时用同一个 id 覆盖。 */
@Composable
private fun AccountEditForm(vm: LedgerViewModel, account: LedgerAccount, onSaved: () -> Unit) {
    val t = LocalCoupleTokens.current
    var name by remember { mutableStateOf(account.name) }
    var initial by remember { mutableStateOf((account.initialBalanceCents / 100).toString()) }
    var type by remember { mutableStateOf(account.type) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账户名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = initial, onValueChange = { initial = it }, label = { Text("期初余额（元）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.forEach { ty ->
                FilterChip(selected = type == ty, onClick = { type = ty }, label = { Text(accountTypeLabel(ty)) })
            }
        }
        Button(onClick = {
            val cents = runCatching { BigDecimal(initial).multiply(BigDecimal(100)).toLong() }.getOrDefault(account.initialBalanceCents)
            if (name.isNotBlank()) vm.saveAccount(account.copy(name = name, type = type, initialBalanceCents = cents))
            onSaved()
        }) { Text("保存修改") }
    }
}

private fun accountTypeLabel(type: LedgerAccountType): String = when (type) {
    LedgerAccountType.CASH -> "现金"
    LedgerAccountType.BANK -> "储蓄卡"
    LedgerAccountType.ALIPAY -> "支付宝"
    LedgerAccountType.WECHAT -> "微信"
    LedgerAccountType.OTHER -> "其他"
}

/**
 * 立即记账向导：一次只问一件事，点一个进下一步，不是长表单。
 * 顺序：金额 -> 账户 -> 一级用途（支出/收入） -> 二级用途（大类，如餐饮）
 * -> 三级用途（详细，如早饭/午饭/晚饭） -> 确认。点选项直接进下一步，
 * 上一步可以回去改；最后一步确认后才保存。转账不走向导，留给下方的详细记账。
 */
@Composable
private fun QuickEntryForm(vm: LedgerViewModel, accounts: List<LedgerAccount>, onSaved: () -> Unit) {
    val t = LocalCoupleTokens.current
    var step by remember { mutableStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(LedgerEntryType.EXPENSE) }
    var category by remember { mutableStateOf(LedgerCategories.EXPENSE.first()) }
    var detail by remember { mutableStateOf(LedgerSubcategories.forCategory(category).first()) }
    var accountId by remember { mutableStateOf<String?>(accounts.firstOrNull()?.id) }
    val categories = if (type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE
    val details = LedgerSubcategories.forCategory(category)
    val amountCents = runCatching { BigDecimal(amount).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
    val accountName = accounts.firstOrNull { it.id == accountId }?.name ?: ""
    val picked = listOf(
        if (amountCents > 0) "${amount} 元" else "",
        accountName,
        if (step > 2) if (type == LedgerEntryType.EXPENSE) "支出" else "收入" else "",
        if (step > 3) category else "",
        if (step > 4) detail else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val titles = listOf("第 1 步 · 金额", "第 2 步 · 账户", "第 3 步 · 支出还是收入", "第 4 步 · 大类", "第 5 步 · 详细类型", "第 6 步 · 确认记账")
    SectionCard("立即记账") {
        Text("第 ${step + 1} 步 / 共 6 步", style = t.typography.caption, color = t.colors.primary)
        Text(titles[step], style = t.typography.title, color = t.colors.onSurface)
        if (picked.isNotBlank()) Text(picked, style = t.typography.caption, color = t.colors.onSurfaceVariant)
        when (step) {
            0 -> {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额（元）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { if (amountCents > 0) step = 1 }, enabled = amountCents > 0) { Text("下一步") }
            }
            1 -> {
                if (accounts.isEmpty()) {
                    Text("还没有账户，先在下方添加一个账户再记账。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                } else {
                    accounts.forEach { a ->
                        Button(onClick = { accountId = a.id; step = 2 }, modifier = Modifier.fillMaxWidth()) { Text(a.name) }
                    }
                }
            }
            2 -> {
                Button(onClick = { type = LedgerEntryType.EXPENSE; category = LedgerCategories.EXPENSE.first(); detail = LedgerSubcategories.forCategory(category).first(); step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("支出") }
                Button(onClick = { type = LedgerEntryType.INCOME; category = LedgerCategories.INCOME.first(); detail = LedgerSubcategories.forCategory(category).first(); step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("收入") }
            }
            3 -> {
                categories.forEach { c ->
                    Button(onClick = { category = c; detail = LedgerSubcategories.forCategory(c).first(); step = 4 }, modifier = Modifier.fillMaxWidth()) { Text(c) }
                }
            }
            4 -> {
                details.forEach { d ->
                    Button(onClick = { detail = d; step = 5 }, modifier = Modifier.fillMaxWidth()) { Text(d) }
                }
            }
            5 -> {
                Text("金额 ${amount} 元", style = t.typography.body, color = t.colors.onSurface)
                Text("账户 $accountName", style = t.typography.body, color = t.colors.onSurface)
                Text("用途 ${if (type == LedgerEntryType.EXPENSE) "支出" else "收入"} · $category · $detail", style = t.typography.body, color = t.colors.onSurface)
                Button(onClick = {
                    if (amountCents <= 0 || accountId == null) return@Button
                    vm.saveEntry(
                        LedgerEntry(
                            id = vm.newId(), ownerId = "",
                            accountId = accountId, type = type,
                            categoryId = "$category·$detail",
                            amountCents = amountCents,
                            fromAccountId = null, toAccountId = null,
                            occurredOn = LocalDate.now(),
                        )
                    )
                    onSaved()
                }) { Text("记账") }
            }
        }
        if (step > 0) Button(onClick = { step -= 1 }) { Text("上一步") }
    }
}

@Composable
private fun AccountForm(vm: LedgerViewModel) {
    val t = LocalCoupleTokens.current
    var name by remember { mutableStateOf("") }
    var initial by remember { mutableStateOf("0") }
    var type by remember { mutableStateOf(LedgerAccountType.CASH) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账户名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = initial, onValueChange = { initial = it }, label = { Text("期初余额（元）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerAccountType.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(accountTypeLabel(t)) })
            }
        }
        Button(onClick = {
            val cents = runCatching { BigDecimal(initial).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
            if (name.isNotBlank()) vm.saveAccount(LedgerAccount(vm.newId(), name, type, cents))
            name = ""
            initial = "0"
        }) { Text("保存账户") }
    }
}

@Composable
private fun EntryForm(vm: LedgerViewModel, accounts: List<LedgerAccount>) {
    val t = LocalCoupleTokens.current
    var type by remember { mutableStateOf(LedgerEntryType.EXPENSE) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(LedgerCategories.EXPENSE.first()) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var fromId by remember { mutableStateOf<String?>(null) }
    var toId by remember { mutableStateOf<String?>(null) }
    val categories = if (type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == LedgerEntryType.EXPENSE, onClick = { type = LedgerEntryType.EXPENSE; category = LedgerCategories.EXPENSE.first() }, label = { Text("支出") })
            FilterChip(selected = type == LedgerEntryType.INCOME, onClick = { type = LedgerEntryType.INCOME; category = LedgerCategories.INCOME.first() }, label = { Text("收入") })
            FilterChip(selected = type == LedgerEntryType.TRANSFER, onClick = { type = LedgerEntryType.TRANSFER }, label = { Text("转账") })
        }
        OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额（元）") }, modifier = Modifier.fillMaxWidth())
        if (type != LedgerEntryType.TRANSFER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            Text("账户", style = t.typography.caption)
            accounts.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, modifier = Modifier.weight(1f))
                    Switch(checked = accountId == a.id, onCheckedChange = { if (it) accountId = a.id })
                }
            }
        } else {
            Text("转出账户", style = t.typography.caption)
            accounts.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, modifier = Modifier.weight(1f))
                    Switch(checked = fromId == a.id, onCheckedChange = { if (it) fromId = a.id })
                }
            }
            Text("转入账户", style = t.typography.caption)
            accounts.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, modifier = Modifier.weight(1f))
                    Switch(checked = toId == a.id, onCheckedChange = { if (it) toId = a.id })
                }
            }
        }
        Button(onClick = {
            val cents = runCatching { BigDecimal(amount).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
            val entry = LedgerEntry(
                id = vm.newId(), ownerId = "",
                accountId = if (type == LedgerEntryType.TRANSFER) null else accountId,
                type = type,
                categoryId = if (type == LedgerEntryType.TRANSFER) null else category,
                amountCents = cents,
                fromAccountId = fromId, toAccountId = toId,
                occurredOn = LocalDate.now(),
            )
            vm.saveEntry(entry)
            amount = ""
        }) { Text("保存账目") }
    }
}
