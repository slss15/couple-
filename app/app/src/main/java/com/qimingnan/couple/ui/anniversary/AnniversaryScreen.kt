package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.anniversary.AnniversaryType
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Anniversary plugin screen, list slice (PR3).
 *
 * Pure display over [AnniversaryEvent]: the next occurrence, days until it,
 * completed age for birthdays, and the fallback/clamp notes that
 * [AnniversaryLogic] flags. The add/edit form (manual solar/lunar picker,
 * leap-month choice, dual-calendar preview) is the next slice of this PR;
 * it must reuse [AnniversaryLogic] and [LunarCalendar], not re-implement
 * the rules, and it must keep the original calendar as first-class data.
 */
@Composable
fun AnniversaryScreen(
    events: List<AnniversaryEvent>,
    today: LocalDate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
) {
    val t = LocalCoupleTokens.current
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
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
            if (live.isEmpty()) {
                Text("还没有纪念日，添加后会在这里倒数。", style = t.typography.body, color = t.colors.onSurfaceVariant)
            }
            live.forEach { event ->
                val next = AnniversaryLogic.nextOccurrence(event, today)
                SectionCard(event.title) {
                    Text(typeLabel(event), style = t.typography.caption, color = t.colors.onSurfaceVariant)
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
                    if (event.calendar == com.qimingnan.couple.data.anniversary.AnniversaryCalendar.LUNAR) {
                        Text(
                            "农历" + (if (event.isLeap) "闰" else "") + event.month + "月" + event.day + "日",
                            style = t.typography.caption, color = t.colors.onSurfaceVariant,
                        )
                    }
                    if (event.note.isNotBlank()) Text(event.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
            }
            Text(
                "阴历换算范围为 1900–2049，超出范围的日期不会被猜算。",
                style = t.typography.caption, color = t.colors.onSurfaceVariant,
            )
        }
    }
}

private fun typeLabel(event: AnniversaryEvent): String = when (event.type) {
    AnniversaryType.BIRTHDAY -> "生日"
    AnniversaryType.LOVE -> "恋爱纪念日"
    AnniversaryType.WEDDING -> "结婚纪念日"
    AnniversaryType.FIRST_MEETING -> "第一次见面"
    AnniversaryType.FESTIVAL -> "节日"
    AnniversaryType.CUSTOM -> "自定义"
}
