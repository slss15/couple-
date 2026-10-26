package com.qimingnan.couple.ui.ta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qimingnan.couple.R
import com.qimingnan.couple.data.net.ImportantDay
import com.qimingnan.couple.theme.LocalCoupleTokens

@Composable
fun TaScreen(vm: TaViewModel, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var editingMe by rememberSaveable { mutableStateOf(false) }
    var addingDay by rememberSaveable { mutableStateOf(false) }

    Scaffold(containerColor = t.colors.background, floatingActionButton = {
        if (state.summary != null) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { addingDay = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ta_add_day)) },
                containerColor = t.colors.primaryContainer,
                contentColor = t.colors.onPrimaryContainer,
            )
        }
    }) { padding ->
        Box(modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.summary == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = t.colors.primary)
                }
                state.summary == null -> Column(
                    Modifier.fillMaxSize().padding(t.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.error ?: stringResource(R.string.placeholder_coming), style = t.typography.body, color = t.colors.onSurfaceVariant)
                    TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.retry)) }
                }
                else -> {
                    val s = state.summary!!
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(t.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(t.spacing.md),
                    ) {
                        item { PartnerCard(s.partner, state.partnerOnline, vm) }
                        s.anniversary?.let { ann ->
                            item { AnniversaryRow(days = (System.currentTimeMillis() - ann) / 86_400_000L) }
                        }
                        item {
                            Text(stringResource(R.string.ta_days_title), style = t.typography.headline, color = t.colors.onBackground)
                        }
                        if (s.days.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.ta_days_empty),
                                    style = t.typography.caption, color = t.colors.onSurfaceVariant,
                                )
                            }
                        }
                        items(s.days, key = { it.id }) { day ->
                            DayRow(day, canDelete = true, onDelete = { vm.deleteDay(day.id) })
                        }
                        item {
                            TextButton(onClick = { editingMe = true }) { Text(stringResource(R.string.ta_edit_me)) }
                        }
                    }
                }
            }
        }
    }

    if (editingMe) {
        val me = state.summary?.me
        if (me != null) EditMeDialog(me, onSave = { bio, bd, by, show ->
            vm.saveMe(bio, bd, by, show)
            editingMe = false
        }, onCancel = { editingMe = false })
    }
    if (addingDay) {
        DayDialog(existing = null, onSave = { name, m, d, y ->
            vm.addDay(name, m, d, y)
            addingDay = false
        }, onCancel = { addingDay = false })
    }
}

@Composable
private fun PartnerCard(p: com.qimingnan.couple.data.net.TaPartnerView, online: Boolean, vm: TaViewModel) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.large, color = t.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(t.spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                if (p.avatarUrl != null) {
                    AsyncImage(
                        model = vm.mediaUrlOf(p.avatarUrl),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(CircleShape),
                    )
                } else {
                    Surface(shape = CircleShape, color = t.colors.primaryContainer, modifier = Modifier.size(96.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = t.colors.primary, modifier = Modifier.size(40.dp))
                        }
                    }
                }
                // 在线状态圆点（presence 流）
                val dot = if (online) t.colors.primary else t.colors.onSurfaceVariant
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(dot),
                )
            }
            Spacer(Modifier.size(t.spacing.sm))
            Text(p.displayName, style = t.typography.headline, color = t.colors.onSurface)
            p.bio?.let { Spacer(Modifier.size(t.spacing.xs)); Text(it, style = t.typography.body, color = t.colors.onSurfaceVariant) }
            val bd = when {
                p.birthday != null -> stringResource(R.string.ta_birthday, p.birthday) +
                    (p.birthYear?.let { "（${java.time.LocalDate.now().year - it} 岁）" } ?: "")
                !p.birthdayVisible -> stringResource(R.string.ta_birthday_hidden)
                else -> null
            }
            bd?.let {
                Spacer(Modifier.size(t.spacing.xs))
                Text(it, style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AnniversaryRow(days: Long) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.medium, color = t.colors.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ta_days_together, days),
            style = t.typography.body, color = t.colors.onPrimaryContainer,
            modifier = Modifier.padding(t.spacing.md),
        )
    }
}

@Composable
private fun DayRow(day: ImportantDay, canDelete: Boolean, onDelete: () -> Unit) {
    val t = LocalCoupleTokens.current
    Surface(shape = t.shapes.medium, color = t.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(t.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(day.name, style = t.typography.body, color = t.colors.onSurface)
                val whenText = "%02d-%02d".format(day.month, day.day) + (day.year?.let { " · $it" } ?: "")
                val inDays = daysUntil(day.month, day.day, System.currentTimeMillis())
                Text(
                    whenText + stringResource(R.string.ta_days_until, inDays),
                    style = t.typography.caption, color = t.colors.onSurfaceVariant,
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.feed_delete), tint = t.colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EditMeDialog(me: com.qimingnan.couple.data.net.TaMeView, onSave: (String?, String?, Int?, Boolean) -> Unit, onCancel: () -> Unit) {
    val t = LocalCoupleTokens.current
    var bio by rememberSaveable { mutableStateOf(me.bio ?: "") }
    var birthday by rememberSaveable { mutableStateOf(me.birthday ?: "") }
    var birthYear by rememberSaveable { mutableStateOf(me.birthYear?.toString() ?: "") }
    var showBirthday by rememberSaveable { mutableStateOf(me.showBirthday) }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = t.colors.surfaceContainer,
        title = { Text(stringResource(R.string.ta_edit_me), color = t.colors.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                OutlinedTextField(bio, { bio = it }, label = { Text(stringResource(R.string.ta_bio)) }, textStyle = t.typography.body)
                OutlinedTextField(
                    birthday, { birthday = it },
                    label = { Text(stringResource(R.string.ta_birthday_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = t.typography.body,
                )
                OutlinedTextField(
                    birthYear, { birthYear = it },
                    label = { Text(stringResource(R.string.ta_birth_year)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = t.typography.body,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ta_show_birthday), style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                    Switch(checked = showBirthday, onCheckedChange = { showBirthday = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(bio, birthday, birthYear.toIntOrNull(), showBirthday)
            }) { Text(stringResource(R.string.feed_send)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.back)) } },
    )
}

@Composable
private fun DayDialog(existing: ImportantDay?, onSave: (String, Int, Int, Int?) -> Unit, onCancel: () -> Unit) {
    val t = LocalCoupleTokens.current
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var month by rememberSaveable { mutableStateOf(existing?.month?.toString() ?: "") }
    var day by rememberSaveable { mutableStateOf(existing?.day?.toString() ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year?.toString() ?: "") }
    val ok = name.isNotBlank() && month.toIntOrNull() in 1..12 && day.toIntOrNull() in 1..31

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = t.colors.surfaceContainer,
        title = { Text(stringResource(R.string.ta_add_day), color = t.colors.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ta_day_name)) }, textStyle = t.typography.body)
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    OutlinedTextField(
                        month, { month = it }, label = { Text(stringResource(R.string.ta_month)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = t.typography.body, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        day, { day = it }, label = { Text(stringResource(R.string.ta_day)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = t.typography.body, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        year, { year = it }, label = { Text(stringResource(R.string.ta_year_optional)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = t.typography.body, modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, month.toInt(), day.toInt(), year.toIntOrNull()) },
                enabled = ok,
            ) { Text(stringResource(R.string.feed_send)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.back)) } },
    )
}
