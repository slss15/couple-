package com.qimingnan.couple.ui.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.chat.ChatScreen
import com.qimingnan.couple.ui.feed.FeedScreen
import com.qimingnan.couple.ui.ledger.LedgerSubTab
import com.qimingnan.couple.ui.me.MeScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.TabVisibility
import com.qimingnan.couple.layout.LayoutResolver
import com.qimingnan.couple.ui.settings.SettingsScreen
import com.qimingnan.couple.ui.settings.SettingsViewModel

/** 底部 Tab：聊天模块保留，入口默认隐藏；P5 后顺序与显隐由服务端布局下发。 */
enum class MainTab(val layoutId: String, @StringRes val label: Int, val icon: ImageVector, val pluginId: String? = null) {
    /** 首页是打开 App 的第一屏（calm 版），默认底栏只有 首页/动态/记账/我的 四项。 */
    Home("tab_home", R.string.tab_home, Icons.Filled.Home),
    Feed("tab_feed", R.string.tab_feed, Icons.Filled.Favorite),
    Ta("tab_ta", R.string.tab_ta, Icons.Filled.FavoriteBorder),
    Me("me_entry", R.string.tab_me, Icons.Filled.Person),
    Anniversary("tab_anniversary", R.string.tab_anniversary, Icons.Filled.DateRange, "anniversary"),
    Ledger("tab_ledger", R.string.tab_ledger, Icons.Filled.AddCircle, "ledger"),
    Period("tab_period", R.string.tab_period, Icons.Filled.Star, "period"),
}

private val LEDGER_SECONDARY_ITEMS = listOf(
    SecondaryTabItem("stats", "统计", Icons.Filled.DateRange),
    SecondaryTabItem("detail", "明细", Icons.AutoMirrored.Filled.List),
    SecondaryTabItem("accounts", "账户", Icons.Filled.Person),
)

