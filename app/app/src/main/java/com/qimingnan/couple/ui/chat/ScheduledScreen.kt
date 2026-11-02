package com.qimingnan.couple.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.R
import com.qimingnan.couple.data.chat.ScheduledRepository
import com.qimingnan.couple.data.net.ScheduleCalc
import com.qimingnan.couple.data.net.ScheduleRule
import com.qimingnan.couple.data.net.ScheduledMessage
import com.qimingnan.couple.theme.LocalCoupleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.inject.Inject

/** 定时消息列表 + 定时发送（App 只做文本）。规则存在服务端，失败直接提示。 */
@HiltViewModel
class ScheduledViewModel @Inject constructor(private val repo: ScheduledRepository) : ViewModel() {
    val items: StateFlow<List<ScheduledMessage>> = repo.items

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    fun refresh() {
        viewModelScope.launch { repo.refresh().onFailure { report(it) } }
    }

    fun setPaused(id: String, paused: Boolean) {
        viewModelScope.launch { repo.setPaused(id, paused).onFailure { report(it) } }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id).onFailure { report(it) } }
    }

    /** 创建文本定时消息；成功回调 onDone。 */
    fun createText(text: String, rule: ScheduleRule, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.createText(text, rule, ScheduleCalc.DEFAULT_TZ)
                .onSuccess { onDone() }
                .onFailure { report(it) }
        }
    }

    private fun report(e: Throwable) {
        _errors.tryEmit(e.message ?: e.javaClass.simpleName)
    }
}

@Composable
fun ScheduledScreen(vm: ScheduledViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val items by vm.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = remember { ZoneId.of(ScheduleCalc.DEFAULT_TZ) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Column(modifier.fillMaxSize()) {
        Surface(color = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs, vertical = t.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.sched_title), style = t.typography.title, color = t.colors.onSurface)
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sched_empty), style = t.typography.body, color = t.colors.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(t.spacing.md),
                verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
            ) {
                items(items, key = { it.id }) { m ->
                    val z = runCatching { ZoneId.of(m.tz) }.getOrDefault(zone)
                    Surface(color = t.colors.surfaceContainer, shape = t.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(t.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.text.ifBlank { "[${m.type}]" }, style = t.typography.body, color = t.colors.onSurface, maxLines = 2)
                                Text(ScheduleCalc.describe(m.rule, z), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                                Text(
                                    m.nextFireAt?.let { stringResource(R.string.sched_next, ScheduleCalc.format(it, z)) }
                                        ?: stringResource(R.string.sched_sent),
                                    style = t.typography.caption,
                                    color = if (m.nextFireAt == null) t.colors.onSurfaceVariant else t.colors.primary,
                                )
                            }
                            Switch(
                                checked = !m.paused,
                                enabled = m.nextFireAt != null,
                                onCheckedChange = { on -> vm.setPaused(m.id, !on) },
                            )
                            IconButton(onClick = { vm.delete(m.id) }, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sched_delete), tint = t.colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val KINDS = listOf(
    ScheduleRule.ONCE to R.string.sched_once,
    ScheduleRule.DAILY to R.string.sched_daily,
    ScheduleRule.WEEKLY to R.string.sched_weekly,
    ScheduleRule.YEARLY to R.string.sched_yearly,
)

/** 长按发送键弹出：选日期 + 时间 + 重复方式，调用 createText(inputText, rule, "Asia/Shanghai")。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSendDialog(text: String, vm: ScheduledViewModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val t = LocalCoupleTokens.current
    val context = LocalContext.current
    val zone = remember { ZoneId.of(ScheduleCalc.DEFAULT_TZ) }
    val start = remember { ZonedDateTime.now(zone).plusMinutes(5) }
    // DatePicker 以 UTC 午夜的毫秒表示所选日期
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    var kind by rememberSaveable { mutableStateOf(ScheduleRule.ONCE) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.errors.collect { busy = false; Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(t.spacing.md),
        title = { Text(stringResource(R.string.sched_send_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = t.typography.body, color = t.colors.onSurfaceVariant, maxLines = 2)
                Spacer(Modifier.height(t.spacing.sm))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    KINDS.forEachIndexed { i, (k, label) ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, KINDS.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                Spacer(Modifier.height(t.spacing.sm))
                if (kind != ScheduleRule.DAILY) {
                    DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimePicker(state = timeState)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    val date = dateState.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: LocalDate.now(zone)
                    val at = ZonedDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute), zone)
                    busy = true
                    vm.createText(text, ScheduleRule.fromPick(kind, at)) {
                        busy = false
                        Toast.makeText(context, context.getString(R.string.sched_created), Toast.LENGTH_SHORT).show()
                        onCreated()
                    }
                },
            ) { Text(stringResource(R.string.sched_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sched_cancel)) } },
    )
}
