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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import android.os.Build
import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.DarkMode

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

/** 字号缩放：同比例放大字号、行高、字距。 */
private fun TextUnit.scaled(f: Float) = if (isSpecified) this * f else this
private fun TextStyle.scaled(f: Float) = copy(
    fontSize = fontSize.scaled(f), lineHeight = lineHeight.scaled(f), letterSpacing = letterSpacing.scaled(f),
)

fun CoupleTypography.scaled(f: Float): CoupleTypography = if (f == 1f) this else CoupleTypography(
    display = display.scaled(f), headline = headline.scaled(f), title = title.scaled(f),
    body = body.scaled(f), label = label.scaled(f), caption = caption.scaled(f),
)

/** 系统动态取色 → token；success / warning / 共享指示保留宿主配色。 */
fun CoupleColors.withScheme(cs: ColorScheme) = copy(
    primary = cs.primary, onPrimary = cs.onPrimary, primaryContainer = cs.primaryContainer, onPrimaryContainer = cs.onPrimaryContainer,
    secondary = cs.secondary, onSecondary = cs.onSecondary, secondaryContainer = cs.secondaryContainer, onSecondaryContainer = cs.onSecondaryContainer,
    background = cs.background, onBackground = cs.onBackground, surface = cs.surface, onSurface = cs.onSurface,
    surfaceVariant = cs.surfaceVariant, onSurfaceVariant = cs.onSurfaceVariant, surfaceContainer = cs.surfaceContainer,
    outline = cs.outline, error = cs.error, onError = cs.onError,
)

/** 按本地设置组装 token；设置 StateFlow 变化时重组即时生效。 */
@Composable
fun CoupleTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.darkMode) {
        DarkMode.System -> isSystemInDarkTheme()
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val base = CoupleColors.from(ThemeSeeds.palette(settings.themeSeed, dark))
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        base.withScheme(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    } else base
    val tokens = CoupleTokens(
        id = (if (settings.dynamicColor) "dynamic" else settings.themeSeed) + if (dark) "-dark" else "-light",
        isDark = dark, colors = colors,
        typography = CoupleTypography().scaled(settings.fontSize.scale),
    )
    CoupleTheme(darkTheme = dark, tokens = tokens, content = content)
}

/** 简写：CoupleTheme.tokens */
object CoupleThemeAccess {
    val tokens: CoupleTokens
        @Composable @ReadOnlyComposable get() = LocalCoupleTokens.current
}
