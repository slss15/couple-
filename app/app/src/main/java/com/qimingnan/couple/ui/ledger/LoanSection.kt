package com.qimingnan.couple.ui.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.SectionCard
import java.time.LocalDate
import java.util.UUID

/** A loan account contains person/platform categories; each category keeps its own loans and repayments. */
@Composable
internal fun LoanAccountDetail(
    vm: LedgerViewModel, account: LedgerAccount, cashAccounts: List<LedgerAccount>,
    allLoans: List<LoanRecord>, entries: List<LedgerEntry>, onBack: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val loans = allLoans.filter { it.loanAccountId == account.id }
    var selectedGroup by rememberSaveable(account.id) { mutableStateOf<String?>(null) }
    var classification by rememberSaveable(account.id) { mutableStateOf("全部") }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LoanRecord?>(null) }
    var repayingId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LoanRecord?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(account.name) { mutableStateOf(account.name) }
    var error by remember { mutableStateOf<String?>(null) }
    val groups = loans.groupBy { it.counterpartyKey }
    val groupLoans = selectedGroup?.let { groups[it] }.orEmpty()
    val groupLabel = groupLoans.firstOrNull()?.counterpartyName
    BackHandler(selectedGroup != null) { selectedGroup = null }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (selectedGroup != null) selectedGroup = null else onBack() }) { Text("返回") }
        Text(groupLabel ?: account.name, style = t.typography.headline, color = t.colors.onSurface, modifier = Modifier.weight(1f))
        if (selectedGroup == null) TextButton(onClick = { renaming = !renaming }) { Text("改名") }
    }
    if (renaming) {
        OutlinedTextField(name, { name = it }, label = { Text("账户名") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveAccount(account.copy(name = name.trim())) { problem -> error = problem; if (problem == null) renaming = false } }, enabled = name.isNotBlank()) { Text("保存名称") }
    }
    val shown = if (selectedGroup == null) loans else groupLoans
    SectionCard(if (account.type == LedgerAccountType.LENT) "借出 · 待收回" else "借入 · 待归还") {
        Text(centsText(shown.sumOf { it.remainingCents }), style = t.typography.display, color = t.colors.primary)
        Text("借款总额 ${centsText(shown.sumOf { it.amountCents })} · 已还 ${centsText(shown.sumOf { it.repaidCents })}", style = t.typography.body, color = t.colors.onSurface)
        Text("资金进出同步到实际收款/付款账户；借贷本金不计日常收入、消费与预算。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        Button(onClick = { editing = null; editorOpen = true }) { Text(if (account.type == LedgerAccountType.LENT) "新增借出" else "新增借入") }
    }
    error?.let { Text(it, color = t.colors.error) }
    if (selectedGroup == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            listOf("全部", "个人", "平台").forEach { label -> FilterChip(classification == label, { classification = label }, label = { Text(label) }) }
        }
        if (groups.isEmpty()) Text("还没有借贷明细，新增时按个人或平台分类。", color = t.colors.onSurfaceVariant)
        groups.toSortedMap().forEach { (group, records) ->
            val first = records.first()
            if (classification == "全部" || first.counterpartyType.label == classification) {
                Surface(color = t.colors.surfaceContainer, shape = t.shapes.large, modifier = Modifier.fillMaxWidth().clickable { selectedGroup = group }) {
                    Row(Modifier.padding(t.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(first.counterpartyName, style = t.typography.title, color = t.colors.onSurface)
                            Text("${first.counterpartyType.label} · ${records.size} 笔借款 · ${records.sumOf { it.repayments.size }} 次还款", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                        Text(centsText(records.sumOf { it.remainingCents }), style = t.typography.title, color = t.colors.primary)
                    }
                }
            }
        }
        if (loans.isEmpty()) TextButton(onClick = { vm.deleteAccount(account.id); onBack() }) { Text("删除空账户") }
    } else {
        shown.sortedWith(compareBy<LoanRecord> { it.settled }.thenByDescending { it.occurredOn ?: "" }).forEach { loan -> key(loan.id) {
            SectionCard(loan.label.ifBlank { "借贷明细" }) {
                Text("${loan.kind.label} ${centsText(loan.amountCents)} · 未结清 ${centsText(loan.remainingCents)}", style = t.typography.title, color = t.colors.onSurface)
                Text("借款日期：${loan.occurredOn ?: "旧记录未填写"}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                loan.accountId?.let { id -> Text("资金账户：" + (cashAccounts.firstOrNull { it.id == id }?.name ?: "已删除/历史账户"), style = t.typography.caption, color = t.colors.onSurfaceVariant) }
                loan.dueOn?.let { due -> Text((if (loan.overdue(LocalDate.now())) "已逾期 · " else "约定还款日 · ") + due, color = if (loan.overdue(LocalDate.now())) t.colors.error else t.colors.onSurfaceVariant) }
                RatioBar(loan.repaidCents.toFloat() / loan.amountCents.toFloat())
                val historical = loan.repaidCents - loan.repayments.sumOf { it.amountCents }
                if (historical > 0) Text("历史已还（旧记录/补录）：${centsText(historical)}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                loan.repayments.sortedByDescending { it.date }.forEach { payment ->
                    val cash = payment.cashAccountId?.let { id -> cashAccounts.firstOrNull { it.id == id }?.name ?: "资金账户已删除" }
                    Text("${payment.date} · ${if (loan.kind == LoanKind.LENT) "收到还钱" else "归还借款"} ${centsText(payment.amountCents)}" + cash?.let { " · $it" }.orEmpty(), style = t.typography.body, color = t.colors.onSurface)
                    if (payment.note.isNotBlank()) Text(payment.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    if (!loan.settled) TextButton(onClick = { repayingId = loan.id }) { Text(if (loan.kind == LoanKind.LENT) "收到还钱" else "归还借款") }
                    TextButton(onClick = { editing = loan; editorOpen = true }) { Text("编辑") }
                    TextButton(onClick = { deleting = loan }) { Text("删除") }
                }
            }
        } }
    }
    if (editorOpen) LoanEditor(vm, account, cashAccounts, editing, groupLoans.firstOrNull(), { editorOpen = false })
    repayingId?.let { id -> LedgerEntryWizard(vm, cashAccounts, entries, { repayingId = null }, initialLoanId = id) }
    deleting?.let { loan ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除借贷明细？") },
            text = { Text("同步撤销本笔借入/借出本金的资金记录。已有还款账目时，须先在账目中删除相关还款，避免明细不一致。历史备忘没有关联资金记录时，只删除备忘。") },
            confirmButton = { TextButton(onClick = { vm.deleteLoan(loan.id) { problem -> error = problem; if (problem == null) deleting = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoanEditor(vm: LedgerViewModel, account: LedgerAccount, cashAccounts: List<LedgerAccount>,
    existing: LoanRecord? = null, group: LoanRecord? = null, onDismiss: () -> Unit) {
    val t = LocalCoupleTokens.current
    val recordId = rememberSaveable(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    val kind = requireNotNull(account.type.loanKind)
    var amount by rememberSaveable { mutableStateOf(existing?.amountCents?.let { java.math.BigDecimal(it).movePointLeft(2).toPlainString() } ?: "") }
    var repaid by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var person by rememberSaveable { mutableStateOf(existing?.counterparty ?: group?.counterpartyName ?: "") }
    var counterpartyType by rememberSaveable { mutableStateOf(existing?.counterpartyType ?: group?.counterpartyType ?: CounterpartyType.PERSON) }
    var cashId by rememberSaveable { mutableStateOf(existing?.accountId) }
    var due by rememberSaveable { mutableStateOf(existing?.dueOn) }
    var date by rememberSaveable { mutableStateOf(existing?.occurredOn ?: LocalDate.now().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val historicalRepaid = if (repaid.isBlank()) 0L else Money.parseCents(repaid) ?: -1L
    val candidate = LoanRecord(recordId, kind, cashId, parseCents(amount), existing?.repaidCents ?: historicalRepaid,
        label.trim(), person.trim(), due, existing?.repayments ?: emptyList(), account.id, counterpartyType, date)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text((if (existing == null) "新增" else "编辑") + kind.label) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CounterpartyType.entries.forEach { type -> FilterChip(counterpartyType == type, { counterpartyType = type }, label = { Text(type.label) }) } }
            OutlinedTextField(person, { person = it }, label = { Text(if (kind == LoanKind.LENT) "借款人 / 平台名称" else "还款对象 / 平台名称") }, singleLine = true)
            OutlinedTextField(label, { label = it }, label = { Text("用途 / 备注（选填）") }, maxLines = 2)
            AmountField(amount, { amount = it }, "借款金额（元）")
            if (existing == null) {
                AmountField(repaid, { repaid = it }, "历史已经归还（补录，元）")
                Text("实际资金账户（选填）", style = t.typography.label)
                Text("选中将同步记录本次${kind.label}资金。历史已记账的借贷选“不关联”，避免重复。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(cashId == null, { cashId = null }, label = { Text("不关联 / 历史补录") })
                    cashAccounts.filterNot { it.type.isLoan }.forEach { cash -> FilterChip(cashId == cash.id, { cashId = cash.id }, label = { Text(cash.name) }) }
                }
                LoanDateButton(date, { date = it ?: LocalDate.now().toString() }, "借款日期", allowClear = false)
            }
            LoanDateButton(due, { due = it }, "约定还款日")
            error?.let { Text(it, color = t.colors.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        busy = true
        vm.addLoan(candidate) { problem -> busy = false; error = problem; if (problem == null) onDismiss() }
    }, enabled = !busy && candidate.validationError() == null && (person.isNotBlank() || existing != null && existing.counterparty.isBlank())) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun LoanDateButton(date: String?, onPick: (String?) -> Unit, label: String, allowClear: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val current = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
            android.app.DatePickerDialog(context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day).toString()) }, current.year, current.monthValue - 1, current.dayOfMonth).show()
        }) { Text(label + "：" + (date ?: "未设置")) }
        if (allowClear && date != null) TextButton(onClick = { onPick(null) }) { Text("清除") }
    }
}
