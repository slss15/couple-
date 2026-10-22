package com.qimingnan.couple.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Material3 组件也从 token 派生，保证没有任何颜色绕开 token 系统。 */
fun CoupleTokens.toColorScheme(): ColorScheme {
    val c = colors
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primary, onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.secondary, onSecondary = c.onSecondary,
        secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.secondary, onTertiary = c.onSecondary,
        background = c.background, onBackground = c.onBackground,
        surface = c.surface, onSurface = c.onSurface,
        surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceVariant,
        surfaceContainer = c.surfaceContainer, surfaceContainerLow = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainer, surfaceContainerHighest = c.surfaceVariant,
        surfaceContainerLowest = c.surface,
        outline = c.outline, outlineVariant = c.surfaceVariant,
        error = c.error, onError = c.onError,
    )
}

fun CoupleTokens.toTypography(): Typography {
    val t = typography
    return Typography(
        displaySmall = t.display, headlineSmall = t.headline, titleLarge = t.headline,
        titleMedium = t.title, bodyLarge = t.body, bodyMedium = t.body,
        labelLarge = t.label, labelMedium = t.caption, bodySmall = t.caption,
    )
}

@Composable
fun CoupleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    tokens: CoupleTokens = if (darkTheme) CoupleTokens.DefaultDark else CoupleTokens.DefaultLight,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCoupleTokens provides tokens) {
        MaterialTheme(
            colorScheme = tokens.toColorScheme(),
            typography = tokens.toTypography(),
            shapes = Shapes(small = tokens.shapes.small, medium = tokens.shapes.medium, large = tokens.shapes.large),
            content = content,
        )
    }
}

/** 简写：CoupleTheme.tokens */
object CoupleThemeAccess {
    val tokens: CoupleTokens
        @Composable @ReadOnlyComposable get() = LocalCoupleTokens.current
}
