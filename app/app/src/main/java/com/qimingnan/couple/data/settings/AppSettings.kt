package com.qimingnan.couple.data.settings

/** 深浅色模式 */
enum class DarkMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

/** 字号档位 */
enum class FontSize(val scale: Float, val label: String) {
    Small(0.85f, "小"), Standard(1.0f, "标准"), Large(1.15f, "大"), XLarge(1.3f, "特大"),
}

/** 本地设置（仅保存在本机 DataStore，不上传）。 */
data class AppSettings(
    val hiddenTabs: Set<String> = emptySet(),
    val themeSeed: String = DEFAULT_SEED,
    val dynamicColor: Boolean = false,
    val darkMode: DarkMode = DarkMode.System,
    val fontSize: FontSize = FontSize.Standard,
) {
    companion object { const val DEFAULT_SEED = "rose" }
}

/** 底部 Tab 可见性：纯逻辑，id 与 MainTab.layoutId 一致；“我的”(me_entry) 永远可见。 */
object TabVisibility {
    const val LOCKED = "me_entry"
    val HIDEABLE = listOf("tab_chat", "tab_feed", "tab_ta")

    fun visible(all: List<String>, hidden: Set<String>): List<String> =
        all.filter { it == LOCKED || it !in hidden }

    /** 当前选中的 Tab 被隐藏时回到“我的”。 */
    fun resolveSelected(selected: String, all: List<String>, hidden: Set<String>): String =
        if (selected in visible(all, hidden)) selected else LOCKED
}
