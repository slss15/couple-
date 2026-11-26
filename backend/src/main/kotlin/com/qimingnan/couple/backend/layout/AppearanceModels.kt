package com.qimingnan.couple.backend.layout

import kotlinx.serialization.Serializable

@Serializable
data class RemoteTheme(
    val seed: String = AppearanceRules.DEFAULT_SEED,
    val darkMode: String = AppearanceRules.DEFAULT_DARK_MODE,
    val fontSize: String = AppearanceRules.DEFAULT_FONT_SIZE,
    val allowUserOverride: Boolean = true,
)

@Serializable
data class LayoutItemCatalog(
    val id: String,
    val label: String,
    val locked: Boolean = false,
)

@Serializable
data class LayoutZoneCatalog(
    val id: String,
    val label: String,
    val capacity: Int,
    val gridWidth: Int,
    val items: List<LayoutItemCatalog>,
)

@Serializable
data class ThemeOption(val id: String, val label: String)

@Serializable
data class ThemeCatalog(
    val seeds: List<ThemeOption>,
    val darkModes: List<ThemeOption>,
    val fontSizes: List<ThemeOption>,
)

@Serializable
data class AppAppearance(
    val layout: LayoutSpec = LayoutRules.DEFAULT,
    val theme: RemoteTheme = RemoteTheme(),
    val catalog: List<LayoutZoneCatalog> = LayoutCatalog.zones,
    val themeCatalog: ThemeCatalog = LayoutCatalog.themeCatalog,
    val appearanceVersion: Long = 0,
    val updatedAt: Long? = null,
)

@Serializable
data class AppearanceUpdate(
    val layout: LayoutSpec? = null,
    val theme: RemoteTheme? = null,
)

@Serializable
data class StoredAppearance(
    val layout: LayoutSpec = LayoutRules.DEFAULT,
    val theme: RemoteTheme = RemoteTheme(),
)

object AppearanceRules {
    const val DEFAULT_SEED = "linepuppy"
    const val DEFAULT_DARK_MODE = "system"
    const val DEFAULT_FONT_SIZE = "standard"

    val SEEDS: Map<String, String> = linkedMapOf(
        "rose" to "玫红",
        "blue" to "天蓝",
        "green" to "薄荷绿",
        "purple" to "薰衣草",
        "orange" to "暖橙",
        "teal" to "青碧",
        "linepuppy" to "鸡毛和小白",
    )
    val DARK_MODES: Map<String, String> = linkedMapOf(
        "system" to "跟随系统",
        "light" to "浅色",
        "dark" to "深色",
    )
    val FONT_SIZES: Map<String, String> = linkedMapOf(
        "small" to "小",
        "standard" to "标准",
        "large" to "大",
        "xlarge" to "特大",
    )

    fun validate(theme: RemoteTheme): List<String> {
        val errors = mutableListOf<String>()
        if (theme.seed !in SEEDS) errors += "theme.seed 必须是 ${SEEDS.keys.joinToString()} 之一"
        if (theme.darkMode !in DARK_MODES) errors += "theme.darkMode 必须是 ${DARK_MODES.keys.joinToString()} 之一"
        if (theme.fontSize !in FONT_SIZES) errors += "theme.fontSize 必须是 ${FONT_SIZES.keys.joinToString()} 之一"
        return errors
    }
}

object LayoutCatalog {
    val zones: List<LayoutZoneCatalog> = listOf(
        LayoutZoneCatalog(
            id = "bottomBar",
            label = "底部导航",
            capacity = LayoutRules.ZONE_CAPACITY.getValue("bottomBar"),
            gridWidth = LayoutRules.ZONE_GRID_WIDTH.getValue("bottomBar"),
            items = listOf(
                LayoutItemCatalog("tab_home", "首页"),
                LayoutItemCatalog("tab_feed", "动态"),
                LayoutItemCatalog("tab_ledger", "记账"),
                LayoutItemCatalog("me_entry", "我的", locked = true),
                LayoutItemCatalog("tab_ta", "TA"),
                LayoutItemCatalog("tab_anniversary", "纪念日"),
                LayoutItemCatalog("tab_period", "经期"),
            ),
        ),
        LayoutZoneCatalog(
            id = "feedHeader",
            label = "动态顶部",
            capacity = LayoutRules.ZONE_CAPACITY.getValue("feedHeader"),
            gridWidth = LayoutRules.ZONE_GRID_WIDTH.getValue("feedHeader"),
            items = listOf(LayoutItemCatalog("plugin:anniversary", "纪念日卡片")),
        ),
        LayoutZoneCatalog(
            id = "taCards",
            label = "TA 页面卡片",
            capacity = LayoutRules.ZONE_CAPACITY.getValue("taCards"),
            gridWidth = LayoutRules.ZONE_GRID_WIDTH.getValue("taCards"),
            items = listOf(
                LayoutItemCatalog("partner_card", "伴侣资料卡"),
                LayoutItemCatalog("anniversary", "在一起天数"),
                LayoutItemCatalog("days", "重要日子"),
            ),
        ),
        LayoutZoneCatalog(
            id = "meSections",
            label = "我的页面区块",
            capacity = LayoutRules.ZONE_CAPACITY.getValue("meSections"),
            gridWidth = LayoutRules.ZONE_GRID_WIDTH.getValue("meSections"),
            items = listOf(
                LayoutItemCatalog("settings", "设置入口"),
                LayoutItemCatalog("profile", "我的资料"),
                LayoutItemCatalog("couple", "情侣状态"),
                LayoutItemCatalog("mqtt", "连接状态"),
                LayoutItemCatalog("server", "服务器信息"),
                LayoutItemCatalog("updates", "应用更新"),
                LayoutItemCatalog("chat_entry", "聊天入口（默认隐藏）"),
            ),
        ),
    )

    val themeCatalog = ThemeCatalog(
        seeds = AppearanceRules.SEEDS.map { ThemeOption(it.key, it.value) },
        darkModes = AppearanceRules.DARK_MODES.map { ThemeOption(it.key, it.value) },
        fontSizes = AppearanceRules.FONT_SIZES.map { ThemeOption(it.key, it.value) },
    )
}
