package com.qimingnan.couple.ui.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.*
import java.time.LocalDate
import java.util.UUID

/** 普通收支六步、转账五步；每页只处理当前一步，不提供极速全表单。 */
@Composable
internal fun LedgerEntryWizard(
    vm: LedgerViewModel,
    accounts: List<LedgerAccount>,
    entries: List<LedgerEntry>,
    onDismiss: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val entryId = remember { UUID.randomUUID().toString() }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var amount by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(LedgerEntryType.EXPENSE) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var addAccount by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val transfer = type == LedgerEntryType.TRANSFER
    val draft = LedgerEntryDraft(parseCents(amount), type, accountId, toId, category, detail, LocalDate.parse(date), note)
    val titles = draft.steps
    val lastStep = titles.lastIndex
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val selectedTo = accounts.firstOrNull { it.id == toId }
    val summary = listOfNotNull(
        amount.takeIf { parseCents(it) > 0 }?.let { centsText(parseCents(it)) },
        if (step > 1) when (type) { LedgerEntryType.EXPENSE -> "支出"; LedgerEntryType.INCOME -> "收入"; LedgerEntryType.TRANSFER -> "转账" } else null,
        selectedAccount?.name,
        if (transfer) selectedTo?.name?.let { "→ $it" } else category,
        if (!transfer) detail else null,
    ).joinToString(" · ")
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0); error = null; addAccount = false }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = t.colors.surface, shape = t.shapes.large,
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().widthIn(max = 560.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.88f).dp).imePadding(),
        ) {
            BackHandler(enabled = !saving) { if (step > 0) step-- else onDismiss() }
            Column(Modifier.padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记一笔", style = t.typography.headline, color = t.colors.onSurface)
                        Text("第 " + (step + 1) + " 步 / 共 " + titles.size + " 步", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                }
                StitchProgressCapsules(step, titles.size)
                if (summary.isNotBlank()) PickedSummaryPill(summary)
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                    Text(titles[step], style = t.typography.title, color = t.colors.onSurface)
                    when {
                        step == 0 -> {
                            Text(centsText(parseCents(amount)), style = t.typography.display, color = t.colors.primary)
                            AmountKeypad(amount, { amount = it }, { if (parseCents(amount) > 0) step++ }, confirmLabel = "下一步", requirePositive = true)
                        }
                        step == 1 -> {
                            LedgerEntryType.entries.forEach { kind ->
                                val label = when (kind) { LedgerEntryType.EXPENSE -> "支出"; LedgerEntryType.INCOME -> "收入"; LedgerEntryType.TRANSFER -> "账户转账" }
                                OptionCard(label, when (kind) { LedgerEntryType.EXPENSE -> "花出去的钱"; LedgerEntryType.INCOME -> "收到的钱"; LedgerEntryType.TRANSFER -> "只移动账户余额，不计收入或支出" }, {
                                    type = kind; category = null; detail = null; accountId = null; toId = null; step = 2
                                }, leading = { GlyphAvatar(label.take(1)) })
                            }
                        }
                        step == 2 || (transfer && step == 3) -> {
                            val choices = if (transfer && step == 3) accounts.filter { it.id != accountId } else accounts
                            if (choices.isEmpty()) Text(if (accounts.isEmpty()) "先添加一个账户，再继续记账。" else "转账需要另一个账户。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                            choices.forEach { account ->
                                OptionCard(account.name, "余额 " + centsText(vm.balance(account, entries)), {
                                    if (step == 2) accountId = account.id else toId = account.id
                                    step++
                                }, selected = account.id == if (step == 2) accountId else toId, leading = {
                                    AccountIcon(account.type, Modifier.size(44.dp), account.bankCode)
                                })
                            }
                            TextButton(onClick = { addAccount = !addAccount }) { Text(if (addAccount) "取消新增" else "新增账户") }
                            if (addAccount) AccountForm(vm, onDone = { addAccount = false })
                        }
                        step == 3 -> {
                            val categories = if (type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE
                            categories.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                                    row.forEach { c ->
                                        OptionCard(c, "", { category = c; detail = null; step++ }, modifier = Modifier.weight(1f), leading = { GlyphAvatar(c.take(1)) })
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        step == 4 && !transfer -> {
                            LedgerSubcategories.forCategory(category ?: "其他").chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                                    row.forEach { d ->
                                        OptionCard(d, "", { detail = d; step++ }, modifier = Modifier.weight(1f))
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        step == lastStep -> {
                            SectionCard(if (transfer) "确认转账" else "确认账目") {
                                Text(centsText(draft.amountCents), style = t.typography.display, color = t.colors.primary)
                                Text(if (transfer) (selectedAccount?.name ?: "账户已删除") + " → " + (selectedTo?.name ?: "账户已删除") else "账户：" + (selectedAccount?.name ?: "账户已删除"), style = t.typography.body, color = t.colors.onSurface)
                                if (!transfer) Text("分类：$category · $detail", style = t.typography.body, color = t.colors.onSurface)
                                if (selectedAccount != null && type != LedgerEntryType.INCOME && vm.balance(selectedAccount, entries) < draft.amountCents) {
                                    Text("本笔之后账户余额可能为负，仍可记账。", style = t.typography.caption, color = t.colors.warning)
                                }
                            }
                            EntryDatePicker(date, { date = it })
                            OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            Text("只记入你的个人账本。" + if (transfer) "转账不计入预算与收支统计。" else "不会出现在动态、聊天或 TA 页面。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                    }
                    error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
                }
                if (step > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    TextButton(onClick = { step-- }, enabled = !saving) { Text("上一步") }
                    if (step == lastStep) {
                        val candidate = draft.toEntry(entryId)
                        Button(onClick = {
                            saving = true
                            vm.saveEntry(candidate) { problem ->
                                saving = false; error = problem
                                if (problem == null) onDismiss()
                            }
                        }, enabled = !saving && selectedAccount != null && (!transfer || selectedTo != null) && LedgerLogic.validate(candidate) == null,
                            modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else if (transfer) "保存转账" else "保存账目") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryDatePicker(date: String, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(onClick = {
        val current = LocalDate.parse(date)
        android.app.DatePickerDialog(context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day).toString()) }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }) { Text("日期：$date") }
}
