package com.qimingnan.couple.ui.settings

import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.Surface
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
import com.qimingnan.couple.data.update.ShizukuState
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.theme.ThemeSeeds
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.SectionCard
import androidx.compose.material3.TextButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings
    fun setTabHidden(id: String, hidden: Boolean) = viewModelScope.launch { repo.setTabHidden(id, hidden) }
    fun setHomeTab(id: String) = viewModelScope.launch { repo.setHomeTab(id) }
    suspend fun initialSettings(): AppSettings = repo.initialSettings()
    fun setRetainRecentTask(on: Boolean) = viewModelScope.launch { repo.setRetainRecentTask(on) }
    fun setSeed(id: String) = viewModelScope.launch { repo.setThemeSeed(id) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamicColor(on) }
    fun setDarkMode(m: DarkMode) = viewModelScope.launch { repo.setDarkMode(m) }
    fun setFontSize(f: FontSize) = viewModelScope.launch { repo.setFontSize(f) }
    fun setPluginEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repo.setPluginEnabled(id, enabled) }
    fun setMonthlyBudget(cents: Long) = viewModelScope.launch { repo.setMonthlyBudget(cents) }
    fun moveTab(id: String, delta: Int) = viewModelScope.launch { repo.moveTabOrder(id, delta) }
    fun moveAccount(id: String, delta: Int, allIds: List<String>) =
        viewModelScope.launch { repo.moveAccountOrder(id, delta, allIds) }
}

/**
 * 常驻连接开关：复用已有的 KeepAliveController（前台服务 + 静音通知），
 * 不新引库；开关状态存在 SharedPreferences 里，App 启动时按它恢复。
 */
@HiltViewModel
class KeepAliveSettingsViewModel @Inject constructor(
    private val controller: com.qimingnan.couple.data.keepalive.KeepAliveController,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = controller.enabled
    fun setEnabled(on: Boolean) = controller.setEnabled(on)
}