@Composable
fun MainShell(
    onRescan: () -> Unit,
    onOpenKeepAlive: () -> Unit = {},
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val appearance by settingsVm.appearance.collectAsStateWithLifecycle()
    val layout = appearance.appearance.layout
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    /** 首页快捷入口可能指向默认隐藏的 Tab（TA / 纪念日 / 经期）：临时「钉住」该页，返回即回首页。 */
    var pinnedTab by rememberSaveable { mutableStateOf<MainTab?>(null) }
    var openLedgerWizard by rememberSaveable { mutableStateOf(false) }
    var lastLedgerRecordTapAt by rememberSaveable { mutableStateOf(0L) }
    var ledgerSubTab by rememberSaveable { mutableStateOf(LedgerSubTab.Stats) }
    val haptics = LocalHapticFeedback.current
    var secondaryMenu by remember { mutableStateOf<SecondaryTabMenu?>(null) }
    var secondaryMenuVisible by remember { mutableStateOf(false) }
    var homeApplied by rememberSaveable { mutableStateOf(false) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showScheduled by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAnniversary by rememberSaveable { mutableStateOf(false) }
    val allIds = MainTab.entries.map { it.layoutId }
    val defaultTabOrder = AppSettings.DEFAULT_TAB_ORDER
    val orderedIds = LayoutResolver.resolveTabOrder(
        layout, settings.tabOrder, settings.tabOrderCustomized, allIds, defaultTabOrder,
    )
    val visibleIds = TabVisibility.visible(allIds, settings.hiddenTabs).filter { it in orderedIds }
    val visibleTabs = MainTab.entries.filter { item ->
        item.layoutId in visibleIds &&
            (item.pluginId == null || com.qimingnan.couple.data.plugin.PluginRegistry.isEnabled(item.pluginId, settings.disabledPlugins))
    }.sortedBy { orderedIds.indexOf(it.layoutId) }

    fun showLedgerSecondaryMenu() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        secondaryMenu = SecondaryTabMenu(LEDGER_SECONDARY_ITEMS, ledgerSubTab.ordinal)
        secondaryMenuVisible = true
    }
    fun moveLedgerSecondaryMenu(index: Int) {
        val current = secondaryMenu ?: return
        if (current.selectedIndex == index) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        secondaryMenu = current.copy(selectedIndex = index)
    }
    fun closeLedgerSecondaryMenu() {
        secondaryMenuVisible = false
    }
    fun commitLedgerSecondaryMenu(index: Int, switchToLedger: Boolean) {
        ledgerSubTab = LedgerSubTab.entries[index.coerceIn(0, LedgerSubTab.entries.lastIndex)]
        if (switchToLedger) {
            tab = MainTab.Ledger
            openLedgerWizard = false
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        secondaryMenuVisible = false
    }
    // 等持久设置读完再应用一次；用户切换 Tab 后不被 StateFlow 默认值覆盖。
    LaunchedEffect(Unit) {
        if (!homeApplied) {
            val initial = settingsVm.initialSettings()
            val initialOrder = LayoutResolver.resolveTabOrder(
                layout, initial.tabOrder, initial.tabOrderCustomized, allIds, defaultTabOrder,
            )
            val initialVisible = MainTab.entries.filter { item ->
                item.layoutId in TabVisibility.visible(allIds, initial.hiddenTabs) &&
                    item.layoutId in initialOrder &&
                    (item.pluginId == null || item.pluginId !in initial.disabledPlugins)
            }.map { it.layoutId }
            val destination = com.qimingnan.couple.data.settings.StartupPage.resolve(initial.homeTab, initialVisible)
            tab = MainTab.entries.firstOrNull { it.layoutId == destination } ?: MainTab.Home
            homeApplied = true
        }
    }
    if (!homeApplied) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(t.colors.background), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = t.colors.primary)
        }
        return
    }
    LaunchedEffect(settings.hiddenTabs, settings.disabledPlugins, layout, tab) {
        // 快捷入口临时打开的隐藏页不受「可见 Tab」约束，否则会被立刻弹回首页。
        if (tab == pinnedTab) return@LaunchedEffect
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
    // 返回键的终点 = 用户设的启动页（在可见 Tab 里解析）。
    // 不能硬编码 Home：首页被隐藏时 Home 不在 visibleTabs，下面那个 LaunchedEffect
    // 会立刻把 tab 弹回去，于是每次返回都只是 Home→Me 空转，永远退不出 App。
    val startTab = MainTab.entries.firstOrNull {
        it.layoutId == com.qimingnan.couple.data.settings.StartupPage.resolve(settings.homeTab, visibleTabs.map { t -> t.layoutId }) &&
            it != MainTab.Ledger
    } ?: visibleTabs.firstOrNull() ?: MainTab.Me
    fun returnToStart() {
        pinnedTab = null
        tab = startTab
        openLedgerWizard = false
    }
    androidx.activity.compose.BackHandler(enabled = secondaryMenuVisible) { closeLedgerSecondaryMenu() }
    androidx.activity.compose.BackHandler(
        enabled = !secondaryMenuVisible &&
            (showChat || showScheduled || showSettings || showAnniversary || pinnedTab != null || tab != startTab),
    ) {
        when {
            showScheduled -> showScheduled = false
            showChat -> showChat = false
            // 纪念日全屏在设置页之上：先关纪念日回设置页，再按一次才关设置。
            // 顺序不能反——showAnniversary 和 showSettings 同时为真时，先判设置会把
            // 设置关掉、纪念日还盖在上面，得多按一次返回才回到设置页。
            showAnniversary -> showAnniversary = false
            showSettings -> showSettings = false
            else -> returnToStart()
        }
    }
    LaunchedEffect(tab) {
        if (tab != MainTab.Ledger) {
            openLedgerWizard = false
            secondaryMenuVisible = false
        }
    }
    if (showChat) {
        com.qimingnan.couple.ui.chat.ChatScreen(
            vm = hiltViewModel(),
            // 必须先关聊天页：下面是 if (showChat) { ...; return }，只置 showScheduled
            // 的话 ScheduledScreen 永远走不到，点「定时消息」没反应、返回键还要按两次。
            onOpenScheduled = { showChat = false; showScheduled = true },
            modifier = Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding(),
        )
        return
    }
    if (showAnniversary) {
        com.qimingnan.couple.ui.anniversary.AnniversaryScreen(
            anniversaryVm = hiltViewModel(),
            ledgerVm = hiltViewModel(),
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
            // 从设置页打开纪念日全屏时保留 showSettings：纪念日盖在设置页上面，
            // 返回键先关纪念日回设置页（BackHandler 里纪念日优先于设置），而不是掉回底栏主页。
            onOpenAnniversary = { showAnniversary = true },
            onOpenLedger = { showSettings = false; tab = MainTab.Ledger; ledgerSubTab = LedgerSubTab.Stats },
            onOpenKeepAlive = onOpenKeepAlive,
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
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = t.colors.background,
            contentColor = t.colors.onBackground,
            bottomBar = {
                // calm 版只保留一套底栏：记账的二级页改到页面顶部三段切换，
                // 长按「记账」图标仍可弹出覆盖底栏的二级页浮层。
                GlassNavigationBar(
                    style = settings.glassStyle,
                    itemCount = visibleTabs.size,
                    onDragSelect = { index ->
                        visibleTabs.getOrNull(index)?.let { item ->
                            if (item != tab) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (item == MainTab.Ledger) ledgerSubTab = LedgerSubTab.Stats
                                pinnedTab = null
                                tab = item
                            }
                        }
                    },
                ) {
                    visibleTabs.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                if (item == MainTab.Ledger) {
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    val doubleTap = now - lastLedgerRecordTapAt in 1..350
                                    if (tab != MainTab.Ledger) ledgerSubTab = LedgerSubTab.Stats
                                    if (doubleTap) {
                                        // 双击「记账」直接拉开记一笔抽屉（不再占一个二级页）。
                                        openLedgerWizard = true
                                        lastLedgerRecordTapAt = 0L
                                    } else {
                                        lastLedgerRecordTapAt = now
                                    }
                                }
                                pinnedTab = null
                                tab = item
                            },
                            icon = {
                                Icon(
                                    item.icon,
                                    contentDescription = null,
                                    modifier = if (item == MainTab.Ledger) {
                                        // 长按「记账」图标：弹出覆盖底栏的二级页浮层（统计/账户）。
                                        Modifier.secondaryTabLongPress(
                                            enabled = true,
                                            itemCount = LEDGER_SECONDARY_ITEMS.size,
                                            initialIndex = ledgerSubTab.ordinal,
                                            onOpen = ::showLedgerSecondaryMenu,
                                            onMove = ::moveLedgerSecondaryMenu,
                                            onFinish = { index -> commitLedgerSecondaryMenu(index, switchToLedger = true) },
                                            onCancel = ::closeLedgerSecondaryMenu,
                                        )
                                    } else Modifier,
                                )
                            },
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
            // 平板/横屏下把内容限制在 560dp 并居中：卡片全是 fillMaxWidth，不限制会被
            // 拉成一整行很宽的条，文字行长过百 dp 很难读。手机上 560 > 屏宽，等于没变化。
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.TopCenter,
            ) {
                val contentModifier = Modifier.fillMaxSize().widthIn(max = 560.dp)
                when (tab) {
                    MainTab.Home -> com.qimingnan.couple.ui.home.HomeScreen(
                        anniversaryVm = hiltViewModel(),
                        ledgerVm = hiltViewModel(),
                        meVm = hiltViewModel(),
                        onRecord = { pinnedTab = null; tab = MainTab.Ledger; ledgerSubTab = LedgerSubTab.Stats; openLedgerWizard = true },
                        onOpenTa = { pinnedTab = MainTab.Ta; tab = MainTab.Ta },
                        onOpenAnniversary = { pinnedTab = MainTab.Anniversary; tab = MainTab.Anniversary },
                        onOpenPeriod = { pinnedTab = MainTab.Period; tab = MainTab.Period },
                        onOpenLedger = { pinnedTab = null; tab = MainTab.Ledger; ledgerSubTab = LedgerSubTab.Stats },
                        onOpenFeed = { pinnedTab = null; tab = MainTab.Feed },
                        modifier = contentModifier,
                    )
                    MainTab.Me -> MeScreen(
                        onRescan = onRescan,
                        onOpenKeepAlive = onOpenKeepAlive,
                        onOpenSettings = { showSettings = true },
                        onOpenChat = { showChat = true },
                        sectionOrder = LayoutResolver.visibleIds(
                            layout, "meSections",
                            listOf("settings", "profile", "couple", "mqtt", "server", "updates"),
                        ),
                        modifier = contentModifier,
                    )
                    MainTab.Feed -> FeedScreen(
                        vm = hiltViewModel(),
                        showAnniversaryCard = LayoutResolver.isVisible(layout, "feedHeader", "plugin:anniversary"),
                        modifier = contentModifier,
                    )
                    MainTab.Ta -> com.qimingnan.couple.ui.ta.TaScreen(
                        vm = hiltViewModel(),
                        cardOrder = LayoutResolver.visibleIds(layout, "taCards", listOf("partner_card", "anniversary", "days")),
                        modifier = contentModifier,
                    )
                    MainTab.Anniversary -> {
                        com.qimingnan.couple.ui.anniversary.AnniversaryScreen(
                            anniversaryVm = hiltViewModel(),
                            ledgerVm = hiltViewModel(),
                            today = java.time.LocalDate.now(),
                            onBack = { tab = MainTab.Me },
                            showBack = false,
                            modifier = contentModifier,
                        )
                    }
                    MainTab.Ledger -> com.qimingnan.couple.ui.ledger.LedgerScreen(
                        vm = hiltViewModel(),
                        onBack = { returnToStart() },
                        // 记账现在是普通底栏 Tab：二级页由页面顶部三段切换，不再需要页头返回键。
                        showBack = false,
                        subTab = ledgerSubTab,
                        onSubTabChange = { ledgerSubTab = it },
                        // 在第一个/最后一个二级页还大幅左右滑：切到相邻的底栏 Tab。
                        onSwipeToTab = { delta ->
                            val index = visibleTabs.indexOf(tab)
                            val target = visibleTabs.getOrNull(index + delta)
                            if (target != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pinnedTab = null
                                tab = target
                            }
                        },
                        autoOpenWizard = openLedgerWizard,
                        onWizardDismissed = { openLedgerWizard = false },
                        modifier = contentModifier,
                    )
                    MainTab.Period -> com.qimingnan.couple.ui.period.PeriodScreen(vm = hiltViewModel(), modifier = contentModifier)
                }
            }
        }

        AnimatedVisibility(
            visible = secondaryMenuVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.94f) + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + scaleOut(targetScale = 0.96f) + slideOutVertically(targetOffsetY = { it / 3 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // 与底栏同一套内边距，二级页浮层正好盖住底栏，松手后恢复原样。
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            secondaryMenu?.let { menu ->
                SecondaryTabOverlay(
                    menu = menu,
                    style = settings.glassStyle,
                    onSelect = { index ->
                        commitLedgerSecondaryMenu(index, switchToLedger = tab != MainTab.Ledger)
                    },
                )
            }
        }
    }
}
