package com.qimingnan.couple.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.data.period.PeriodLog
import com.qimingnan.couple.data.period.PeriodLogic
import com.qimingnan.couple.data.period.Stats
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.GlyphAvatar
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

@Composable
fun PeriodScreen(vm: PeriodViewModel, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deletingLog by remember { mutableStateOf<PeriodLog?>(null) }

    // 内层 Scaffold：外层 MainShell 的 Scaffold 已给过一次状态栏留白，这里只留底部
    // 手势条 inset，不再重复加顶部留白，否则经期页最上方会多出一整段状态栏高度的空白。
    Scaffold(
        containerColor = t.colors.background,
        contentColor = t.colors.onBackground,
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = t.colors.primaryContainer,
                contentColor = t.colors.onPrimaryContainer,
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.period_add)) }
        },
    ) { padding ->
        Box(modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                // 底栏浮在内容上，列表末尾要留出它的高度。
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = t.spacing.md, end = t.spacing.md, top = t.spacing.md,
                    bottom = t.spacing.md + com.qimingnan.couple.ui.components.LocalBottomBarInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                item { StatusCard(state.stats) }
                item {
                    Text(stringResource(R.string.period_history_title), style = t.typography.headline, color = t.colors.onBackground)
                }
                if (state.logs.isEmpty()) {
                    item {
                        // 原来只有一行 caption，像一条错误提示而不是「这里还空着」。
                        // 居中给图标 + 标题 + 说明，第一次记录的入口才立得住。
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = t.spacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
                        ) {
                            Icon(
                                Icons.Filled.DateRange,
                                contentDescription = null,
                                tint = t.colors.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(stringResource(R.string.period_state_none), style = t.typography.title, color = t.colors.onSurface)
                            Text(
                                stringResource(R.string.period_empty),
                                style = t.typography.caption, color = t.colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                items(state.logs.asReversed(), key = { it.id }) { log ->
                    LogRow(log, onDelete = { deletingLog = log })
                }
                item {
                    Text(
                        stringResource(R.string.period_privacy_note),
                        style = t.typography.caption, color = t.colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = t.spacing.sm),
                    )
                }
            }
        }
    }

    if (adding) AddLogDialog(onSave = { s, e, note -> vm.addLog(s, e, note); adding = false }, onCancel = { adding = false })

    // 删除经期记录先二次确认：本机私密记录，点一下垃圾桶就直接删太容易误触。
    deletingLog?.let { log ->
        AlertDialog(
            onDismissRequest = { deletingLog = null },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    "确定删除 ${log.startDate} ~ ${log.endDate ?: "…"} 这条经期记录吗？删除后不能恢复。",
                    style = t.typography.body, color = t.colors.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteLog(log.id); deletingLog = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingLog = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatusCard(stats: Stats?) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.large, color = t.colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(t.spacing.xl), verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            if (stats == null || stats.lastStart == null) {
                Text(stringResource(R.string.period_state_none), style = t.typography.headline, color = t.colors.onPrimaryContainer)
                Text(stringResource(R.string.period_state_none_hint), style = t.typography.caption, color = t.colors.onPrimaryContainer)
            } else {
                // 原来三行同色同级的 caption 竖着堆在一起，状态、预测、统计分不出主次。
                // 改成「大数字 + 单位 + 说明」：状态数字 40sp 独占视线，说明只做 caption。
                val overdue = stats.overdueDays
                val (number, label) = when {
                    stats.inPeriod -> (stats.dayOfPeriod ?: 1) to "经期第"
                    overdue != null -> overdue to "预计推迟"
                    else -> (stats.daysUntilNext ?: 0) to "距离下次"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        number.toString(),
                        // 收掉 display 自带的 4sp 字距，数字和后面的单位才不会隔开一截。
                        style = t.typography.display.copy(letterSpacing = 0.sp),
                        color = t.colors.onPrimaryContainer,
                        maxLines = 1,
                    )
                    Text(
                        "天",
                        style = t.typography.label,
                        color = t.colors.onPrimaryContainer.copy(alpha = 0.86f),
                        modifier = Modifier.padding(start = t.spacing.xs, top = t.spacing.sm),
                    )
                    Spacer(Modifier.size(t.spacing.md))
                    Text(label, style = t.typography.caption, color = t.colors.onPrimaryContainer.copy(alpha = 0.86f))
                }
                // 预测日期与周期统计原来是两行，合并成一行 caption，回到「说明」的视觉层级。
                val next = stats.nextPredicted?.let { stringResource(R.string.period_predict, it.format(ISO)) }
                val stat = stringResource(R.string.period_stats, stats.cycleDays, stats.periodDays, stats.samples)
                Text(
                    listOfNotNull(next, stat).joinToString(" · "),
                    style = t.typography.caption,
                    color = t.colors.onPrimaryContainer.copy(alpha = 0.86f),
                )
            }
        }
    }
}

