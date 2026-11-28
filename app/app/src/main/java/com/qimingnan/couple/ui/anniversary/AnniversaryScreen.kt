package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                AnniversaryEventCard(
                    event = event,
                    next = AnniversaryLogic.nextOccurrence(event, today),
                    today = today,
                    extras = extras,
                    showSurprise = event.id == nearest?.id,
                    onCheckIn = { anniversaryVm.checkIn(event.id, today.toString()) },
                    onEdit = { editing = event; editorOpen = true },
                    onDelete = { deleting = event },
                    onAdjustSurprise = { surpriseEvent = event },
                )
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
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                Text(event.title + " · " + progress.toInt() + "%")
                Slider(progress, { progress = it }, valueRange = 0f..100f, steps = 9)
                // 卡片上原来那句"准备进度只保存在本机"跟着控件走：底部只剩一行数字，说明留在它解释的地方。
                Text("准备进度只保存在本机。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
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

/**
 * 一张事件卡。原来从"类型/公历农历"到"编辑/删除"最多堆 10 行，"还有 N 天"被 6 行 caption 夹在中间，
 * 整页最该被看见的倒计时反而不突出。现在按信息优先级重排：
 * 左侧日期印章当锚点，右侧标题 + 一行状态，行尾是倒计时；其余信息按"必须看见 / 异常才出现 / 收进底部"三档归位。
 * 编辑/删除是低频操作，从卡片底部的两个文字按钮改成右上角图标，既省一行也不跟倒计时抢注意力。
 */
@Composable
private fun AnniversaryEventCard(
    event: AnniversaryEvent,
    next: Occurrence?,
    today: LocalDate,
    extras: AnniversaryExtras,
    showSurprise: Boolean,
    onCheckIn: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAdjustSurprise: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val days = next?.let { AnniversaryLogic.daysUntil(today, it) }
    val checked = extras.checkedIn(event.id, today.toString())
    val count = extras.checkinCount(event.id)
    val age = if (event.type == AnniversaryType.BIRTHDAY) AnniversaryLogic.age(event, today) else null
    val progress = if (showSurprise) extras.surprise(event.id).coerceIn(0, 100) else null
    val calendarLabel = when {
        event.calendar == AnniversaryCalendar.SOLAR -> "公历"
        event.isLeap -> "农历闰"
        else -> "农历"
    }
    val caption = anniversaryTypeLabel(event.type) + " · " + calendarLabel + event.month + "月" + event.day + "日 · " +
        if (event.recurring) "每年" else "一次性"
    // 正常情况这行不出现：日期印章 + 行尾倒计时已经说清了"下次哪天"。
    // 只有日期被规则改过（闰月回退 / 当月截断）或者压根没有下次日期时才需要解释，并且压成一行。
    val abnormal = if (next == null) {
        "已过去 / 无下次日期"
    } else {
        val reasons = listOfNotNull(
            if (next.leapFallback) "本年没有原闰月，按普通月同日" else null,
            if (next.clampedDay) "当月没有这一天，按当月最后一天" else null,
        )
        if (reasons.isEmpty()) null else "下次公历 " + next.date + " · " + reasons.joinToString("；")
    }
    SectionCard(title = null) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            DateStamp(date = next?.date, fallbackMonth = event.month, fallbackDay = event.day)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.title,
                        style = t.typography.title,
                        color = t.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onEdit, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = t.colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = t.colors.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        caption,
                        style = t.typography.caption,
                        color = t.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (days != null) CountdownText(days)
                }
            }
        }
        abnormal?.let { Text(it, style = t.typography.caption, color = t.colors.onSurfaceVariant) }
        if (event.note.isNotBlank()) Text(event.note, style = t.typography.body, color = t.colors.onSurfaceVariant)
        if (next?.date == today) {
            Button(onClick = onCheckIn, enabled = !checked) { Text(if (checked) "今天已打卡" else "今天打卡") }
        }
        // 底部只留数字：分隔线把操作区和统计区切开，一行 caption 收完打卡次数、周岁、惊喜进度。
        if (count > 0 || age != null || progress != null) {
            HorizontalDivider(thickness = 1.dp, color = t.colors.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    footerCaption(count, age, progress),
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (progress != null) {
                    TextButton(onClick = onAdjustSurprise) { Text("调整", style = t.typography.label, color = t.colors.primary) }
                }
            }
            if (progress != null) RatioBar(progress / 100f)
        }
    }
}

/**
 * 日期印章：46×52 的小方块，和 HomeScreen 的「下一个纪念日」用同一块 primaryContainer 材质。
 * 它在这里是"日期标签"而不是强调填充——强调只留给倒计时数字，一张卡里不放两个同等重量的色块。
 * 没有下次日期时退回事件本身的月日，印章不留空。
 */
@Composable
private fun DateStamp(date: LocalDate?, fallbackMonth: Int, fallbackDay: Int) {
    val t = LocalCoupleTokens.current
    Column(
        Modifier.width(46.dp).height(52.dp).clip(t.shapes.medium).background(t.colors.primaryContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            (date?.dayOfMonth ?: fallbackDay).toString().padStart(2, '0'),
            style = t.typography.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal),
            color = t.colors.onPrimaryContainer,
        )
        Text(
            (date?.monthValue ?: fallbackMonth).toString() + "月",
            style = t.typography.caption.copy(fontSize = 9.sp),
            color = t.colors.onPrimaryContainer,
        )
    }
}

/** 行尾倒计时：24sp primary，一张卡里唯一的强调数字；"天"缩到 caption，让数字本身最重。 */
@Composable
private fun CountdownText(days: Long) {
    val t = LocalCoupleTokens.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (days == 0L) "今天" else days.toString(),
            style = t.typography.display.copy(
                fontSize = 24.sp,
                lineHeight = 24.sp,
                letterSpacing = (-1).sp,
                fontWeight = FontWeight.Normal,
            ),
            color = t.colors.primary,
        )
        if (days > 0L) {
            Text(
                "天",
                style = t.typography.caption,
                color = t.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** 卡片底部那一行：打卡次数 / 周岁 / 惊喜进度压成一行，数字用 secondary 挑出来，其余字退到 onSurfaceVariant。 */
@Composable
private fun footerCaption(count: Int, age: Int?, progress: Int?): AnnotatedString {
    val t = LocalCoupleTokens.current
    val parts = buildList<Triple<String, String, String>> {
        if (count > 0) add(Triple("已珍藏", count.toString(), " 次打卡"))
        age?.let { add(Triple("周岁", it.toString(), " 岁")) }
        progress?.let { add(if (it == 100) Triple("神秘惊喜", "已准备好", "") else Triple("神秘惊喜", "$it%", "")) }
    }
    return buildAnnotatedString {
        parts.forEachIndexed { index, (label, value, suffix) ->
            if (index > 0) append(" · ")
            append(label + " ")
            withStyle(SpanStyle(color = t.colors.secondary)) { append(value) }
            append(suffix)
        }
    }
}
