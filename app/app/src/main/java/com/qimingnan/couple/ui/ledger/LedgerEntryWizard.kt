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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    initialLoanId: String? = null,
) {
    val t = LocalCoupleTokens.current
    val loans by vm.loans.collectAsStateWithLifecycle()
    val initialLoan = loans.firstOrNull { it.id == initialLoanId }
    val cashAccounts = accounts.filterNot { it.type.isLoan }
    val allAccounts by vm.accounts.collectAsStateWithLifecycle()
    val entryId = rememberSaveable { UUID.randomUUID().toString() }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var amount by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(if (initialLoan?.kind == LoanKind.LENT) LedgerEntryType.INCOME else LedgerEntryType.EXPENSE) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(if (initialLoanId != null) "还钱" else null) }
    var loanId by rememberSaveable { mutableStateOf(initialLoanId) }
    var loanAccountId by rememberSaveable { mutableStateOf(initialLoan?.loanAccountId) }
    var counterpartyKey by rememberSaveable { mutableStateOf(initialLoan?.counterpartyKey) }
    var loanEditorOpen by remember { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var addAccount by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val transfer = type == LedgerEntryType.TRANSFER
    val repayment = !transfer && category == "还钱"
    val selectedLoan = loans.firstOrNull { it.id == loanId }
    val expectedKind = if (type == LedgerEntryType.INCOME) LoanKind.LENT else LoanKind.BORROWED
    val selectedLoanAccount = allAccounts.firstOrNull { it.id == loanAccountId && it.type.loanKind == expectedKind }
    val eligibleLoans = loans.filter { !it.settled && it.kind == expectedKind && it.loanAccountId == selectedLoanAccount?.id }
    fun goBack() {
        when {
            step == 4 && repayment && counterpartyKey != null -> { counterpartyKey = null; loanId = null }
            step == 4 && repayment && loanAccountId != null -> { loanAccountId = null; loanId = null }
            step > 0 -> step--
            else -> onDismiss()
        }
    }
    val draft = LedgerEntryDraft(parseCents(amount), type, accountId, toId, category, detail, LocalDate.parse(date), note)
    val titles = if (repayment) draft.steps.mapIndexed { index, title -> if (index == 4) "选择借贷账户与个人 / 平台" else title } else draft.steps
    val lastStep = titles.lastIndex
    val selectedAccount = cashAccounts.firstOrNull { it.id == accountId }
    val selectedTo = cashAccounts.firstOrNull { it.id == toId }
    val summary = listOfNotNull(
        amount.takeIf { parseCents(it) > 0 }?.let { centsText(parseCents(it)) },
        if (step > 1) when (type) { LedgerEntryType.EXPENSE -> "支出"; LedgerEntryType.INCOME -> "收入"; LedgerEntryType.TRANSFER -> "转账" } else null,
        selectedAccount?.name,
        if (transfer) selectedTo?.name?.let { "→ $it" } else category,
        if (repayment) selectedLoan?.counterpartyName else if (!transfer) detail else null,
    ).joinToString(" · ")
    if (loanEditorOpen && selectedLoanAccount != null) LoanEditor(vm, selectedLoanAccount, cashAccounts, onDismiss = { loanEditorOpen = false })
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0); error = null; addAccount = false }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = t.colors.surface, shape = t.shapes.large,
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().widthIn(max = 560.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.88f).dp).imePadding(),
        ) {
            BackHandler(enabled = !saving) { goBack() }
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
                                    if (type != kind || initialLoanId == null) {
                                        category = null; detail = null; loanId = null; loanAccountId = null; counterpartyKey = null
                                    }
                                    type = kind; accountId = null; toId = null; step = 2
                                }, leading = { GlyphAvatar(label.take(1)) })
                            }
                        }
                        step == 2 || (transfer && step == 3) -> {
                            val choices = if (transfer && step == 3) cashAccounts.filter { it.id != accountId } else cashAccounts
                            if (choices.isEmpty()) Text(if (cashAccounts.isEmpty()) "先添加一个资金账户，再继续记账。" else "转账需要另一个账户。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                            choices.forEach { account ->
                                OptionCard(account.name, "余额 " + centsText(vm.balance(account, entries)), {
                                    if (step == 2) accountId = account.id else toId = account.id
                                    step++
                                }, selected = account.id == if (step == 2) accountId else toId, leading = {
                                    AccountIcon(account.type, Modifier.size(44.dp), account.bankCode)
                                })
                            }
                            TextButton(onClick = { addAccount = !addAccount }) { Text(if (addAccount) "取消新增" else "新增账户") }
                            if (addAccount) AccountForm(vm, onDone = { addAccount = false }, allowLoanTypes = false)
                        }
                        step == 3 -> {
                            val categories = (if (type == LedgerEntryType.INCOME) LedgerCategories.INCOME else LedgerCategories.EXPENSE) + "还钱"
                            categories.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                                    row.forEach { c ->
                                        OptionCard(if (c == "还钱") (if (type == LedgerEntryType.INCOME) "还钱 · 收回借款" else "还钱 · 归还借款") else c,
                                            if (c == "还钱") "选择借出/借入账户，再选择对应个人或平台" else "",
                                            { category = c; detail = null; loanId = null; loanAccountId = null; counterpartyKey = null; step++ }, modifier = Modifier.weight(1f), leading = { GlyphAvatar(c.take(1)) })
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        step == 4 && repayment -> {
                            when {
                                selectedLoanAccount == null -> {
                                    val choices = allAccounts.filter { it.type.loanKind == expectedKind }
                                    Text(if (type == LedgerEntryType.INCOME) "选择借出账户（对方还钱）" else "选择借入账户（我还钱）", style = t.typography.body)
                                    choices.forEach { account -> OptionCard(account.name, account.type.loanKind?.label.orEmpty(), { loanAccountId = account.id; counterpartyKey = null }) }
                                    if (choices.isEmpty()) Text("还没有对应的借贷账户，请先在账户页添加。", color = t.colors.onSurfaceVariant)
                                }
                                counterpartyKey == null -> {
                                    Text(if (type == LedgerEntryType.INCOME) "选择借款人 / 平台" else "选择还款对象 / 平台", style = t.typography.body)
                                    eligibleLoans.groupBy { it.counterpartyKey }.toSortedMap().forEach { (key, records) ->
                                        OptionCard(records.first().counterpartyName,
                                            "${records.first().counterpartyType.label} · 未结清 ${centsText(records.sumOf { it.remainingCents })}",
                                            { counterpartyKey = key })
                                    }
                                    if (eligibleLoans.isEmpty()) Text("该账户没有未结清借款。", color = t.colors.onSurfaceVariant)
                                    TextButton(onClick = { loanEditorOpen = true }) { Text("新增借贷明细") }
                                }
                                else -> {
                                    Text(eligibleLoans.firstOrNull { it.counterpartyKey == counterpartyKey }?.counterpartyName ?: "该分类已结清或删除", style = t.typography.title)
                                    eligibleLoans.filter { it.counterpartyKey == counterpartyKey }.forEach { loan ->
                                        OptionCard(loan.label.ifBlank { "借款明细" }, "未结清 ${centsText(loan.remainingCents)} · 借款日期 ${loan.occurredOn ?: "未填写"}", {
                                            loanId = loan.id; detail = loan.counterpartyName; step++
                                        })
                                    }
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
                            SectionCard(if (transfer) "确认转账" else if (repayment) "确认还钱" else "确认账目") {
                                Text(centsText(draft.amountCents), style = t.typography.display, color = t.colors.primary)
                                Text(if (transfer) (selectedAccount?.name ?: "账户已删除") + " → " + (selectedTo?.name ?: "账户已删除") else "账户：" + (selectedAccount?.name ?: "账户已删除"), style = t.typography.body, color = t.colors.onSurface)
                                if (!transfer) Text(if (repayment) "借贷账户：${selectedLoanAccount?.name ?: "已删除"} · ${selectedLoan?.counterpartyType?.label.orEmpty()} · ${selectedLoan?.counterpartyName ?: "已删除"}" else "分类：$category · $detail", style = t.typography.body, color = t.colors.onSurface)
                                if (selectedAccount != null && type != LedgerEntryType.INCOME && vm.balance(selectedAccount, entries) < draft.amountCents) {
                                    Text("本笔之后账户余额可能为负，仍可记账。", style = t.typography.caption, color = t.colors.warning)
                                }
                            }
                            if (repayment && selectedLoan == null) Text("所选借贷已删除，请返回重新选择。", color = t.colors.error)
                            if (repayment && selectedLoan != null && draft.amountCents > selectedLoan.remainingCents) Text("还款超过未结清金额 ${centsText(selectedLoan.remainingCents)}，请返回修改金额。", color = t.colors.error)
                            EntryDatePicker(date, { date = it })
                            OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            Text("只记入你的个人账本。" + if (transfer || repayment) "借贷本金与转账不计入日常预算和收支统计，实际账户余额照常变动。" else "不会出现在动态、聊天或 TA 页面。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                    }
                    error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
                }
                if (step > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    TextButton(onClick = { goBack() }, enabled = !saving) { Text("上一步") }
                    if (step == lastStep) {
                        val candidate = draft.toEntry(entryId)
                        Button(onClick = {
                            saving = true
                            val onComplete: (String?) -> Unit = { problem ->
                                saving = false; error = problem
                                if (problem == null) onDismiss()
                            }
                            if (repayment && selectedLoan != null && accountId != null) {
                                vm.recordRepayment(entryId, selectedLoan.id, accountId!!, draft.amountCents, date, note, expectedKind, onComplete)
                            } else vm.saveEntry(candidate, onComplete)
                        }, enabled = !saving && selectedAccount != null && (!transfer || selectedTo != null) && LedgerLogic.validate(candidate) == null && (!repayment || selectedLoanAccount != null && selectedLoan != null && !selectedLoan.settled && selectedLoan.kind == expectedKind && draft.amountCents <= selectedLoan.remainingCents),
                            modifier = Modifier.weight(1f)) { Text(if (saving) "保存中…" else if (transfer) "保存转账" else if (repayment) "保存还钱" else "保存账目") }
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
