package com.qimingnan.couple.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
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

/** 底部四个 Tab；id 与布局 JSON 的 bottomBar 条目一致（me_entry 为锁定项）。 */
enum class MainTab(val layoutId: String, @StringRes val label: Int, val icon: ImageVector) {
    Chat("tab_chat", R.string.tab_chat, Icons.Filled.Email),
    Feed("tab_feed", R.string.tab_feed, Icons.Filled.Home),
    Ta("tab_ta", R.string.tab_ta, Icons.Filled.Favorite),
    Me("me_entry", R.string.tab_me, Icons.Filled.Person),
}

@Composable
fun MainShell(onRescan: () -> Unit, onOpenKeepAlive: () -> Unit = {}) {
    val t = LocalCoupleTokens.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Chat) }
    Scaffold(
        containerColor = t.colors.background,
        contentColor = t.colors.onBackground,
        bottomBar = {
            NavigationBar(containerColor = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
                MainTab.entries.forEach { item ->
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
            MainTab.Me -> MeScreen(onRescan = onRescan, onOpenKeepAlive = onOpenKeepAlive, modifier = Modifier.padding(padding))
            MainTab.Feed -> FeedScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Ta -> com.qimingnan.couple.ui.ta.TaScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
            MainTab.Chat -> ChatScreen(vm = hiltViewModel(), modifier = Modifier.padding(padding))
        }
    }
}
