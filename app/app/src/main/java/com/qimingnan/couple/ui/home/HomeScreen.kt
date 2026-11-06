package com.qimingnan.couple.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.anniversary.AnniversaryCalendar
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.anniversary.AnniversaryViewModel
import com.qimingnan.couple.ui.components.GlyphAvatar
import com.qimingnan.couple.ui.components.LedgerEntryRow
import com.qimingnan.couple.ui.components.OptionCard
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.ledger.LedgerViewModel
import java.time.LocalDate

/**
 * 首页：独立页面，打开 App 先看到的第一屏，不在底栏里。
 *
 * 只放快速入口，不做重功能，数据全用现有 ViewModel，不新开表：
 * 大按钮「记一笔」一点直达记账弹窗向导第 1 步（输金额）；最近纪念日倒数
 * 按事件自身的阳历/阴历显示；最近几笔账；本月支出/收入/结余；
 * 纪念日、记账、动态三个入口行。
 */
@Composable
fun HomeScreen(
    anniversaryVm: AnniversaryViewModel,
    ledgerVm: LedgerViewModel,
    onRecord: () -> Unit,
    onOpenAnniversary: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenFeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalCoupleTokens.current
    val today = LocalDate.now()
    val events by anniversaryVm.events.collectAsStateWithLifecycle()
    val entries by ledgerVm.entries.collectAsStateWithLifecycle()
    val accounts by ledgerVm.accounts.collectAsStateWithLifecycle()
    val totals by ledgerVm.monthTotals.collectAsStateWithLifecycle()
    val accountById = accounts.associateBy { it.id }

    fun centsText(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} 元"
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text("首页", style = t.typography.headline, color = t.colors.onSurface)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            // 大按钮「记一笔」：主色大卡，点一下直达向导第 1 步（输金额）。
            Surface(
                color = t.colors.primaryContainer,
                shape = t.shapes.large,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onRecord),
            ) {
                Column(Modifier.fillMaxWidth().padding(t.spacing.lg)) {
                    Text("记一笔", style = t.typography.headline, color = t.colors.onPrimaryContainer)
                    Text("点一下，直达向导第 1 步", style = t.typography.body, color = t.colors.onPrimaryContainer)
                }
            }
            val live = events.filter { !it.deleted }.sortedBy { e ->
                AnniversaryLogic.nextOccurrence(e, today)?.date ?: LocalDate.MAX
            }
            SectionCard("最近的纪念日") {
                if (live.isEmpty()) {
                    Text("还没有纪念日，添加后会在这里倒数。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                }
                live.take(2).forEach { event ->
                    val next = AnniversaryLogic.nextOccurrence(event, today)
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenAnniversary),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(event.title, style = t.typography.title, color = t.colors.onSurface)
                            Text(
                                if (event.calendar == AnniversaryCalendar.LUNAR) "阴历 ${event.month}月${event.day}日" else "阳历 ${event.month}月${event.day}日",
                                style = t.typography.caption, color = t.colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (next == null) "已过期" else "还有 ${AnniversaryLogic.daysUntil(today, next)} 天",
                            style = t.typography.title, color = t.colors.primary,
                        )
                    }
                }
            }
            SectionCard("最近的账") {
                if (entries.isEmpty()) {
                    Text("还没有账目，点「记一笔」记第一笔。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                }
                entries.sortedByDescending { it.occurredOn }.take(5).forEach { e ->
                    LedgerEntryRow(
                        glyph = when (e.type) {
                            LedgerEntryType.TRANSFER -> "转"
                            else -> (e.categoryId ?: "其").firstOrNull()?.toString() ?: "其"
                        },
                        title = when (e.type) {
                            LedgerEntryType.EXPENSE -> "支出 · ${e.categoryId ?: "已删除分类"}"
                            LedgerEntryType.INCOME -> "收入 · ${e.categoryId ?: "已删除分类"}"
                            LedgerEntryType.TRANSFER -> "转账"
                        },
                        subtitle = when (e.type) {
                            LedgerEntryType.TRANSFER -> "${accountById[e.fromAccountId]?.name ?: "?"} → ${accountById[e.toAccountId]?.name ?: "?"} · ${e.occurredOn}"
                            else -> "${accountById[e.accountId]?.name ?: ""} · ${e.occurredOn}"
                        },
                        amountText = when (e.type) {
                            LedgerEntryType.EXPENSE -> "-¥${e.amountCents / 100}.${(e.amountCents % 100).toString().padStart(2, '0')}"
                            LedgerEntryType.INCOME -> "+¥${e.amountCents / 100}.${(e.amountCents % 100).toString().padStart(2, '0')}"
                            LedgerEntryType.TRANSFER -> "¥${e.amountCents / 100}.${(e.amountCents % 100).toString().padStart(2, '0')}"
                        },
                        note = e.note,
                        onClick = onOpenLedger,
                    )
                }
            }
            SectionCard("本月") {
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
            Text("去哪里", style = t.typography.title, color = t.colors.onSurface)
            OptionCard(
                title = "纪念日",
                subtitle = "倒数、打卡、旅行计划",
                onClick = onOpenAnniversary,
                leading = { GlyphAvatar("纪") },
            )
            OptionCard(
                title = "记账",
                subtitle = "统计、记一笔、账户",
                onClick = onOpenLedger,
                leading = { GlyphAvatar("账") },
            )
            OptionCard(
                title = "动态",
                subtitle = "看看最近的动态",
                onClick = onOpenFeed,
                leading = { GlyphAvatar("动") },
            )
        }
    }
}
