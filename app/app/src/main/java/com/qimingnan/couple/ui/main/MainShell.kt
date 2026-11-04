package com.qimingnan.couple.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.chat.ChatScreen
import com.qimingnan.couple.ui.feed.FeedScreen
import com.qimingnan.couple.ui.me.MeScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.settings.TabVisibility
import com.qimingnan.couple.ui.settings.SettingsScreen
import com.qimingnan.couple.ui.settings.SettingsViewModel

/** 底部五个 Tab；前四个 id 与布局 JSON 的 bottomBar 条目一致（me_entry 为锁定项），插件 Tab 不进 LayoutRules 默认布局，以免破坏其容量校验。 */
enum class MainTab(val layoutId: String, @StringRes val label: Int, val icon: ImageVector) {
    Chat("tab_chat", R.string.tab_chat, Icons.Filled.Email),
    Feed("tab_feed", R.string.tab_feed, Icons.Filled.Home),
    Ta("tab_ta", R.string.tab_ta, Icons.Filled.Favorite),
    Me("me_entry", R.string.tab_me, Icons.Filled.Person),
    Plugins("tab_plugins", R.string.tab_plugins, Icons.Filled.Build),
}

@Composable
fun MainShell(
    onRescan: () -> Unit,
    onOpenKeepAlive: () -> Unit = {},
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.Chat) }
    var showScheduled by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAnniversary by rememberSaveable { mutableStateOf(false) }
    var showLedger by rememberSaveable { mutableStateOf(false) }
    val allIds = MainTab.entries.map { it.layoutId }
    val visibleIds = TabVisibility.visible(allIds, settings.hiddenTabs)
    val visibleTabs = MainTab.entries.filter { it.layoutId in visibleIds }
    LaunchedEffect(settings.hiddenTabs, tab) {
        val resolved = TabVisibility.resolveSelected(tab.layoutId, allIds, settings.hiddenTabs)
        if (resolved != tab.layoutId) tab = MainTab.entries.first { it.layoutId == resolved }
    }
    androidx.activity.compose.BackHandler(enabled = showScheduled) { showScheduled = false }
    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
    androidx.activity.compose.BackHandler(enabled = showAnniversary) { showAnniversary = false }
    androidx.activity.compose.BackHandler(enabled = showLedger) { showLedger = false }
    if (showLedger) {
        com.qimingnan.couple.ui.ledger.LedgerScreen(
            vm = hiltViewModel(),
            onBack = { showLedger = false },
            modifier = Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding(),
        )
        return
    }
    if (showAnniversary) {
        val anniversaryVm: com.qimingnan.couple.ui.anniversary.AnniversaryViewModel = hiltViewModel()
        val anniversaryEvents by anniversaryVm.events.collectAsStateWithLifecycle()
        com.qimingnan.couple.ui.anniversary.AnniversaryScreen(
            events = anniversaryEvents,
            today = java.time.LocalDate.now(),
            onBack = { showAnniversary = false },
            modifier = Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding(),
        )
        return
    }
    if (showSettings) {
        SettingsScreen(
            vm = settingsVm,
            onBack = { showSettings = false },
            onOpenAnniversary = { showSettings = false; showAnniversary = true },
            onOpenLedger = { showSettings = false; showLedger = true },
            modifier = Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding(),
        )
        return
    }
    if (showScheduled) {
        com.qimingnan.couple.ui.chat.ScheduledScreen(
            vm = hiltViewModel(),
            onBack = { showScheduled = false },
            modifier = Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding(),
        )
        return
    }
    Scaffold(
        containerColor = t.colors.background,
        contentColor = t.colors.onBackground,
        bottomBar = {
            NavigationBar(containerColor = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
                visibleTabs.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label), style = t.typography.caption) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.colors.onPrimaryContainer,
                            selectedTextColor = t.colors.primary,
                            indicatorColor = t.colors.primaryContainer,
                            unselectedIconColor = t.colors.onSurfaceVariant,
                            unselectedTextColor = t.colors.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.Me -> MeScreen(onRescan = onRescan, onOpenKeepAlive = onOpenKeepAlive, onOpenSettings = { showSettings = true }, modifier = Modifier.padding(padding))
            MainTab.Plugins -> com.qimingnan.couple.ui.plugin.PluginScreen(
                settingsVm = settingsVm,
                onOpenAnniversary = { showAnniversary = true },
                onOpenLedger = { showLedger = true },
                modifier = Modifier.padding(padding),
            )
            MainTab.Feed -> FeedScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Ta -> com.qimingnan.couple.ui.ta.TaScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Chat -> ChatScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding), onOpenScheduled = { showScheduled = true })
        }
    }
}
