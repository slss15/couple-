package com.qimingnan.couple.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
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

/** 底部 Tab：聊天已不在 Tab 栏（从「我的」进入），TA 先保留原样，插件各为独立 Tab。 */
enum class MainTab(val layoutId: String, @StringRes val label: Int, val icon: ImageVector, val pluginId: String? = null) {
    Feed("tab_feed", R.string.tab_feed, Icons.Filled.Home),
    Ta("tab_ta", R.string.tab_ta, Icons.Filled.Favorite),
    Me("me_entry", R.string.tab_me, Icons.Filled.Person),
    Anniversary("tab_anniversary", R.string.tab_anniversary, Icons.Filled.DateRange, "anniversary"),
    Ledger("tab_ledger", R.string.tab_ledger, Icons.Filled.AddCircle, "ledger"),
}

@Composable
fun MainShell(
    onRescan: () -> Unit,
    onOpenKeepAlive: () -> Unit = {},
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.Anniversary) }
    var ledgerSubTab by rememberSaveable { mutableStateOf(com.qimingnan.couple.ui.ledger.LedgerSubTab.Record) }
    var homeApplied by rememberSaveable { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showScheduled by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAnniversary by rememberSaveable { mutableStateOf(false) }
    val allIds = MainTab.entries.map { it.layoutId }
    val visibleIds = TabVisibility.visible(allIds, settings.hiddenTabs)
    val visibleTabs = MainTab.entries.filter { item ->
        item.layoutId in visibleIds &&
            (item.pluginId == null || com.qimingnan.couple.data.plugin.PluginRegistry.isEnabled(item.pluginId, settings.disabledPlugins))
    }
    // 首页：只在第一次加载设置时应用一次，之后用户自己切 Tab 不会被设置覆盖。
    LaunchedEffect(settings.homeTab) {
        if (!homeApplied) {
            val home = MainTab.entries.firstOrNull { it.layoutId == settings.homeTab }
            if (home != null && home in visibleTabs) tab = home
            homeApplied = true
        }
    }
    LaunchedEffect(settings.hiddenTabs, settings.disabledPlugins, tab) {
        val resolved = TabVisibility.resolveSelected(tab.layoutId, allIds, settings.hiddenTabs)
        val resolvedTab = MainTab.entries.first { it.layoutId == resolved }
        val pluginOk = resolvedTab.pluginId == null ||
            com.qimingnan.couple.data.plugin.PluginRegistry.isEnabled(resolvedTab.pluginId, settings.disabledPlugins)
        if (!pluginOk || resolvedTab !in visibleTabs) {
            val home = MainTab.entries.firstOrNull { it.layoutId == settings.homeTab && it in visibleTabs }
            tab = home ?: MainTab.Me
        } else if (resolved != tab.layoutId) {
            tab = resolvedTab
        }
    }
    androidx.activity.compose.BackHandler(enabled = showChat) { showChat = false }
    androidx.activity.compose.BackHandler(enabled = showScheduled) { showScheduled = false }
    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
    androidx.activity.compose.BackHandler(enabled = showAnniversary) { showAnniversary = false }
    // 进记账 Tab 默认回到中间的「记账」子 Tab；系统返回键在记账页里回到首页 Tab。
    androidx.activity.compose.BackHandler(enabled = tab == MainTab.Ledger) { tab = MainTab.Anniversary }
    LaunchedEffect(tab) { if (tab == MainTab.Ledger) ledgerSubTab = com.qimingnan.couple.ui.ledger.LedgerSubTab.Record }
    if (showChat) {
        com.qimingnan.couple.ui.chat.ChatScreen(
            vm = hiltViewModel(),
            onOpenScheduled = { showScheduled = true },
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
            onOpenLedger = { showSettings = false; tab = MainTab.Ledger; ledgerSubTab = com.qimingnan.couple.ui.ledger.LedgerSubTab.Record },
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
            if (tab == MainTab.Ledger) {
                // 记账专属底栏：顺序统计、记账、账户，记账在中间是默认页；主底栏在这里不显示。
                NavigationBar(containerColor = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
                    val ledgerTabs = listOf(
                        com.qimingnan.couple.ui.ledger.LedgerSubTab.Stats to Icons.Filled.DateRange,
                        com.qimingnan.couple.ui.ledger.LedgerSubTab.Record to Icons.Filled.AddCircle,
                        com.qimingnan.couple.ui.ledger.LedgerSubTab.Accounts to Icons.Filled.Person,
                    )
                    ledgerTabs.forEach { (sub, icon) ->
                        NavigationBarItem(
                            selected = ledgerSubTab == sub,
                            onClick = { ledgerSubTab = sub },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(sub.label, style = t.typography.caption) },
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
            } else {
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
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.Me -> MeScreen(onRescan = onRescan, onOpenKeepAlive = onOpenKeepAlive, onOpenSettings = { showSettings = true }, onOpenChat = { showChat = true }, modifier = Modifier.padding(padding))
            MainTab.Feed -> FeedScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Ta -> com.qimingnan.couple.ui.ta.TaScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Anniversary -> {
                val anniversaryVm: com.qimingnan.couple.ui.anniversary.AnniversaryViewModel = hiltViewModel()
                val anniversaryEvents by anniversaryVm.events.collectAsStateWithLifecycle()
                com.qimingnan.couple.ui.anniversary.AnniversaryScreen(
                    events = anniversaryEvents,
                    today = java.time.LocalDate.now(),
                    onBack = { tab = MainTab.Me },
                    showBack = false,
                    modifier = Modifier.padding(padding),
                )
            }
            MainTab.Ledger -> com.qimingnan.couple.ui.ledger.LedgerScreen(
                vm = hiltViewModel(),
                onBack = { tab = MainTab.Anniversary },
                showBack = true,
                subTab = ledgerSubTab,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
