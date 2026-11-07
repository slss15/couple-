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
import kotlinx.coroutines.flow.first
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
    /** 等持久设置读取完成再决定启动页，不能拿 StateFlow 初始默认值代替用户选择。 */
    suspend fun initialSettings(): AppSettings = decode(store.data.catch { emit(emptyPreferences()) }.first())

    suspend fun setHomeTab(id: String) {
        if (id != StartupPage.HOME && id !in AppSettings.DEFAULT_TAB_ORDER) return
        store.edit { it[HOME_TAB] = id }
    }
    suspend fun setRetainRecentTask(on: Boolean) = store.edit { it[RETAIN_RECENT] = on }
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

    /** 在持久事务里计算顺序，连续点击也不丢失更新。 */
    suspend fun moveTabOrder(id: String, delta: Int) {
        if (delta != -1 && delta != 1) return
        store.edit { p ->
            val order = effectiveTabOrder(p[TAB_ORDER]?.split(",") ?: emptyList()).toMutableList()
            val index = order.indexOf(id)
            val target = index + delta
            if (index >= 0 && target in order.indices) {
                order[index] = order[target].also { order[target] = order[index] }
                p[TAB_ORDER] = order.joinToString(",")
            }
        }
    }

    suspend fun moveAccountOrder(id: String, delta: Int, allIds: List<String>) {
        if (delta != -1 && delta != 1) return
        store.edit { p ->
            val order = ((p[ACCOUNT_ORDER]?.split(",") ?: emptyList()) + allIds).distinct()
                .filter { it in allIds }.toMutableList()
            val index = order.indexOf(id)
            val target = index + delta
            if (index >= 0 && target in order.indices) {
                order[index] = order[target].also { order[target] = order[index] }
                p[ACCOUNT_ORDER] = order.joinToString(",")
            }
        }
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

    /** 旅行计划只引用账户余额，不创建收入/支出或共同资金池。 */
    suspend fun setTravel(accountId: String?, targetCents: Long, name: String = "") = store.edit { p ->
        if (accountId == null) {
            p.remove(TRAVEL_ACCOUNT)
            p.remove(TRAVEL_NAME)
            p[TRAVEL_TARGET] = 0
        } else {
            require(targetCents > 0) { "旅行目标必须大于 0" }
            p[TRAVEL_ACCOUNT] = accountId
            p[TRAVEL_TARGET] = targetCents
            p[TRAVEL_NAME] = name.trim()
        }
    }

    /** 原有字符串记录兼容读取；按 id 更新，不允许相同 id 出现多条。 */
    suspend fun addLoan(record: LoanRecord) {
        require(record.validationError() == null) { record.validationError() ?: "借贷记录无效" }
        store.edit { p ->
            val records = p[LOANS] ?: emptySet()
            val current = records.mapNotNull(LoanRecord::decode).firstOrNull { it.id == record.id }
            val updated = if (current == null) record else record.copy(repaidCents = current.repaidCents, repayments = current.repayments)
            require(updated.validationError() == null) { updated.validationError() ?: "借贷记录无效" }
            val old = records.filterNot { LoanRecord.decode(it)?.id == record.id }.toSet()
            p[LOANS] = old + updated.encode()
        }
    }

    /** 必须在 DataStore 事务内读旧值，否则快速连续还款会互相覆盖。 */
    suspend fun repayLoan(id: String, addCents: Long, date: String = java.time.LocalDate.now().toString(), note: String = ""): Boolean {
        var saved = false
        store.edit { p ->
            val records = p[LOANS] ?: emptySet()
            p[LOANS] = records.map { raw ->
                val loan = LoanRecord.decode(raw)
                if (loan?.id != id) raw else {
                    val updated = loan.repay(addCents, date, note)
                    if (updated == null) raw else { saved = true; updated.encode() }
                }
            }.toSet()
        }
        return saved
    }

    suspend fun deleteLoan(id: String) = store.edit { p ->
        p[LOANS] = (p[LOANS] ?: emptySet()).filterNot { LoanRecord.decode(it)?.id == id }.toSet()
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
        private val RETAIN_RECENT = booleanPreferencesKey("retain_recent_task")
        private val CHECKINS = stringSetPreferencesKey("anniversary_checkins")
        private val SURPRISE = stringSetPreferencesKey("anniversary_surprise")
        private val TRAVEL_ACCOUNT = stringPreferencesKey("travel_account_id")
        private val TRAVEL_TARGET = longPreferencesKey("travel_target_cents")
        private val TRAVEL_NAME = stringPreferencesKey("travel_name")
        private val LOANS = stringSetPreferencesKey("loan_records")

        /** 顺序里补齐缺失的 Tab、去掉多余的，保证 5 个都在。 */
        fun effectiveTabOrder(stored: List<String>): List<String> =
            (stored + AppSettings.DEFAULT_TAB_ORDER).distinct()
                .filter { it in AppSettings.DEFAULT_TAB_ORDER }

        fun decode(p: Preferences) = AppSettings(
            hiddenTabs = (p[HIDDEN] ?: emptySet()) - TabVisibility.LOCKED,
            homeTab = (p[HOME_TAB] ?: StartupPage.DEFAULT).takeIf { it == StartupPage.HOME || it in AppSettings.DEFAULT_TAB_ORDER } ?: StartupPage.DEFAULT,
            themeSeed = p[SEED] ?: AppSettings.DEFAULT_SEED,
            dynamicColor = p[DYNAMIC] ?: false,
            darkMode = DarkMode.entries.firstOrNull { it.name == p[DARK] } ?: DarkMode.System,
            fontSize = FontSize.entries.firstOrNull { it.name == p[FONT] } ?: FontSize.Standard,
            disabledPlugins = p[DISABLED_PLUGINS] ?: emptySet(),
            monthlyBudgetCents = p[MONTHLY_BUDGET] ?: 0,
            tabOrder = (p[TAB_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
                .let(::effectiveTabOrder),
            accountOrder = p[ACCOUNT_ORDER]?.split(",")?.filter { it.isNotBlank() }?.distinct() ?: emptyList(),
            retainRecentTask = p[RETAIN_RECENT] ?: true,
            checkins = p[CHECKINS] ?: emptySet(),
            surpriseProgress = p[SURPRISE] ?: emptySet(),
            travelAccountId = p[TRAVEL_ACCOUNT],
            travelTargetCents = p[TRAVEL_TARGET] ?: 0,
            travelName = p[TRAVEL_NAME] ?: "",
            loans = p[LOANS] ?: emptySet(),
        )
    }
}
