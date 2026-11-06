package com.qimingnan.couple.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qimingnan.couple.data.ledger.LoanRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 设置仓库：DataStore 持久化，[settings] 为热 StateFlow，修改立即生效。 */
class SettingsRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val settings: StateFlow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map(::decode)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun setTabHidden(id: String, hidden: Boolean) {
        if (id == TabVisibility.LOCKED || id !in TabVisibility.HIDEABLE) return
        store.edit { p ->
            val cur = p[HIDDEN] ?: emptySet()
            p[HIDDEN] = if (hidden) cur + id else cur - id
        }
    }
    suspend fun setHomeTab(id: String) = store.edit { it[HOME_TAB] = id }
    suspend fun setThemeSeed(id: String) = store.edit { it[SEED] = id; it[DYNAMIC] = false }
    suspend fun setDynamicColor(on: Boolean) = store.edit { it[DYNAMIC] = on }
    suspend fun setDarkMode(m: DarkMode) = store.edit { it[DARK] = m.name }
    suspend fun setFontSize(f: FontSize) = store.edit { it[FONT] = f.name }
    suspend fun setPluginEnabled(id: String, enabled: Boolean) = store.edit { p ->
        val cur = p[DISABLED_PLUGINS] ?: emptySet()
        p[DISABLED_PLUGINS] = if (enabled) cur - id else cur + id
    }

    /** 本月个人预算（分），0 = 没设。 */
    suspend fun setMonthlyBudget(cents: Long) = store.edit { it[MONTHLY_BUDGET] = cents.coerceAtLeast(0) }

    /** 把一个 Tab 在顺序里上下移一位（delta 为 -1 上移、+1 下移），越界不动。 */
    suspend fun moveTabOrder(id: String, delta: Int) {
        val order = effectiveTabOrder(settings.value.tabOrder).toMutableList()
        val i = order.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        store.edit { it[TAB_ORDER] = order.joinToString(",") }
    }

    /** 把一个账户在顺序里上下移一位；allIds 是当前全部账户 id（新账户先补进顺序末尾）。 */
    suspend fun moveAccountOrder(id: String, delta: Int, allIds: List<String>) {
        val order = (settings.value.accountOrder + allIds).distinct().toMutableList()
        val i = order.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        store.edit { it[ACCOUNT_ORDER] = order.joinToString(",") }
    }

    /** 纪念日打卡：记下这次发生日已打卡（"事件 id:yyyy-MM-dd"）。 */
    suspend fun addCheckIn(eventId: String, date: String) = store.edit { p ->
        p[CHECKINS] = (p[CHECKINS] ?: emptySet()) + "$eventId:$date"
    }

    /** 神秘惊喜准备进度（0–100），按事件 id 存。 */
    suspend fun setSurpriseProgress(eventId: String, progress: Int) = store.edit { p ->
        val cur = (p[SURPRISE] ?: emptySet()).filterNot { it.substringBefore(':') == eventId }.toSet()
        p[SURPRISE] = cur + "$eventId:${progress.coerceIn(0, 100)}"
    }

    /** 旅行计划：关联账户 + 目标金额（分）；accountId 为 null 表示清除计划。 */
    suspend fun setTravel(accountId: String?, targetCents: Long) = store.edit { p ->
        if (accountId == null) {
            p.remove(TRAVEL_ACCOUNT)
            p[TRAVEL_TARGET] = 0
        } else {
            p[TRAVEL_ACCOUNT] = accountId
            p[TRAVEL_TARGET] = targetCents.coerceAtLeast(0)
        }
    }

    /** 新增一笔借贷记录（编码后存进 DataStore，不动数据库版本）。 */
    suspend fun addLoan(record: LoanRecord) = store.edit { p ->
        p[LOANS] = (p[LOANS] ?: emptySet()) + record.encode()
    }

    /** 给一笔借贷记还款（追加已还金额，超还的部分按还清算）。 */
    suspend fun repayLoan(id: String, addCents: Long) {
        val list = settings.value.loans.mapNotNull(LoanRecord::decode)
        val updated = list.map {
            if (it.id == id) it.copy(repaidCents = (it.repaidCents + addCents).coerceAtMost(it.amountCents)) else it
        }
        store.edit { it[LOANS] = updated.map { r -> r.encode() }.toSet() }
    }

    suspend fun deleteLoan(id: String) = store.edit { p ->
        p[LOANS] = (p[LOANS] ?: emptySet()).filterNot { it.substringBefore('|') == id }.toSet()
    }

    companion object {
        private val HIDDEN = stringSetPreferencesKey("hidden_tabs")
        private val HOME_TAB = stringPreferencesKey("home_tab")
        private val SEED = stringPreferencesKey("theme_seed")
        private val DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val DARK = stringPreferencesKey("dark_mode")
        private val FONT = stringPreferencesKey("font_size")
        private val DISABLED_PLUGINS = stringSetPreferencesKey("disabled_plugins")
        private val MONTHLY_BUDGET = longPreferencesKey("monthly_budget_cents")
        private val TAB_ORDER = stringPreferencesKey("tab_order")
        private val ACCOUNT_ORDER = stringPreferencesKey("account_order")
        private val CHECKINS = stringSetPreferencesKey("anniversary_checkins")
        private val SURPRISE = stringSetPreferencesKey("anniversary_surprise")
        private val TRAVEL_ACCOUNT = stringPreferencesKey("travel_account_id")
        private val TRAVEL_TARGET = longPreferencesKey("travel_target_cents")
        private val LOANS = stringSetPreferencesKey("loan_records")

        /** 顺序里补齐缺失的 Tab、去掉多余的，保证 5 个都在。 */
        fun effectiveTabOrder(stored: List<String>): List<String> =
            (stored + AppSettings.DEFAULT_TAB_ORDER).distinct()
                .filter { it in AppSettings.DEFAULT_TAB_ORDER }

        fun decode(p: Preferences) = AppSettings(
            hiddenTabs = (p[HIDDEN] ?: emptySet()) - TabVisibility.LOCKED,
            homeTab = (p[HOME_TAB] ?: "tab_anniversary").takeIf { it != "tab_chat" } ?: "tab_anniversary",
            themeSeed = p[SEED] ?: AppSettings.DEFAULT_SEED,
            dynamicColor = p[DYNAMIC] ?: false,
            darkMode = DarkMode.entries.firstOrNull { it.name == p[DARK] } ?: DarkMode.System,
            fontSize = FontSize.entries.firstOrNull { it.name == p[FONT] } ?: FontSize.Standard,
            disabledPlugins = p[DISABLED_PLUGINS] ?: emptySet(),
            monthlyBudgetCents = p[MONTHLY_BUDGET] ?: 0,
            tabOrder = (p[TAB_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
                .let(::effectiveTabOrder),
            accountOrder = p[ACCOUNT_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            checkins = p[CHECKINS] ?: emptySet(),
            surpriseProgress = p[SURPRISE] ?: emptySet(),
            travelAccountId = p[TRAVEL_ACCOUNT],
            travelTargetCents = p[TRAVEL_TARGET] ?: 0,
            loans = p[LOANS] ?: emptySet(),
        )
    }
}
