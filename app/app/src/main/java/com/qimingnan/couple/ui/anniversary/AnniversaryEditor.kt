package com.qimingnan.couple.ui.anniversary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.data.anniversary.*
import com.qimingnan.couple.theme.LocalCoupleTokens
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnniversaryEditor(vm: AnniversaryViewModel, existing: AnniversaryEvent?, today: LocalDate, onDismiss: () -> Unit) {
    val t = LocalCoupleTokens.current
    val id = remember(existing?.id) { existing?.id ?: vm.newId() }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var type by rememberSaveable { mutableStateOf(existing?.type ?: AnniversaryType.CUSTOM) }
    var calendar by rememberSaveable { mutableStateOf(existing?.calendar ?: AnniversaryCalendar.SOLAR) }
    var year by rememberSaveable { mutableStateOf(existing?.let { it.year?.toString() ?: "" } ?: today.year.toString()) }
    var month by rememberSaveable { mutableStateOf((existing?.month ?: today.monthValue).toString()) }
    var day by rememberSaveable { mutableStateOf((existing?.day ?: today.dayOfMonth).toString()) }
    var leap by rememberSaveable { mutableStateOf(existing?.isLeap ?: false) }
    var onlyLeap by rememberSaveable { mutableStateOf(existing?.onlyLeapMonth ?: false) }
    var recurring by rememberSaveable { mutableStateOf(existing?.recurring ?: true) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidate = AnniversaryEvent(id, title.trim(), type, calendar, month.toIntOrNull() ?: 0, day.toIntOrNull() ?: 0,
        isLeap = calendar == AnniversaryCalendar.LUNAR && leap, year = year.toIntOrNull(), recurring = recurring,
        onlyLeapMonth = calendar == AnniversaryCalendar.LUNAR && leap && onlyLeap,
        reminderDaysAhead = existing?.reminderDaysAhead ?: 0, note = note.trim())
    val invalid = if (year.isNotBlank() && year.toIntOrNull() == null) "年份必须为数字" else AnniversaryLogic.validationError(candidate)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "新增纪念日" else "编辑纪念日") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnniversaryType.entries.forEach { value -> FilterChip(type == value, { type = value }, label = { Text(anniversaryTypeLabel(value)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(calendar == AnniversaryCalendar.SOLAR, { calendar = AnniversaryCalendar.SOLAR; leap = false; onlyLeap = false }, label = { Text("公历") })
                FilterChip(calendar == AnniversaryCalendar.LUNAR, { calendar = AnniversaryCalendar.LUNAR }, label = { Text("农历") })
            }
            Text(if (calendar == AnniversaryCalendar.LUNAR) "请填写原始农历日期（1900–2049）；不会把输入的日期偷偷改成公历。" else "请填写原始公历日期；每年重复的事件可以不填写年份。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(year, { year = it }, label = { Text("年（可空）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1.4f))
                OutlinedTextField(month, { month = it }, label = { Text("月") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(day, { day = it }, label = { Text("日") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            if (calendar == AnniversaryCalendar.LUNAR) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("原日期是闰月", Modifier.weight(1f)); Switch(leap, { leap = it; if (!it) onlyLeap = false }) }
                if (leap) Row(verticalAlignment = Alignment.CenterVertically) { Text("仅在有该闰月的年份提醒", Modifier.weight(1f)); Switch(onlyLeap, { onlyLeap = it }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("每年重复", Modifier.weight(1f)); Switch(recurring, { recurring = it }) }
            if (invalid == null) {
                val occurrence = AnniversaryLogic.nextOccurrence(candidate, today)
                Text(occurrence?.let { "下次公历日期：" + it.date } ?: "这个一次性事件已过去，或暂时没有可换算日期。", style = t.typography.caption, color = t.colors.primary)
                occurrence?.let { next ->
                    if (next.leapFallback) Text("下次无该闰月，按普通月同日。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    if (next.clampedDay) Text("当月没有这一天，按当月最后一天。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
            } else if (title.isNotBlank()) Text(invalid, style = t.typography.caption, color = t.colors.error)
            OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            error?.let { Text(it, color = t.colors.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        saving = true
        vm.save(candidate) { problem -> saving = false; error = problem; if (problem == null) onDismiss() }
    }, enabled = invalid == null && !saving) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}

internal fun anniversaryTypeLabel(type: AnniversaryType): String = when (type) {
    AnniversaryType.BIRTHDAY -> "生日"
    AnniversaryType.LOVE -> "恋爱纪念日"
    AnniversaryType.WEDDING -> "结婚纪念日"
    AnniversaryType.FIRST_MEETING -> "初次见面"
    AnniversaryType.FESTIVAL -> "节日"
    AnniversaryType.CUSTOM -> "自定义"
}
