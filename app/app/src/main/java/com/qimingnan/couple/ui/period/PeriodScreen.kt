package com.qimingnan.couple.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.data.period.PeriodLog
import com.qimingnan.couple.data.period.PeriodLogic
import com.qimingnan.couple.data.period.Stats
import com.qimingnan.couple.theme.LocalCoupleTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

@Composable
fun PeriodScreen(vm: PeriodViewModel, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = t.colors.background,
        contentColor = t.colors.onBackground,
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(t.spacing.md),
                verticalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                item { StatusCard(state.stats) }
                item {
                    Text(stringResource(R.string.period_history_title), style = t.typography.headline, color = t.colors.onBackground)
                }
                if (state.logs.isEmpty()) {
                    item {
                        Text(stringResource(R.string.period_empty), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                }
                items(state.logs.asReversed(), key = { it.id }) { log ->
                    LogRow(log, onDelete = { vm.deleteLog(log.id) })
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
}

@Composable
private fun StatusCard(stats: Stats?) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.large, color = t.colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
            when {
                stats == null || stats.lastStart == null -> {
                    Text(stringResource(R.string.period_state_none), style = t.typography.headline, color = t.colors.onPrimaryContainer)
                    Text(stringResource(R.string.period_state_none_hint), style = t.typography.caption, color = t.colors.onPrimaryContainer)
                }
                stats.inPeriod -> {
                    Text(
                        stringResource(R.string.period_state_in, stats.dayOfPeriod ?: 1),
                        style = t.typography.headline, color = t.colors.onPrimaryContainer,
                    )
                }
                stats.overdueDays != null -> {
                    Text(
                        stringResource(R.string.period_state_overdue, stats.overdueDays),
                        style = t.typography.headline, color = t.colors.onPrimaryContainer,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.period_state_next, stats.daysUntilNext ?: 0),
                        style = t.typography.headline, color = t.colors.onPrimaryContainer,
                    )
                }
            }
            val next = stats?.nextPredicted?.let { stringResource(R.string.period_predict, it.format(ISO)) }
            next?.let { Text(it, style = t.typography.caption, color = t.colors.onPrimaryContainer) }
            if (stats != null && stats.lastStart != null) {
                Text(
                    stringResource(R.string.period_stats, stats.cycleDays, stats.periodDays, stats.samples),
                    style = t.typography.caption, color = t.colors.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun LogRow(log: PeriodLog, onDelete: () -> Unit) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.medium, color = t.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(t.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val days = log.end?.let { java.time.temporal.ChronoUnit.DAYS.between(log.start, it).toInt() + 1 }
                    ?: stringResource(R.string.period_ongoing)
                Text(
                    "${log.startDate} ~ ${log.endDate ?: "…"} · " + days,
                    style = t.typography.body, color = t.colors.onSurface,
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
                    onClick = { millis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) } },
                    enabled = millis != null,
                ) { Text(stringResource(R.string.feed_send)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
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
            ) { Text(stringResource(R.string.feed_send)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.back)) } },
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
