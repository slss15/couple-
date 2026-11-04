package com.qimingnan.couple.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.DarkMode
import com.qimingnan.couple.data.settings.FontSize
import com.qimingnan.couple.data.settings.SettingsRepository
import com.qimingnan.couple.data.settings.TabVisibility
import com.qimingnan.couple.theme.Contrast
import com.qimingnan.couple.theme.CoupleTypography
import com.qimingnan.couple.theme.ThemeSeeds
import com.qimingnan.couple.theme.scaled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = scopes.forEach { it.cancel() }

    private fun repo(file: File): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
    }

    private fun SettingsRepository.await(p: (AppSettings) -> Boolean) =
        runBlocking { withTimeout(5_000) { settings.first(p) } }

    @Test fun defaults() {
        val s = repo(tmp.newFile("a.preferences_pb").also { it.delete() }).await { true }
        assertEquals(AppSettings(), s)
    }

    @Test fun updatesFlowImmediately() = runBlocking {
        val r = repo(File(tmp.root, "b.preferences_pb"))
        r.setTabHidden("tab_feed", true)
        r.setDarkMode(DarkMode.Dark)
        r.setFontSize(FontSize.XLarge)
        r.setThemeSeed("teal")
        val s = r.await { it.fontSize == FontSize.XLarge && it.themeSeed == "teal" }
        assertEquals(setOf("tab_feed"), s.hiddenTabs)
        assertEquals(DarkMode.Dark, s.darkMode)
        r.setTabHidden("tab_feed", false)
        assertTrue(r.await { it.hiddenTabs.isEmpty() }.hiddenTabs.isEmpty())
    }

    @Test fun meTabCannotBeHidden() = runBlocking {
        val r = repo(File(tmp.root, "c.preferences_pb"))
        r.setTabHidden(TabVisibility.LOCKED, true)
        r.setTabHidden("bogus", true)
        r.setTabHidden("tab_feed", true)
        assertEquals(setOf("tab_feed"), r.await { it.hiddenTabs.isNotEmpty() }.hiddenTabs)
    }

    @Test fun dynamicColorTurnsOffWhenPickingSeed() = runBlocking {
        val r = repo(File(tmp.root, "d.preferences_pb"))
        r.setDynamicColor(true)
        r.await { it.dynamicColor }
        r.setThemeSeed("blue")
        val s = r.await { it.themeSeed == "blue" }
        assertEquals(false, s.dynamicColor)
    }

    @Test fun persistsAcrossInstances() {
        val f = File(tmp.root, "e.preferences_pb")
        val r1 = repo(f)
        runBlocking { r1.setFontSize(FontSize.Small); r1.setTabHidden("tab_ta", true) }
        r1.await { it.fontSize == FontSize.Small && "tab_ta" in it.hiddenTabs }
        scopes.forEach { it.cancel() }; scopes.clear()
        Thread.sleep(100)
        val s = repo(f).await { it.fontSize == FontSize.Small }
        assertEquals(setOf("tab_ta"), s.hiddenTabs)
    }
}

class TabVisibilityTest {
    private val all = listOf("tab_chat", "tab_feed", "tab_ta", "me_entry")

    @Test fun hidesSelected() =
        assertEquals(listOf("tab_chat", "me_entry"), TabVisibility.visible(all, setOf("tab_feed", "tab_ta")))

    @Test fun meAlwaysVisible() =
        assertEquals(listOf("me_entry"), TabVisibility.visible(all, all.toSet()))

    @Test fun hiddenSelectedFallsBackToMe() {
        assertEquals("me_entry", TabVisibility.resolveSelected("tab_feed", all, setOf("tab_feed")))
        assertEquals("tab_chat", TabVisibility.resolveSelected("tab_chat", all, setOf("tab_feed")))
    }
}

class ThemeSettingsTest {
    @Test fun allSeedsPassContrast() {
        assertEquals(7, ThemeSeeds.all.size)
        for (seed in ThemeSeeds.all) for (dark in listOf(false, true)) {
            val p = ThemeSeeds.palette(seed.id, dark)
            p.textPairs().forEach { (n, c) -> assertTrue("${seed.id}/$dark $n", Contrast.ratio(c.first, c.second) >= 4.5) }
            p.iconPairs().forEach { (n, c) -> assertTrue("${seed.id}/$dark $n", Contrast.ratio(c.first, c.second) >= 3.0) }
        }
    }

    @Test fun fontScaleApplies() {
        val base = CoupleTypography()
        FontSize.entries.forEach { f ->
            assertEquals(base.body.fontSize.value * f.scale, base.scaled(f.scale).body.fontSize.value, 0.01f)
        }
        assertEquals(listOf(0.85f, 1.0f, 1.15f, 1.3f), FontSize.entries.map { it.scale })
    }
}
