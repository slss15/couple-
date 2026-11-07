package com.qimingnan.couple.ui.ledger

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

/** 借贷是独立备忘；借入、借出及偿还不自动生成收支，也不重复增加账户资产。 */
@Composable
internal fun LoanSection(vm: LedgerViewModel, accounts: List<LedgerAccount>, loans: List<LoanRecord>, initialAccountId: String? = null) {
    val t = LocalCoupleTokens.current
    val summary = LoanTotals.summarize(loans)
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LoanRecord?>(null) }
    var repay by remember { mutableStateOf<LoanRecord?>(null) }
    var deleting by remember { mutableStateOf<LoanRecord?>(null) }
    var filter by rememberSaveable { mutableStateOf("未结清") }
    SectionCard("信用与负债") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
            Column(Modifier.weight(1f)) {
                Text("待还 · 借入", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Text(centsText(summary.outstandingBorrowedCents), style = t.typography.title, color = t.colors.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("待收 · 借出", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Text(centsText(summary.outstandingLentCents), style = t.typography.title, color = t.colors.secondary)
            }
        }
        Text("本机借贷备忘，关联账户不改变余额。实际资金变动请另记收入、支出或转账。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        Button(onClick = { editing = null; editorOpen = true }) { Text("新增借入 / 借出") }
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            listOf("未结清", "已结清", "全部").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
        }
        val shown = loans.filter { when (filter) { "未结清" -> !it.settled; "已结清" -> it.settled; else -> true } }
            .sortedWith(compareBy<LoanRecord> { it.settled }.thenBy { it.dueOn ?: "9999-12-31" }.thenBy { it.id })
        if (shown.isEmpty()) Text("这里没有" + filter.takeIf { it != "全部" }.orEmpty() + "的借贷记录。", style = t.typography.body, color = t.colors.onSurfaceVariant)
        shown.forEach { loan -> key(loan.id) {
            Surface(color = t.colors.surfaceContainer, shape = t.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(t.spacing.md), verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loan.kind.label + " · " + loan.counterparty.ifBlank { loan.label.ifBlank { "未写对方" } }, style = t.typography.title, color = t.colors.onSurface)
                            if (loan.label.isNotBlank() && loan.counterparty.isNotBlank()) Text(loan.label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                            loan.accountId?.let { id -> Text("关联：" + (accounts.firstOrNull { it.id == id }?.name ?: "账户已删除"), style = t.typography.caption, color = t.colors.onSurfaceVariant) }
                        }
                        Text(centsText(loan.amountCents), style = t.typography.title, color = t.colors.onSurface)
                    }
                    RatioBar(loan.repaidCents.toFloat() / loan.amountCents.toFloat())
                    Text((if (loan.kind == LoanKind.BORROWED) "已还 " else "已收回 ") + centsText(loan.repaidCents) + " · 未结清 " + centsText(loan.remainingCents), style = t.typography.body, color = t.colors.onSurface)
                    loan.dueOn?.let { due -> Text((if (loan.overdue(LocalDate.now())) "已逾期 · " else "约定日期 · ") + due, style = t.typography.caption, color = if (loan.overdue(LocalDate.now())) t.colors.error else t.colors.onSurfaceVariant) }
                    if (loan.repayments.isNotEmpty()) {
                        Text("偿还记录", style = t.typography.label, color = t.colors.onSurface)
                        loan.repayments.asReversed().forEach { payment ->
                            Text(payment.date + " · " + centsText(payment.amountCents) + if (payment.note.isBlank()) "" else " · " + payment.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                    } else if (loan.repaidCents > 0) Text("历史已还金额已保留，旧记录没有逐笔日期。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                        if (loan.settled) Text("已结清", modifier = Modifier.padding(vertical = t.spacing.md), color = t.colors.secondary)
                        else TextButton(onClick = { repay = loan }) { Text(if (loan.kind == LoanKind.BORROWED) "记还款" else "记收回") }
                        TextButton(onClick = { editing = loan; editorOpen = true }) { Text("编辑") }
                        TextButton(onClick = { deleting = loan }) { Text("删除") }
                    }
                }
            }
        } }
    }
    if (editorOpen) LoanEditor(vm, accounts, editing, initialAccountId, onDismiss = { editorOpen = false })
    repay?.let { selected ->
        val current = loans.firstOrNull { it.id == selected.id }
        if (current != null) RepaymentDialog(vm, current, onDismiss = { repay = null })
    }
    deleting?.let { loan ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除借贷备忘？") },
            text = { Text("这会删除本笔备忘和还款明细，不会删除或修改账本中的收支。") },
            confirmButton = { TextButton(onClick = { vm.deleteLoan(loan.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoanEditor(vm: LedgerViewModel, accounts: List<LedgerAccount>, existing: LoanRecord?, initialAccountId: String?, onDismiss: () -> Unit) {
    val recordId = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: LoanKind.BORROWED) }
    var amount by rememberSaveable { mutableStateOf(existing?.amountCents?.let { java.math.BigDecimal(it).movePointLeft(2).toPlainString() } ?: "") }
    var repaid by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var person by rememberSaveable { mutableStateOf(existing?.counterparty ?: "") }
    var accountId by rememberSaveable { mutableStateOf(existing?.accountId ?: initialAccountId) }
    var due by rememberSaveable { mutableStateOf(existing?.dueOn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidate = LoanRecord(recordId, kind, accountId, parseCents(amount), existing?.repaidCents ?: parseCents(repaid), label.trim(), person.trim(), due, existing?.repayments ?: emptyList())
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "新增借贷" else "编辑借贷") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LoanKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }, enabled = existing == null) } }
            OutlinedTextField(person, { person = it }, label = { Text("对方（选填）") }, singleLine = true)
            OutlinedTextField(label, { label = it }, label = { Text("用途 / 备注（选填）") }, maxLines = 2)
            AmountField(amount, { amount = it }, "借贷总金额（元）")
            if (existing == null) AmountField(repaid, { repaid = it }, "已经还了多少（选填，元）")
            Text("关联账户（选填）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(accountId == null, { accountId = null }, label = { Text("不关联") })
                accounts.forEach { a -> FilterChip(accountId == a.id, { accountId = a.id }, label = { Text(a.name) }) }
            }
            LoanDateButton(due, { due = it }, "约定还款日")
            error?.let { Text(it, color = LocalCoupleTokens.current.colors.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        busy = true
        vm.addLoan(candidate) { problem -> busy = false; error = problem; if (problem == null) onDismiss() }
    }, enabled = !busy && candidate.validationError() == null) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun RepaymentDialog(vm: LedgerViewModel, loan: LoanRecord, onDismiss: () -> Unit) {
    var amount by rememberSaveable(loan.id) { mutableStateOf("") }
    var note by rememberSaveable(loan.id) { mutableStateOf("") }
    var date by rememberSaveable(loan.id) { mutableStateOf<String?>(LocalDate.now().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cents = parseCents(amount)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (loan.kind == LoanKind.BORROWED) "记录还款" else "记录收回") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("未结清 " + centsText(loan.remainingCents))
            AmountField(amount, { amount = it }, "本次金额（元）")
            TextButton(onClick = { amount = java.math.BigDecimal(loan.remainingCents).movePointLeft(2).toPlainString() }) { Text("全部结清") }
            LoanDateButton(date, { date = it }, "偿还日期", allowClear = false)
            OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") })
            if (cents > loan.remainingCents) Text("不能超过未结清金额", color = LocalCoupleTokens.current.colors.error)
            error?.let { Text(it, color = LocalCoupleTokens.current.colors.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        busy = true
        vm.repayLoan(loan.id, cents, date ?: LocalDate.now().toString(), note) { problem -> busy = false; error = problem; if (problem == null) onDismiss() }
    }, enabled = !busy && cents > 0 && cents <= loan.remainingCents) { Text(if (busy) "保存中…" else "确认") } },
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
