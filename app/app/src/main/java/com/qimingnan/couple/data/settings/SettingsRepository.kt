package com.qimingnan.couple.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    companion object {
        private val HIDDEN = stringSetPreferencesKey("hidden_tabs")
        private val HOME_TAB = stringPreferencesKey("home_tab")
        private val SEED = stringPreferencesKey("theme_seed")
        private val DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val DARK = stringPreferencesKey("dark_mode")
        private val FONT = stringPreferencesKey("font_size")
        private val DISABLED_PLUGINS = stringSetPreferencesKey("disabled_plugins")

        fun decode(p: Preferences) = AppSettings(
            hiddenTabs = (p[HIDDEN] ?: emptySet()) - TabVisibility.LOCKED,
            homeTab = p[HOME_TAB] ?: "tab_chat",
            themeSeed = p[SEED] ?: AppSettings.DEFAULT_SEED,
            dynamicColor = p[DYNAMIC] ?: false,
            darkMode = DarkMode.entries.firstOrNull { it.name == p[DARK] } ?: DarkMode.System,
            fontSize = FontSize.entries.firstOrNull { it.name == p[FONT] } ?: FontSize.Standard,
            disabledPlugins = p[DISABLED_PLUGINS] ?: emptySet(),
        )
    }
}
