package com.qimingnan.couple.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class CoupleColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val outline: Color,
    val error: Color,
    val onError: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val sharingIndicator: Color,
    val onSharingIndicator: Color,
) {
    companion object {
        fun from(p: CouplePalette) = CoupleColors(
            primary = Color(p.primary), onPrimary = Color(p.onPrimary),
            primaryContainer = Color(p.primaryContainer), onPrimaryContainer = Color(p.onPrimaryContainer),
            secondary = Color(p.secondary), onSecondary = Color(p.onSecondary),
            secondaryContainer = Color(p.secondaryContainer), onSecondaryContainer = Color(p.onSecondaryContainer),
            background = Color(p.background), onBackground = Color(p.onBackground),
            surface = Color(p.surface), onSurface = Color(p.onSurface),
            surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant),
            surfaceContainer = Color(p.surfaceContainer), outline = Color(p.outline),
            error = Color(p.error), onError = Color(p.onError),
            success = Color(p.success), onSuccess = Color(p.onSuccess),
            warning = Color(p.warning), onWarning = Color(p.onWarning),
            sharingIndicator = Color(p.sharingIndicator), onSharingIndicator = Color(p.onSharingIndicator),
        )
    }
}

@Immutable
data class CoupleShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(20.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(50),
)

@Immutable
data class CoupleSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** plan §7：点击区域不小于 48dp */
    val minTouchTarget: Dp = 48.dp,
    /** 布局网格单元（S=1、M=2、L=4 格） */
    val gridUnit: Dp = 88.dp,
)

@Immutable
data class CoupleTypography(
    val display: TextStyle = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
    val headline: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * 宿主提供给所有 UI（以及之后的插件）的主题 token。
 * 浅色/深色必须齐全；所有 Composable 通过 [LocalCoupleTokens] 读取，禁止硬编码颜色。
 */
@Immutable
data class CoupleTokens(
    val id: String,
    val isDark: Boolean,
    val colors: CoupleColors,
    val shapes: CoupleShapes = CoupleShapes(),
    val spacing: CoupleSpacing = CoupleSpacing(),
    val typography: CoupleTypography = CoupleTypography(),
) {
    companion object {
        val DefaultLight = CoupleTokens(id = "default-light", isDark = false, colors = CoupleColors.from(Palettes.Light))
        val DefaultDark = CoupleTokens(id = "default-dark", isDark = true, colors = CoupleColors.from(Palettes.Dark))
    }
}

val LocalCoupleTokens = staticCompositionLocalOf { CoupleTokens.DefaultLight }
