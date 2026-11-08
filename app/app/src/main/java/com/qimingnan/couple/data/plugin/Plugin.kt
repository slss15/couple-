package com.qimingnan.couple.data.plugin

/**
 * Plugin host contract (PR2). First version is intentionally built-in, not
 * dynamically loaded code: plugins ship inside the main APK and update
 * through the mirror update channel, so no plugin can bypass signing,
 * verification or the host's permission boundary.
 *
 * Rules every plugin must follow (enforced by the host in later PRs):
 * - A plugin never touches tokens, MQTT or chat tables directly; it only
 *   receives what the host passes in (self / partner / couple ids, names).
 * - Each plugin owns its own Room tables (prefixed with the plugin id) and
 *   its own DataStore keys (see [settingsKey]); disabling a plugin hides
 *   its entry but must not delete its data.
 * - Plugin records are sync-ready even while offline: id, owner/couple,
 *   created/updated timestamps and a soft-delete flag, mirroring the
 *   existing feed/chat offline-outbox pattern.
 */
enum class PluginScope { PERSONAL, COUPLE }

data class PluginDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val scope: PluginScope,
    /** False until the plugin's own PR lands; the host still lists it so the switch exists. */
    val available: Boolean,
)

object PluginRegistry {
    const val ANNIVERSARY = "anniversary"
    const val LEDGER = "ledger"
    const val PERIOD = "period"

    val all: List<PluginDescriptor> = listOf(
        PluginDescriptor(
            id = ANNIVERSARY,
            title = "纪念日",
            description = "在一起天数、生日与纪念日倒数，支持阳历和阴历",
            scope = PluginScope.COUPLE,
            available = true, // PR3 enables the anniversary plugin; ledger stays false until PR4.
        ),
        PluginDescriptor(
            id = LEDGER,
            title = "记账",
            description = "普通个人记账：账户、支出、收入、转账与月预算",
            scope = PluginScope.PERSONAL,
            available = true, // PR4 enables the ledger plugin.
        ),
        PluginDescriptor(
            id = PERIOD,
            title = "经期记录",
            description = "经期日志、周期预测与提醒；数据只保存在本机",
            scope = PluginScope.PERSONAL,
            available = true,
        ),
    )

    fun byId(id: String): PluginDescriptor? = all.firstOrNull { it.id == id }

    fun isEnabled(id: String, disabledPlugins: Set<String>): Boolean =
        byId(id)?.available == true && id !in disabledPlugins

    fun enabled(disabledPlugins: Set<String>): List<PluginDescriptor> =
        all.filter { isEnabled(it.id, disabledPlugins) }

    /** Namespaced DataStore key convention for plugin-owned settings. */
    fun settingsKey(pluginId: String, key: String): String = "plugin_${pluginId}_$key"
}
