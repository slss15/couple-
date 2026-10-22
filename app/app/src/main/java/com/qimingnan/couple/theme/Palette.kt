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
