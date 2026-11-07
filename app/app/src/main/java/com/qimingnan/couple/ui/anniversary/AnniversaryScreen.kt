package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.anniversary.*
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.ledger.LedgerViewModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** UI 参考 Stitch；日期仍使用原有公历/农历规则，扩展数据只存本机。 */
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
    val events by anniversaryVm.events.collectAsStateWithLifecycle()
    val extras by anniversaryVm.extras.collectAsStateWithLifecycle()
    val accounts by ledgerVm.accounts.collectAsStateWithLifecycle()
    val entries by ledgerVm.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AnniversaryEvent?>(null) }
    var deleting by remember { mutableStateOf<AnniversaryEvent?>(null) }
    var surpriseEvent by remember { mutableStateOf<AnniversaryEvent?>(null) }
    val live = events.filter { !it.deleted }.sortedBy { AnniversaryLogic.nextOccurrence(it, today)?.date ?: LocalDate.MAX }
    val nearest = live.firstOrNull { AnniversaryLogic.nextOccurrence(it, today) != null }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = t.colors.onSurface) }
            Text("纪念日", style = t.typography.headline, color = t.colors.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = null; editorOpen = true }) { Text("＋ 新增") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.lg)) {
            val start = live.filter { it.type == AnniversaryType.LOVE }.mapNotNull(::originalDate).minOrNull()
            Surface(color = t.colors.primaryContainer, shape = t.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(t.spacing.xl), verticalArrangement = Arrangement.spacedBy(t.spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("把值得记住的日子，放在心上", style = t.typography.label, color = t.colors.onPrimaryContainer)
                    if (start != null) {
                        val days = ChronoUnit.DAYS.between(start, today)
                        Text(if (days >= 0) "已经在一起" else "距离在一起还有", style = t.typography.body, color = t.colors.onPrimaryContainer)
                        Text(kotlin.math.abs(days).toString() + " 天", style = t.typography.display, color = t.colors.onPrimaryContainer)
                        Text("从 $start 开始", style = t.typography.caption, color = t.colors.onPrimaryContainer)
                    } else {
                        Text("每一天都值得珍藏", style = t.typography.headline, color = t.colors.onPrimaryContainer)
                        Text("新增一个带年份的恋爱纪念日，记录在一起的天数。", style = t.typography.body, color = t.colors.onPrimaryContainer)
                    }
                    Text("公历 · 农历双轨记录", style = t.typography.caption, color = t.colors.onPrimaryContainer)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                listOf("全部", "生日", "恋爱", "其他").forEach { label ->
                    val count = live.count { matchesFilter(it, label) }
                    FilterChip(filter == label, { filter = label }, label = { Text("$label ($count)") })
                }
            }
            val shown = live.filter { matchesFilter(it, filter) }
            if (shown.isEmpty()) SectionCard("还没有这个分类的纪念日") {
                Text("记下生日、第一次见面，或下一次想一起出发的日子。", style = t.typography.body, color = t.colors.onSurfaceVariant)
                TextButton(onClick = { editing = null; editorOpen = true }) { Text("新增纪念日") }
            }
            shown.forEach { event -> key(event.id) {
                val next = AnniversaryLogic.nextOccurrence(event, today)
                SectionCard(event.title) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(anniversaryTypeLabel(event.type) + " · " + if (event.calendar == AnniversaryCalendar.SOLAR) "公历" else "农历" + if (event.isLeap) "闰月" else "", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                            Text(event.month.toString() + "月" + event.day + "日" + if (event.recurring) " · 每年" else " · 一次性", style = t.typography.body, color = t.colors.onSurface)
                        }
                        if (next != null) {
                            val days = AnniversaryLogic.daysUntil(today, next)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (days == 0L) "今天" else "还有", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                Text(if (days == 0L) "就是今天" else "$days 天", style = t.typography.headline, color = t.colors.primary)
                            }
                        } else Text("已过去 / 无下次日期", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    next?.let {
                        Text("下次公历：" + it.date, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        if (it.leapFallback) Text("本年没有原闰月，按普通月同日。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        if (it.clampedDay) Text("当月没有这一天，按当月最后一天。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    if (event.type == AnniversaryType.BIRTHDAY) AnniversaryLogic.age(event, today)?.let { age -> Text("周岁 $age 岁", style = t.typography.caption, color = t.colors.onSurfaceVariant) }
                    if (event.note.isNotBlank()) Text(event.note, style = t.typography.body, color = t.colors.onSurfaceVariant)
                    if (next?.date == today) {
                        val checked = extras.checkedIn(event.id, today.toString())
                        Button(onClick = { anniversaryVm.checkIn(event.id, today.toString()) }, enabled = !checked) { Text(if (checked) "今天已打卡" else "今天打卡") }
                    }
                    val count = extras.checkinCount(event.id)
                    if (count > 0) Text("已珍藏 $count 次打卡", style = t.typography.caption, color = t.colors.secondary)
                    if (event.id == nearest?.id) {
                        val progress = extras.surprise(event.id).coerceIn(0, 100)
                        Text(if (progress == 100) "神秘惊喜 · 已准备好" else "神秘惊喜 · 准备 $progress%", style = t.typography.label, color = t.colors.onSurface)
                        RatioBar(progress / 100f)
                        TextButton(onClick = { surpriseEvent = event }) { Text("调整准备进度") }
                        Text("准备进度只保存在本机。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    Row {
                        TextButton(onClick = { editing = event; editorOpen = true }) { Text("编辑") }
                        TextButton(onClick = { deleting = event }) { Text("删除") }
                    }
                }
            } }
            TravelPlanCard(anniversaryVm, extras, accounts, entries)
            Text("农历换算范围 1900–2049；范围外不猜算。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        }
    }
    if (editorOpen) AnniversaryEditor(anniversaryVm, editing, today, onDismiss = { editorOpen = false })
    deleting?.let { event -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除纪念日？") }, text = { Text("删除「" + event.title + "」？不会影响个人账本或旅行账户余额。") },
        confirmButton = { TextButton(onClick = { anniversaryVm.delete(event.id); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    surpriseEvent?.let { event ->
        var progress by remember(event.id) { mutableFloatStateOf(extras.surprise(event.id).toFloat()) }
        AlertDialog(onDismissRequest = { surpriseEvent = null }, title = { Text("神秘惊喜准备进度") }, text = {
            Column { Text(event.title + " · " + progress.toInt() + "%"); Slider(progress, { progress = it }, valueRange = 0f..100f, steps = 9) }
        }, confirmButton = { TextButton(onClick = { anniversaryVm.setSurpriseProgress(event.id, progress.toInt()); surpriseEvent = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { surpriseEvent = null }) { Text("取消") } })
    }
}

private fun matchesFilter(event: AnniversaryEvent, filter: String): Boolean = when (filter) {
    "生日" -> event.type == AnniversaryType.BIRTHDAY
    "恋爱" -> event.type == AnniversaryType.LOVE
    "其他" -> event.type != AnniversaryType.BIRTHDAY && event.type != AnniversaryType.LOVE
    else -> true
}

private fun originalDate(event: AnniversaryEvent): LocalDate? = event.year?.let { year -> runCatching {
    if (event.calendar == AnniversaryCalendar.SOLAR) {
        if (event.month == 2 && event.day == 29 && !LocalDate.of(year, 1, 1).isLeapYear) LocalDate.of(year, 2, 28)
        else LocalDate.of(year, event.month, event.day)
    } else LunarCalendar.lunarToSolar(LunarDate(year, event.month, event.day, event.isLeap))
}.getOrNull() }
