package com.qimingnan.couple.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.DarkMode
import com.qimingnan.couple.data.settings.FontSize
import com.qimingnan.couple.data.settings.SettingsRepository
import com.qimingnan.couple.data.settings.TabVisibility
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.theme.ThemeSeeds
import com.qimingnan.couple.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings
    fun setTabHidden(id: String, hidden: Boolean) = viewModelScope.launch { repo.setTabHidden(id, hidden) }
    fun setSeed(id: String) = viewModelScope.launch { repo.setThemeSeed(id) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamicColor(on) }
    fun setDarkMode(m: DarkMode) = viewModelScope.launch { repo.setDarkMode(m) }
    fun setFontSize(f: FontSize) = viewModelScope.launch { repo.setFontSize(f) }
    fun setPluginEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repo.setPluginEnabled(id, enabled) }
}

private val TAB_LABELS = mapOf("tab_chat" to "聊天", "tab_feed" to "动态", "tab_ta" to "TA")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    exportVm: ChatExportViewModel = hiltViewModel(),
    notifyVm: NotifyViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = t.colors.onSurface) }
            Text("设置", style = t.typography.headline, color = t.colors.onSurface)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            SectionCard("底部 Tab") {
                TabVisibility.HIDEABLE.forEach { id ->
                    SwitchRow("显示「${TAB_LABELS[id]}」", checked = id !in s.hiddenTabs) { vm.setTabHidden(id, !it) }
                }
                SwitchRow("显示「我的」（不可隐藏）", checked = true, enabled = false) {}
            }
            SectionCard("主题色") {
                val dynAvail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                FlowRow(horizontalArrangement = Arrangement.spacedBy(t.spacing.md), verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    ThemeSeeds.all.forEach { seed ->
                        val selected = !s.dynamicColor && s.themeSeed == seed.id
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(t.spacing.minTouchTarget).clip(CircleShape)
                                    .background(Color(if (t.isDark) seed.dark.primary else seed.light.primary))
                                    .border(if (selected) 3.dp else 0.dp, t.colors.onSurface, CircleShape)
                                    .clickable { vm.setSeed(seed.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Filled.Check, contentDescription = null,
                                    tint = Color(if (t.isDark) seed.dark.onPrimary else seed.light.onPrimary))
                            }
                            Text(seed.label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        }
                    }
                }
                if (dynAvail) SwitchRow("系统动态取色（Android 12+）", checked = s.dynamicColor) { vm.setDynamic(it) }
            }
            SectionCard("深浅色") {
                ChipRow(DarkMode.entries, s.darkMode, { it.label }) { vm.setDarkMode(it) }
            }
            SectionCard("字号") {
                ChipRow(FontSize.entries, s.fontSize, { it.label }) { vm.setFontSize(it) }
                Text("预览：今天也想你 ❤", style = t.typography.body, color = t.colors.onSurface)
            }
            SectionCard("插件") {
                com.qimingnan.couple.data.plugin.PluginRegistry.all.forEach { plugin ->
                    val enabled = plugin.available && plugin.id !in s.disabledPlugins
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.title, style = t.typography.body, color = t.colors.onSurface)
                            Text(
                                if (plugin.available) plugin.description else plugin.description + "（即将推出）",
                                style = t.typography.caption, color = t.colors.onSurfaceVariant,
                            )
                            Text(
                                if (plugin.scope == com.qimingnan.couple.data.plugin.PluginScope.PERSONAL) "个人私密" else "情侣共享",
                                style = t.typography.caption, color = t.colors.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, enabled = plugin.available, onCheckedChange = { vm.setPluginEnabled(plugin.id, it) })
                    }
                }
                Text(
                    "关闭插件只隐藏入口，不删除数据；插件内容由后续单独的 PR 提供。",
                    style = t.typography.caption, color = t.colors.onSurfaceVariant,
                )
            }
            NotifySection(notifyVm)
            ChatExportSection(exportVm)
            UpdateMirrorSection()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t = LocalCoupleTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val t = LocalCoupleTokens.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onPick(item) }, label = { Text(label(item), style = t.typography.label) })
        }
    }
}
