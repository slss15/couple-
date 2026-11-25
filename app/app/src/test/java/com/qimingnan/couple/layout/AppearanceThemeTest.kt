package com.qimingnan.couple.layout

import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.DarkMode
import com.qimingnan.couple.data.settings.FontSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceThemeTest {
    private fun state(theme: RemoteTheme) = AppearanceState(AppAppearance(theme = theme))

    @Test fun serverThemeAppliesWhenUserHasNotCustomized() {
        val local = AppSettings(themeCustomized = false)
        val effective = local.withServerAppearance(
            state(RemoteTheme(seed = "blue", darkMode = "dark", fontSize = "large")),
        )
        assertEquals("blue", effective.themeSeed)
        assertEquals(DarkMode.Dark, effective.darkMode)
        assertEquals(FontSize.Large, effective.fontSize)
    }

    @Test fun localThemeWinsWhenAllowed() {
        val local = AppSettings(
            themeSeed = "green",
            darkMode = DarkMode.Light,
            fontSize = FontSize.Small,
            themeCustomized = true,
        )
        val effective = local.withServerAppearance(
            state(RemoteTheme(seed = "blue", darkMode = "dark", fontSize = "large", allowUserOverride = true)),
        )
        assertEquals(local, effective)
    }

    @Test fun serverThemeWinsWhenOverrideDisabled() {
        val local = AppSettings(themeSeed = "green", themeCustomized = true)
        val effective = local.withServerAppearance(
            state(RemoteTheme(seed = "orange", darkMode = "light", fontSize = "xlarge", allowUserOverride = false)),
        )
        assertEquals("orange", effective.themeSeed)
        assertEquals(DarkMode.Light, effective.darkMode)
        assertEquals(FontSize.XLarge, effective.fontSize)
    }

    @Test fun unknownRemoteValuesFallBackSafely() {
        val effective = RemoteTheme(seed = "not-a-theme", darkMode = "neon", fontSize = "huge").normalized()
        assertEquals("linepuppy", effective.seed)
        assertEquals("system", effective.darkMode)
        assertEquals("standard", effective.fontSize)
    }
}