private val TAB_LABELS = mapOf("tab_feed" to "动态", "tab_ta" to "TA", "tab_anniversary" to "纪念日", "tab_ledger" to "记账")
/** 默认启动页：含独立首页（打开 App 先看到的启动页）和动态等 5 个 Tab。 */
private val HOME_TAB_LABELS = mapOf(
    "home_page" to "首页（启动页）",
    "tab_feed" to "动态",
    "tab_ta" to "TA",
    "me_entry" to "我的",
    "tab_anniversary" to "纪念日",
    "tab_ledger" to "记账",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAnniversary: () -> Unit = {},
    onOpenLedger: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    modifier: Modifier = Modifier,
    exportVm: ChatExportViewModel = hiltViewModel(),
    notifyVm: NotifyViewModel = hiltViewModel(),
    updateVm: com.qimingnan.couple.ui.update.UpdateViewModel = hiltViewModel(),
    keepAliveVm: KeepAliveSettingsViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val upd by updateVm.state.collectAsStateWithLifecycle()
    val keepAliveOn by keepAliveVm.enabled.collectAsStateWithLifecycle()
    var notificationDenied by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationDenied = !granted
        keepAliveVm.setEnabled(granted)
    }
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
                Text("最多显示 5 个，隐藏的 Tab 不在主底栏出现。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                TabVisibility.HIDEABLE.forEach { id ->
                    SwitchRow("显示「${TAB_LABELS[id]}」", checked = id !in s.hiddenTabs) { vm.setTabHidden(id, !it) }
                }
                SwitchRow("显示「我的」（不可隐藏）", checked = true, enabled = false) {}
            }
            SectionCard("底部 Tab 顺序") {
                Text("点 ↑ ↓ 调整底栏从左到右的顺序，改完立即保存。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                val orderLabels = mapOf("tab_feed" to "动态", "tab_ta" to "TA", "me_entry" to "我的", "tab_anniversary" to "纪念日", "tab_ledger" to "记账")
                s.tabOrder.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${orderLabels[id] ?: id}", style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.moveTab(id, -1) }, enabled = index > 0) { Text("↑ 上移") }
                        TextButton(onClick = { vm.moveTab(id, 1) }, enabled = index < s.tabOrder.lastIndex) { Text("↓ 下移") }
                    }
                }
                Text("实时预览", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    if (s.homeTab == "home_page") {
                        Surface(color = t.colors.primaryContainer, shape = t.shapes.pill) {
                            Text("首页", style = t.typography.label, color = t.colors.onPrimaryContainer, modifier = Modifier.padding(horizontal = t.spacing.md, vertical = t.spacing.sm))
                        }
                    }
                    s.tabOrder.filter { it == TabVisibility.LOCKED || it !in s.hiddenTabs }.forEach { id ->
                        val selected = s.homeTab == id
                        Surface(
                            color = if (selected) t.colors.primaryContainer else t.colors.surfaceContainer,
                            shape = t.shapes.pill,
                        ) {
                            Text(
                                orderLabels[id] ?: id,
                                style = t.typography.label,
                                color = if (selected) t.colors.onPrimaryContainer else t.colors.onSurface,
                                modifier = Modifier.padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                            )
                        }
                    }
                }
                Text("高亮的是默认启动页；未设置时默认打开动态。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            SectionCard("默认启动页") {
                Text("打开 App 时首先显示哪个页面：选「首页（启动页）」就是独立的首页，也可以选动态等任意一个 Tab。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(t.spacing.md), verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    HOME_TAB_LABELS.forEach { (id, label) ->
                        val pluginId = when (id) { "tab_ledger" -> "ledger"; "tab_anniversary" -> "anniversary"; else -> null }
                        val available = id == "home_page" || (id !in s.hiddenTabs && (pluginId == null || pluginId !in s.disabledPlugins))
                        FilterChip(selected = s.homeTab == id, enabled = available, onClick = { vm.setHomeTab(id) }, label = { Text(label) })
                    }
                }
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
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = plugin.available) {
                            if (plugin.id == com.qimingnan.couple.data.plugin.PluginRegistry.ANNIVERSARY) onOpenAnniversary()
                            if (plugin.id == com.qimingnan.couple.data.plugin.PluginRegistry.LEDGER) onOpenLedger()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
            SectionCard("静默安装（Shizuku）") {
                Text(
                    when (upd.shizuku) {
                        ShizukuState.AUTHORIZED -> "Shizuku 已授权：有新版本时可静默安装，不用再点系统安装确认。"
                        ShizukuState.NOT_AUTHORIZED -> "Shizuku 未授权：更新时走系统安装确认。"
                        ShizukuState.UNSUPPORTED -> "Shizuku 版本过旧，请先升级 Shizuku。"
                        ShizukuState.NOT_RUNNING -> "未检测到正在运行的 Shizuku：更新时走系统安装确认。"
                    },
                    style = t.typography.body, color = t.colors.onSurface,
                )
                if (upd.shizuku == ShizukuState.NOT_AUTHORIZED) {
                    SecondaryButton("授权 Shizuku 静默安装", onClick = updateVm::requestShizukuPermission)
                }
                Text("同一份授权也用于后台保活的一键豁免（我的 → 后台保活）。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            SectionCard("后台与任务保留") {
                SwitchRow("常驻连接 · 静音通知", checked = keepAliveOn) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else keepAliveVm.setEnabled(on)
                }
                Text("使用前台服务尽量保持消息连接；关闭后撤掉常驻通知。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                if (notificationDenied) Text("通知权限未允许，暂未开启常驻连接。可在后台保活页重新授权。", style = t.typography.caption, color = t.colors.error)
                SwitchRow("在最近任务列表保留", checked = s.retainRecentTask, onChange = vm::setRetainRecentTask)
                Text("显示在系统最近任务列表；可在支持的手机上手动锁定任务卡片。不能阻止手动划掉、强行停止或系统回收。", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                SecondaryButton("后台保活 · Shizuku 豁免与诊断", onClick = onOpenKeepAlive)
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
