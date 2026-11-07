package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.data.anniversary.TravelSavings
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.ledger.AmountField
import com.qimingnan.couple.ui.ledger.centsText
import com.qimingnan.couple.ui.ledger.parseCents

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TravelPlanCard(vm: AnniversaryViewModel, extras: AnniversaryExtras, accounts: List<LedgerAccount>, entries: List<LedgerEntry>) {
    val t = LocalCoupleTokens.current
    var editing by remember { mutableStateOf(false) }
    var name by remember(extras.travelName) { mutableStateOf(extras.travelName) }
    var selected by remember(extras.travelAccountId) { mutableStateOf(extras.travelAccountId) }
    var target by remember(extras.travelTargetCents) { mutableStateOf(if (extras.travelTargetCents > 0) java.math.BigDecimal(extras.travelTargetCents).movePointLeft(2).toPlainString() else "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val configured = extras.travelAccountId != null && extras.travelTargetCents > 0
    SectionCard("旅行计划") {
        if (!configured || editing) {
            Text("给下一次出发留一点期待", style = t.typography.title, color = t.colors.onSurface)
            OutlinedTextField(name, { name = it }, label = { Text("目的地 / 计划名称（选填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("旅行攒钱关联账户", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            if (accounts.isEmpty()) Text("先在记账 → 账户页新增一个账户。", style = t.typography.body, color = t.colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { account -> FilterChip(selected == account.id, { selected = account.id }, label = { Text(account.name) }) }
            }
            AmountField(target, { target = it }, "攒钱目标（元）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    busy = true
                    vm.setTravel(selected, parseCents(target), name) { problem -> busy = false; error = problem; if (problem == null) editing = false }
                }, enabled = !busy && accounts.any { it.id == selected } && parseCents(target) > 0) { Text(if (busy) "保存中…" else "保存旅行计划") }
                if (configured) TextButton(onClick = { editing = false }) { Text("取消") }
            }
        } else {
            val account = accounts.firstOrNull { it.id == extras.travelAccountId }
            Text(extras.travelName.ifBlank { "下一次出发" }, style = t.typography.title, color = t.colors.onSurface)
            val savings = TravelSavings.from(account, entries, extras.travelTargetCents)
            if (savings == null) Text("关联账户已删除或不可用，请重新选择。", style = t.typography.body, color = t.colors.error)
            else {
                Text("已攒 " + centsText(savings.savedCents), style = t.typography.headline, color = t.colors.primary)
                RatioBar(savings.progress)
                Text("目标 " + centsText(savings.targetCents) + " · 还差 " + centsText(savings.remainingCents), style = t.typography.body, color = t.colors.onSurface)
                Text("关联 " + account!!.name + " · 实际余额 " + centsText(savings.balanceCents), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                if (savings.balanceCents < 0) Text("账户余额为负，储蓄进度按 0 展示。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                if (savings.remainingCents == 0L) Text("旅行目标已达成", style = t.typography.label, color = t.colors.secondary)
            }
            Row {
                TextButton(onClick = { editing = true }) { Text("编辑计划") }
                TextButton(onClick = { vm.setTravel(null, 0) }) { Text("移除计划") }
            }
        }
        Text("储蓄进度读取个人账户真实余额，不另建小金库，也不会冻结或扣除资金。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        error?.let { Text(it, color = t.colors.error) }
    }
}