@Composable
private fun LogRow(log: PeriodLog, onDelete: () -> Unit) {
    val t = LocalCoupleTokens.current
    // 原来 "2026-01-01 ~ 2026-01-05 · 5" 是一整条拼接字符串，天数连单位都没有，
    // 和账本行的读法也不一致。改成账本行同构：日号字形 + "M月d日 – M月d日" + "共 N 天"。
    // LedgerEntryRow 不支持行尾按钮，所以手写同样的 Row 结构，保留原来的删除 IconButton。
    Surface(shape = t.shapes.medium, color = t.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            GlyphAvatar("%02d".format(log.start.dayOfMonth), modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                val startText = "${log.start.monthValue}月${log.start.dayOfMonth}日"
                val endText = log.end?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "至今"
                Text("$startText – $endText", style = t.typography.body, color = t.colors.onSurface)
                val days = log.end?.let { java.time.temporal.ChronoUnit.DAYS.between(log.start, it).toInt() + 1 }
                Text(
                    days?.let { "共 $it 天" } ?: stringResource(R.string.period_ongoing),
                    style = t.typography.caption, color = t.colors.onSurfaceVariant,
                )
                if (log.note.isNotEmpty()) {
                    Text(log.note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.feed_delete), tint = t.colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLogDialog(onSave: (LocalDate, LocalDate?, String) -> Unit, onCancel: () -> Unit) {
    val t = LocalCoupleTokens.current
    var start by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val error = start?.let { PeriodLogic.validate(it, end) }

    @Composable
    fun picker(state: androidx.compose.material3.DatePickerState, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                val millis = state.selectedDateMillis
                TextButton(
                    // Material3 的 selectedDateMillis 是「UTC 零点」。用 systemDefault 换算的话，
                    // 负偏移时区（美洲）会整体差一天；ScheduledScreen 里也是用 UTC。
                    onClick = { millis?.let { onPick(Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()) } },
                    enabled = millis != null,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = t.colors.surfaceContainer,
        title = { Text(stringResource(R.string.period_add), color = t.colors.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                DateField(stringResource(R.string.period_start), start?.format(ISO) ?: stringResource(R.string.period_pick_date)) { picking = Pick.START }
                DateField(stringResource(R.string.period_end), end?.format(ISO) ?: stringResource(R.string.period_pick_date)) { picking = Pick.END }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.period_note)) }, textStyle = t.typography.body)
                error?.let { Text(it, style = t.typography.caption, color = t.colors.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { start?.let { s -> onSave(s, end, note) } },
                enabled = start != null && error == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )

    when (picking) {
        Pick.START -> picker(rememberDatePickerState(), { start = it; picking = null }, { picking = null })
        Pick.END -> picker(rememberDatePickerState(), { end = it; picking = null }, { picking = null })
        null -> {}
    }
}

private enum class Pick { START, END }

@Composable
private fun DateField(label: String, value: String, onClick: () -> Unit) {
    val t = LocalCoupleTokens.current
    Surface(
        shape = t.shapes.medium, color = t.colors.surface,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(t.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = t.typography.body, color = t.colors.onSurfaceVariant)
        }
    }
}
