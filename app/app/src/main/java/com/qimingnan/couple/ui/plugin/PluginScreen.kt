package com.qimingnan.couple.ui.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.plugin.PluginRegistry
import com.qimingnan.couple.data.plugin.PluginScope
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.settings.SettingsViewModel

/**
 * Plugin tab page: the direct tab-bar entry for plugins.
 *
 * Lists only enabled plugins (available and not turned off in Settings);
 * tapping a row opens that plugin's fullscreen page, same as the
 * Settings -> Plugin entry. Disabling a plugin in Settings hides it
 * here too but never deletes its data.
 */
@Composable
fun PluginScreen(
    settingsVm: SettingsViewModel,
    onOpenAnniversary: () -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalCoupleTokens.current
    val s by settingsVm.settings.collectAsStateWithLifecycle()
    val enabled = PluginRegistry.enabled(s.disabledPlugins)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        Text("插件", style = t.typography.headline, color = t.colors.onSurface)
        SectionCard("全部插件") {
            if (enabled.isEmpty()) {
                Text(
                    "插件都已在设置里关闭，去「我的」→ 设置 → 插件里重新打开。",
                    style = t.typography.body,
                    color = t.colors.onSurfaceVariant,
                )
            }
            enabled.forEach { plugin ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (plugin.id == PluginRegistry.ANNIVERSARY) onOpenAnniversary()
                        if (plugin.id == PluginRegistry.LEDGER) onOpenLedger()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.title, style = t.typography.body, color = t.colors.onSurface)
                        Text(plugin.description, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        Text(
                            if (plugin.scope == PluginScope.PERSONAL) "个人私密" else "情侣共享",
                            style = t.typography.caption,
                            color = t.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "点开一个插件就是它的全屏页，返回回到插件列表。",
            style = t.typography.caption,
            color = t.colors.onSurfaceVariant,
        )
    }
}
