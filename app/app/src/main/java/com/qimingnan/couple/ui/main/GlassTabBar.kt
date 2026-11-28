package com.qimingnan.couple.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.data.settings.GlassStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import com.qimingnan.couple.theme.LocalCoupleTokens

@Composable
fun GlassNavigationBar(
    style: GlassStyle,
    /** 真实背景模糊的状态：内容侧用 Modifier.haze(state) 登记，这里用 hazeChild 取模糊结果。 */
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    /** 底栏项数；与 onDragSelect 一起启用「按住横向滑动选择」。 */
    itemCount: Int = 0,
    onDragSelect: ((Int) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val t = LocalCoupleTokens.current
    // 滑动选择的回调每次重组都是新 lambda；用 rememberUpdatedState + remember 固定引用，
    // 否则 pointerInput 会因 key 变化在手势中途重启，滑到一半就断了。
    val latestSelect = rememberUpdatedState(onDragSelect)
    val swipeSelect: (Int) -> Unit = remember { { index: Int -> latestSelect.value?.invoke(index) } }
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        GlassSurface(
            style = style,
            hazeState = hazeState,
            modifier = Modifier.fillMaxWidth().then(
                if (itemCount > 1) Modifier.tabBarSwipe(itemCount, swipeSelect) else Modifier,
            ),
            shape = RoundedCornerShape(30.dp),
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = t.colors.surface.copy(alpha = 0f),
                contentColor = t.colors.onSurface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                content = content,
            )
        }
    }
}

@Composable
fun SecondaryTabOverlay(
    menu: SecondaryTabMenu,
    style: GlassStyle,
    hazeState: HazeState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalCoupleTokens.current
    GlassSurface(
        style = style,
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().heightIn(min = 108.dp),
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("左右拖动 · 松手切换", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${menu.selectedIndex + 1}/${menu.items.size}", style = t.typography.caption, color = t.colors.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                menu.items.forEachIndexed { index, item ->
                    val selected = index == menu.selectedIndex
                    val scale by animateFloatAsState(if (selected) 1f else 0.96f, label = "secondary-tab-scale")
                    Surface(
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f).height(58.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        shape = RoundedCornerShape(20.dp),
                        // 未选中态靠底色分层，不靠整圈描边：深色主题里 surface 和 background 同色，
                        // 半透明底等于没有，只剩一圈发亮的边，正好是刚去掉的那种「白圈」。
                        color = if (selected) t.colors.primaryContainer.copy(alpha = 0.92f) else t.colors.surfaceContainer.copy(alpha = 0.72f),
                        contentColor = if (selected) t.colors.onPrimaryContainer else t.colors.onSurfaceVariant,
                        border = if (selected) BorderStroke(1.dp, t.colors.primary.copy(alpha = 0.28f)) else null,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Text(item.label, style = t.typography.caption)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassSurface(
    style: GlassStyle,
    hazeState: HazeState,
    modifier: Modifier,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val isLiquid = style == GlassStyle.Liquid
    // 只有液态玻璃才建动画：静态毛玻璃不需要每帧跑高光。
    val transition = if (isLiquid) rememberInfiniteTransition(label = "glass") else null
    // 动画值只在绘制阶段读（drawBehind 里），不参与重组。
    val liquidState = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_200, easing = LinearEasing), RepeatMode.Restart),
        label = "glass-shift",
    )
    val driftState = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "glass-drift",
    )
    // 真·背景模糊：hazeChild 会把内容侧（Modifier.haze）录下来的画面按 blurRadius 模糊后
    // 画在这里。之前那套「半透明渐变 + 高光带」只是画出来的假玻璃，后面根本没有东西可糊。
    // fallbackTint 在拿不到 RenderEffect 的低版本机型上退化成半透明底色，不会变透明块。
    val hazeStyle = HazeStyle(
        backgroundColor = if (t.isDark) t.colors.surfaceContainer.copy(alpha = 0.55f) else t.colors.surface.copy(alpha = 0.42f),
        tint = HazeTint(t.colors.surface.copy(alpha = if (t.isDark) 0.42f else 0.55f)),
        blurRadius = if (isLiquid) 32.dp else 18.dp,
        noiseFactor = if (isLiquid) 0.10f else 0.06f,
    )

    Box(
        modifier
            // 深色下纯黑投影落在近黑背景上看不见，底栏失去「浮起」的分离感；
            // 深色改用一点主题色光晕，浅色仍用默认黑影。
            .shadow(
                elevation = if (isLiquid) 22.dp else 14.dp,
                shape = shape,
                clip = false,
                ambientColor = if (t.isDark) t.colors.primaryContainer.copy(alpha = 0.35f) else t.colors.onSurface.copy(alpha = 0.30f),
                spotColor = if (t.isDark) t.colors.primaryContainer.copy(alpha = 0.35f) else t.colors.onSurface.copy(alpha = 0.30f),
            )
            .clip(shape)
            // 已经 clip(shape) 了，不需要再传 shape（传了会走 deprecated 重载）。
            .hazeChild(hazeState, hazeStyle)
            .drawBehind {
                val liquid = liquidState?.value ?: 0f
                val drift = driftState?.value ?: 0f
                val w = size.width
                val h = size.height
                // 顶部镜面高光：玻璃的"边"是亮的，不是暗的。
                val specular = t.colors.glassSpecular.copy(alpha = if (t.isDark) 0.16f else 0.55f)
                val stroke = 1.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(t.colors.glassSpecular.copy(alpha = 0f), specular, t.colors.glassSpecular.copy(alpha = 0f)),
                    ),
                    start = Offset(0f, stroke / 2f),
                    end = Offset(w, stroke / 2f),
                    strokeWidth = stroke,
                )
                // 底部一道很淡的暗边，把玻璃和背景分开（深色下用 background 压暗，
                // 不能用 onSurface —— 深色下它是浅色，会变成一条亮线）。
                drawLine(
                    color = if (t.isDark) t.colors.background.copy(alpha = 0.55f)
                    else t.colors.onSurface.copy(alpha = 0.07f),
                    start = Offset(w * 0.06f, h - stroke / 2f),
                    end = Offset(w * 0.94f, h - stroke / 2f),
                    strokeWidth = stroke,
                )
                if (isLiquid) {
                    // 液态玻璃的两道流动高光：模糊底之上再叠一层会动的镜面，
                    // 这才是"液态"和"磨砂"的差别。
                    val bandX = w * liquid
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                t.colors.glassSpecular.copy(alpha = 0f),
                                t.colors.glassSpecular.copy(alpha = if (t.isDark) 0.10f else 0.34f),
                                t.colors.glassSpecular.copy(alpha = 0f),
                            ),
                            start = Offset(bandX - w * 0.28f, 0f),
                            end = Offset(bandX + w * 0.28f, h),
                        ),
                    )
                    val driftX = w * drift
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                t.colors.primary.copy(alpha = 0f),
                                t.colors.primary.copy(alpha = 0.22f),
                                t.colors.primary.copy(alpha = 0f),
                            ),
                            start = Offset(driftX - w * 0.22f, h),
                            end = Offset(driftX + w * 0.22f, 0f),
                        ),
                    )
                }
            },
    ) {
        content()
    }
}
