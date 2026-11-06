package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.anniversary.AnniversaryCalendar
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.anniversary.AnniversaryType
import com.qimingnan.couple.data.anniversary.LunarCalendar
import com.qimingnan.couple.data.anniversary.LunarDate
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.ledger.AmountField
import com.qimingnan.couple.ui.ledger.LedgerViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Anniversary plugin screen (PR3), Stitch visual pass.
 *
 * Pure display over [AnniversaryEvent] plus three extras that live in
 * settings DataStore, not in the anniversary table (see [AnniversaryExtras]):
 * 打卡 (check in on the day itself), 神秘惊喜准备进度 (0–100 for the nearest
 * upcoming event), and 旅行计划 (a linked account + a savings target, shown
 * against that account's real balance). No new table, no database bump.
 * The add/edit form (manual solar/lunar picker, leap-month choice,
 * dual-calendar preview) is still the next slice of this PR.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnniversaryScreen(
    anniversaryVm: AnniversaryViewModel,
    ledgerVm: LedgerViewModel,
    today: LocalDate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
) {
    val t = LocalCoupleTokens.current
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val events by anniversaryVm.events.collectAsStateWithLifecycle()
    val extras by anniversaryVm.extras.collectAsStateWithLifecycle()
    val accounts by ledgerVm.accounts.collectAsStateWithLifecycle()
    val entries by ledgerVm.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("全部") }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.colors.onSurface) }
            Text("纪念日", style = t.typography.headline, color = t.colors.onSurface)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            val live = events.filter { !it.deleted }.sortedBy { e ->
                AnniversaryLogic.nextOccurrence(e, today)?.date ?: LocalDate.MAX
            }
            // 顶部大卡：在一起天数。取首个带年份的恋爱纪念日起算；阳历直接算，
            // 阴历经换算；算不出来（例如超出 1900–2049）不编造天数，只提示。
            SectionCard("在一起") {
                val loveStart = remember(live) {
                    live.firstOrNull { it.type == AnniversaryType.LOVE && it.year != null }?.let { e ->
                        val y = e.year!!
                        when (e.calendar) {
                            AnniversaryCalendar.SOLAR -> runCatching {
                                if (e.month == 2 && e.day == 29 && !LocalDate.of(y, 1, 1).isLeapYear) LocalDate.of(y, 2, 28)
                                else LocalDate.of(y, e.month, e.day)
                            }.getOrNull()
                            AnniversaryCalendar.LUNAR -> LunarCalendar.lunarToSolar(LunarDate(y, e.month, e.day, isLeap = e.isLeap))
                        }
                    }
                }
                if (loveStart != null) {
                    val days = ChronoUnit.DAYS.between(loveStart, today)
                    if (days >= 0) {
                        Text("已经在一起 $days 天", style = t.typography.headline, color = t.colors.primary)
                        Text("从 ${loveStart.format(fmt)} 开始", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    } else {
                        Text("还没到 ${loveStart.format(fmt)}", style = t.typography.body, color = t.colors.onSurface)
                    }
                } else {
                    Text("添加一个带年份的恋爱纪念日，这里会显示在一起的天数。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                }
            }
            // 分类筛选：全部 / 生日 / 恋爱 / 其他，带计数。
            val birthdayCount = live.count { it.type == AnniversaryType.BIRTHDAY }
            val loveCount = live.count { it.type == AnniversaryType.LOVE }
            val otherCount = live.size - birthdayCount - loveCount
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部" to live.size, "生日" to birthdayCount, "恋爱" to loveCount, "其他" to otherCount).forEach { (label, count) ->
                    FilterChip(
                        selected = filter == label,
                        onClick = { filter = label },
                        label = { Text("$label（$count）") },
                    )
                }
            }
            val shown = when (filter) {
                "生日" -> live.filter { it.type == AnniversaryType.BIRTHDAY }
                "恋爱" -> live.filter { it.type == AnniversaryType.LOVE }
                "其他" -> live.filter { it.type != AnniversaryType.BIRTHDAY && it.type != AnniversaryType.LOVE }
                else -> live
            }
            if (shown.isEmpty()) {
                Text("这里还没有纪念日，添加后会在这里倒数。", style = t.typography.body, color = t.colors.onSurfaceVariant)
            }
            shown.forEachIndexed { index, event ->
                val next = AnniversaryLogic.nextOccurrence(event, today)
                SectionCard(event.title) {
                    Text(
                        typeLabel(event) + " · " + when (event.calendar) {
                            AnniversaryCalendar.SOLAR -> "阳历 ${event.month}月${event.day}日"
                            AnniversaryCalendar.LUNAR -> "阴历" + (if (event.isLeap) "闰" else "") + "${event.month}月${event.day}日"
                        },
                        style = t.typography.caption, color = t.colors.onSurfaceVariant,
                    )
                    if (next == null) {
                        Text("已过期", style = t.typography.body, color = t.colors.onSurface)
                    } else {
                        val days = AnniversaryLogic.daysUntil(today, next)
                        Text(
                            when {
                                days == 0L -> "就是今天 · ${next.date.format(fmt)}"
                                days > 0 -> "还有 $days 天 · ${next.date.format(fmt)}"
                                else -> "已过 ${-days} 天 · ${next.date.format(fmt)}"
                            },
                            style = t.typography.body, color = t.colors.onSurface,
                        )
                        if (next.leapFallback) Text("今年没有闰月，按正月同日办理", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        if (next.clampedDay) Text("当月没有这一天，按当月最后一天办理", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    if (event.type == AnniversaryType.BIRTHDAY) {
                        AnniversaryLogic.age(event, today)?.let { age ->
                            Text("周岁 $age 岁", style = t.typography.body, color = t.colors.onSurface)
                        }
                    }
                    if (event.note.isNotBlank()) Text(event.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    // 打卡：只在当天能打。打过显示“今天已打卡”，下面累计打卡次数。
                    if (next != null) {
                        val days = AnniversaryLogic.daysUntil(today, next)
                        val dateKey = next.date.toString()
                        if (days == 0L) {
                            if (extras.checkedIn(event.id, dateKey)) {
                                Text("今天已打卡", style = t.typography.body, color = t.colors.primary)
                            } else {
                                Button(onClick = { anniversaryVm.checkIn(event.id, dateKey) }) { Text("打卡") }
                            }
                        }
                        val count = extras.checkinCount(event.id)
                        if (count > 0) Text("已打卡 $count 次", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    // 神秘惊喜：只在最近一张倒数卡上出现，进度 0–100，每次 +10%。
                    if (index == 0) {
                        val progress = extras.surprise(event.id)
                        Text("神秘惊喜准备 $progress%", style = t.typography.body, color = t.colors.onSurface)
                        RatioBar(progress / 100f)
                        if (progress >= 100) {
                            Text("已准备好", style = t.typography.body, color = t.colors.primary)
                        } else {
                            Button(onClick = { anniversaryVm.setSurpriseProgress(event.id, progress + 10) }) { Text("准备 +10%") }
                        }
                    }
                }
            }
            // 旅行计划：关联一个账户 + 目标金额，已攒金额就是那个账户的真实余额。
            // 目标金额与关联账户只存在设置里，不升数据库版本。
            SectionCard("旅行计划") {
                val travelSet = extras.travelAccountId != null && extras.travelTargetCents > 0
                var travelEditing by remember { mutableStateOf(false) }
                var travelAccountPick by remember(extras.travelAccountId) { mutableStateOf(extras.travelAccountId) }
                var travelTargetText by remember(extras.travelTargetCents) {
                    mutableStateOf(if (extras.travelTargetCents > 0) BigDecimal(extras.travelTargetCents).divide(BigDecimal(100)).toPlainString() else "")
                }
                if (!travelSet || travelEditing) {
                    Text("选一个账户当旅行攒钱罐，再设一个目标金额。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                    if (accounts.isEmpty()) {
                        Text("还没有账户，先去记账页建一个账户。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { a ->
                            FilterChip(selected = travelAccountPick == a.id, onClick = { travelAccountPick = a.id }, label = { Text(a.name) })
                        }
                    }
                    AmountField(value = travelTargetText, onValueChange = { travelTargetText = it }, label = "旅行目标金额（元）")
                    val targetCents = runCatching { BigDecimal(travelTargetText).multiply(BigDecimal(100)).toLong() }.getOrDefault(0L)
                    Button(
                        onClick = {
                            anniversaryVm.setTravel(travelAccountPick, targetCents)
                            travelEditing = false
                        },
                        enabled = travelAccountPick != null && targetCents > 0,
                    ) { Text("保存旅行计划") }
                    if (travelSet) {
                        TextButton(onClick = { travelEditing = false }) { Text("取消") }
                    }
                } else {
                    val acc = accounts.firstOrNull { it.id == extras.travelAccountId }
                    val saved = acc?.let { ledgerVm.balance(it, entries) } ?: 0L
                    val target = extras.travelTargetCents
                    Text("目标 ${centsText(target)}", style = t.typography.body, color = t.colors.onSurface)
                    Text("已攒 ${centsText(saved)}（${acc?.name ?: "账户已删除"}）", style = t.typography.body, color = t.colors.onSurface)
                    RatioBar((saved.toDouble() / target.toDouble()).toFloat().coerceIn(0f, 1f))
                    Text(
                        if (saved >= target) "目标达成，可以出发了。" else "还差 ${centsText(target - saved)}",
                        style = t.typography.body, color = t.colors.onSurface,
                    )
                    TextButton(onClick = { travelEditing = true }) { Text("更换账户 / 目标") }
                }
            }
            Text(
                "阴历换算范围为 1900–2049，超出范围的日期不会被猜算。",
                style = t.typography.caption, color = t.colors.onSurfaceVariant,
            )
        }
    }
}

private fun centsText(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} 元"
}

private fun typeLabel(event: AnniversaryEvent): String = when (event.type) {
    AnniversaryType.BIRTHDAY -> "生日"
    AnniversaryType.LOVE -> "恋爱纪念日"
    AnniversaryType.WEDDING -> "结婚纪念日"
    AnniversaryType.FIRST_MEETING -> "第一次见面"
    AnniversaryType.FESTIVAL -> "节日"
    AnniversaryType.CUSTOM -> "自定义"
}
