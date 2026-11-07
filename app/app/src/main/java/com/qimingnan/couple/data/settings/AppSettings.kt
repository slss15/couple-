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
    /** 默认打开动态；显式选择独立首页时保留 home_page。 */
    val homeTab: String = StartupPage.DEFAULT,
    /** Plugin ids the user turned off; absent = on. Disabled plugins hide their entry but keep data. */
    val disabledPlugins: Set<String> = emptySet(),
    val themeSeed: String = DEFAULT_SEED,
    val dynamicColor: Boolean = false,
    val darkMode: DarkMode = DarkMode.System,
    val fontSize: FontSize = FontSize.Standard,
    /** 本月个人支出预算（分），0 = 没设；统计页显示已用/剩余，80%/100% 时提醒。 */
    val monthlyBudgetCents: Long = 0,
    /** 底部 Tab 栏顺序（layoutId 列表），设置里上下移动保存；默认见 DEFAULT_TAB_ORDER。 */
    val tabOrder: List<String> = DEFAULT_TAB_ORDER,
    /** 账户顺序（账户 id 列表），账户页上下移动保存；空 = 按添加顺序。 */
    val accountOrder: List<String> = emptyList(),
    /** 在系统最近任务列表显示本应用；不能阻止用户划掉或系统回收。 */
    val retainRecentTask: Boolean = true,
    /** 纪念日打卡：存 "事件 id:yyyy-MM-dd"（打卡的是哪一次发生日），存在 DataStore 里，不动数据库。 */
    val checkins: Set<String> = emptySet(),
    /** 神秘惊喜准备进度：存 "事件 id:0-100"，存在 DataStore 里，不动数据库。 */
    val surpriseProgress: Set<String> = emptySet(),
    /** 旅行计划关联的账户 id（DataStore，不动数据库）；没设就是没定旅行计划。 */
    val travelAccountId: String? = null,
    /** 旅行计划目标金额（分），0 = 没设。 */
    val travelTargetCents: Long = 0,
    val travelName: String = "",
    /** 借贷记录：LoanRecord 编码后的字符串集合，存在 DataStore 里，不动数据库版本。 */
    val loans: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_SEED = "linepuppy"
        /** 底部 Tab 默认顺序，与 MainTab 的 layoutId 一致。 */
        val DEFAULT_TAB_ORDER = listOf("tab_feed", "tab_ta", "me_entry", "tab_anniversary", "tab_ledger")
    }
}

/** 底部 Tab 可见性：纯逻辑，id 与 MainTab.layoutId 一致；“我的”(me_entry) 永远可见。 */
object TabVisibility {
    const val LOCKED = "me_entry"
    val HIDEABLE = listOf("tab_feed", "tab_ta", "tab_anniversary", "tab_ledger")

    fun visible(all: List<String>, hidden: Set<String>): List<String> =
        all.filter { it == LOCKED || it !in hidden }

    /** 当前选中的 Tab 被隐藏时回到“我的”。 */
    fun resolveSelected(selected: String, all: List<String>, hidden: Set<String>): String =
        if (selected in visible(all, hidden)) selected else LOCKED
}

/** 启动页解析只依赖持久设置和实际可见入口，便于独立验证。 */
object StartupPage {
    const val DEFAULT = "tab_feed"
    const val HOME = "home_page"

    fun resolve(requested: String, visibleTabs: List<String>): String = when {
        requested == HOME -> HOME
        requested in visibleTabs -> requested
        DEFAULT in visibleTabs -> DEFAULT
        TabVisibility.LOCKED in visibleTabs -> TabVisibility.LOCKED
        else -> visibleTabs.firstOrNull() ?: TabVisibility.LOCKED
    }
}
