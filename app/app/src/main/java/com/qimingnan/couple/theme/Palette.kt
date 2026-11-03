package com.qimingnan.couple.theme

/**
 * 唯一允许出现颜色字面量的文件。值是 ARGB Long，纯 Kotlin，方便单测做对比度校验。
 * 其他 UI 代码只能通过 LocalCoupleTokens 取色（见 NoHardcodedColorTest）。
 */
data class CouplePalette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceContainer: Long,
    val outline: Long,
    val error: Long,
    val onError: Long,
    val success: Long,
    val onSuccess: Long,
    val warning: Long,
    val onWarning: Long,
    /** “正在共享”醒目指示（plan §5 TA） */
    val sharingIndicator: Long,
    val onSharingIndicator: Long,
) {
    /** 文字/前景色 与 背景色 的配对，要求对比度 ≥ 4.5:1。 */
    fun textPairs(): Map<String, Pair<Long, Long>> = mapOf(
        "onPrimary/primary" to (onPrimary to primary),
        "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
        "onSecondary/secondary" to (onSecondary to secondary),
        "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
        "onBackground/background" to (onBackground to background),
        "onSurface/surface" to (onSurface to surface),
        "onSurface/surfaceContainer" to (onSurface to surfaceContainer),
        "onSurfaceVariant/surfaceVariant" to (onSurfaceVariant to surfaceVariant),
        "onSurfaceVariant/surface" to (onSurfaceVariant to surface),
        "onError/error" to (onError to error),
        "onSuccess/success" to (onSuccess to success),
        "onWarning/warning" to (onWarning to warning),
        "onSharingIndicator/sharingIndicator" to (onSharingIndicator to sharingIndicator),
        "primary/surface(文字按钮)" to (primary to surface),
        "error/surface(错误文字)" to (error to surface),
    )

    /** 图标/边框等非文字元素，要求对比度 ≥ 3:1。 */
    fun iconPairs(): Map<String, Pair<Long, Long>> = mapOf(
        "outline/background" to (outline to background),
        "primary/background" to (primary to background),
        "primary/surfaceContainer" to (primary to surfaceContainer),
        "onSurfaceVariant/surfaceContainer" to (onSurfaceVariant to surfaceContainer),
        "sharingIndicator/background" to (sharingIndicator to background),
    )
}

object Palettes {
    val Light = CouplePalette(
        primary = 0xFFA8235A,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFFFD9E2,
        onPrimaryContainer = 0xFF3E001D,
        secondary = 0xFF74565F,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFFFD9E3,
        onSecondaryContainer = 0xFF2B151C,
        background = 0xFFFFFBFC,
        onBackground = 0xFF201A1B,
        surface = 0xFFFFFBFC,
        onSurface = 0xFF201A1B,
        surfaceVariant = 0xFFF2DDE1,
        onSurfaceVariant = 0xFF514347,
        surfaceContainer = 0xFFF6EBEE,
        outline = 0xFF837377,
        error = 0xFFBA1A1A,
        onError = 0xFFFFFFFF,
        success = 0xFF1B6D2F,
        onSuccess = 0xFFFFFFFF,
        warning = 0xFF7A5900,
        onWarning = 0xFFFFFFFF,
        sharingIndicator = 0xFFC62828,
        onSharingIndicator = 0xFFFFFFFF,
    )

    val Dark = CouplePalette(
        primary = 0xFFFFB1C8,
        onPrimary = 0xFF650033,
        primaryContainer = 0xFF881A4A,
        onPrimaryContainer = 0xFFFFD9E2,
        secondary = 0xFFE3BDC6,
        onSecondary = 0xFF422931,
        secondaryContainer = 0xFF5A3F47,
        onSecondaryContainer = 0xFFFFD9E2,
        background = 0xFF1A1316,
        onBackground = 0xFFEFDFE1,
        surface = 0xFF1A1316,
        onSurface = 0xFFEFDFE1,
        surfaceVariant = 0xFF514347,
        onSurfaceVariant = 0xFFD5C2C6,
        surfaceContainer = 0xFF261D20,
        outline = 0xFF9E8C90,
        error = 0xFFFFB4AB,
        onError = 0xFF690005,
        success = 0xFF8DD79A,
        onSuccess = 0xFF00391A,
        warning = 0xFFF5BF48,
        onWarning = 0xFF402D00,
        sharingIndicator = 0xFFFFB4AB,
        onSharingIndicator = 0xFF690005,
    )
}

/** 主题色强调组（primary / secondary 两组），套在中性底色上。 */
data class Accent(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
)

data class ThemeSeed(val id: String, val label: String, val light: Accent, val dark: Accent)

fun CouplePalette.withAccent(a: Accent) = copy(
    primary = a.primary, onPrimary = a.onPrimary, primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    secondary = a.secondary, onSecondary = a.onSecondary, secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
)

/** 6 个预设种子色；rose 即原默认配色。 */
object ThemeSeeds {
    private fun accentOf(p: CouplePalette) = Accent(p.primary, p.onPrimary, p.primaryContainer, p.onPrimaryContainer, p.secondary, p.onSecondary, p.secondaryContainer, p.onSecondaryContainer)

    val all = listOf(
        ThemeSeed("rose", "玫红", accentOf(Palettes.Light), accentOf(Palettes.Dark)),
        ThemeSeed("blue", "天蓝",
            Accent(0xFF0061A4, 0xFFFFFFFF, 0xFFD1E4FF, 0xFF001D36, 0xFF535F70, 0xFFFFFFFF, 0xFFD7E3F7, 0xFF101C2B),
            Accent(0xFF9ECAFF, 0xFF003258, 0xFF00497D, 0xFFD1E4FF, 0xFFBBC7DB, 0xFF253140, 0xFF3B4858, 0xFFD7E3F7)),
        ThemeSeed("green", "薄荷绿",
            Accent(0xFF006C4C, 0xFFFFFFFF, 0xFF89F8C7, 0xFF002114, 0xFF4D6357, 0xFFFFFFFF, 0xFFCFE9D9, 0xFF092016),
            Accent(0xFF6CDBAC, 0xFF003826, 0xFF005138, 0xFF89F8C7, 0xFFB3CCBE, 0xFF1F352A, 0xFF354B40, 0xFFCFE9D9)),
        ThemeSeed("purple", "薰衣草",
            Accent(0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D, 0xFF625B71, 0xFFFFFFFF, 0xFFE8DEF8, 0xFF1D192B),
            Accent(0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF, 0xFFCCC2DC, 0xFF332D41, 0xFF4A4458, 0xFFE8DEF8)),
        ThemeSeed("orange", "暖橙",
            Accent(0xFF8B5000, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2C1600, 0xFF725A42, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF291806),
            Accent(0xFFFFB870, 0xFF4A2800, 0xFF693C00, 0xFFFFDCBE, 0xFFE1C1A4, 0xFF402C18, 0xFF59422C, 0xFFFFDCBE)),
        ThemeSeed("teal", "青碧",
            Accent(0xFF006A6A, 0xFFFFFFFF, 0xFF6FF7F6, 0xFF002020, 0xFF4A6363, 0xFFFFFFFF, 0xFFCCE8E7, 0xFF051F1F),
            Accent(0xFF4CDADA, 0xFF003737, 0xFF004F4F, 0xFF6FF7F6, 0xFFB0CCCB, 0xFF1B3534, 0xFF324B4B, 0xFFCCE8E7)),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()

    fun palette(id: String, dark: Boolean): CouplePalette {
        val s = byId(id)
        return if (dark) Palettes.Dark.withAccent(s.dark) else Palettes.Light.withAccent(s.light)
    }
}
