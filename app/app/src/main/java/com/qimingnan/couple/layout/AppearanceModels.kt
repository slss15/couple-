package com.qimingnan.couple.layout

import com.qimingnan.couple.data.settings.AppSettings
import com.qimingnan.couple.data.settings.DarkMode
import com.qimingnan.couple.data.settings.FontSize
import com.qimingnan.couple.theme.ThemeSeeds
import kotlinx.serialization.Serializable

/** 服务端主题默认值。值使用小写字符串，避免 App 端枚举名称变化影响契约。 */
@Serializable
data class RemoteTheme(
    val seed: String = AppearanceRules.DEFAULT_SEED,
    val darkMode: String = AppearanceRules.DEFAULT_DARK_MODE,
    val fontSize: String = AppearanceRules.DEFAULT_FONT_SIZE,
    val allowUserOverride: Boolean = true,
)

/** GET /app/appearance 与 Admin 外观页共用的响应。 */
@Serializable
data class AppAppearance(
    val layout: LayoutSpec = LayoutRules.DEFAULT,
    val theme: RemoteTheme = RemoteTheme(),
    val appearanceVersion: Long = 0,
    val updatedAt: Long? = null,
)

/** App 内存态：网络失败时保留上次缓存，页面仍可正常渲染。 */
data class AppearanceState(
    val appearance: AppAppearance = AppAppearance(),
    val fromCache: Boolean = false,
    val error: String? = null,
)

object AppearanceRules {
    const val DEFAULT_SEED = "linepuppy"
    const val DEFAULT_DARK_MODE = "system"
    const val DEFAULT_FONT_SIZE = "standard"

    val SEEDS: List<String> = ThemeSeeds.all.map { it.id }
    val DARK_MODES: List<String> = listOf("system", "light", "dark")
    val FONT_SIZES: List<String> = listOf("small", "standard", "large", "xlarge")
}

fun RemoteTheme.normalized(): RemoteTheme = copy(
    seed = seed.takeIf { it in AppearanceRules.SEEDS } ?: AppearanceRules.DEFAULT_SEED,
    darkMode = darkMode.takeIf { it in AppearanceRules.DARK_MODES } ?: AppearanceRules.DEFAULT_DARK_MODE,
    fontSize = fontSize.takeIf { it in AppearanceRules.FONT_SIZES } ?: AppearanceRules.DEFAULT_FONT_SIZE,
)

fun RemoteTheme.darkMode(): DarkMode = when (darkMode) {
    "light" -> DarkMode.Light
    "dark" -> DarkMode.Dark
    else -> DarkMode.System
}

fun RemoteTheme.fontSize(): FontSize = when (fontSize) {
    "small" -> FontSize.Small
    "large" -> FontSize.Large
    "xlarge" -> FontSize.XLarge
    else -> FontSize.Standard
}

/**
 * 服务端主题是默认值；本地手动改过主题且服务端允许覆盖时，本地优先。
 * 服务端关闭 allowUserOverride 后，所有设备统一使用服务端主题。
 */
fun AppSettings.withServerAppearance(state: AppearanceState): AppSettings {
    val remote = state.appearance.theme.normalized()
    if (themeCustomized && remote.allowUserOverride) return this
    return copy(
        themeSeed = remote.seed,
        dynamicColor = false,
        darkMode = remote.darkMode(),
        fontSize = remote.fontSize(),
    )
}
