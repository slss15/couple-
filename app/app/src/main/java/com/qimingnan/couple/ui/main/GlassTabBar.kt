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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.data.settings.GlassStyle
import com.qimingnan.couple.theme.LocalCoupleTokens

@Composable
fun GlassNavigationBar(
    style: GlassStyle,
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
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalCoupleTokens.current
    GlassSurface(
        style = style,
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
                        color = if (selected) t.colors.primaryContainer.copy(alpha = 0.92f) else t.colors.surface.copy(alpha = 0.30f),
                        contentColor = if (selected) t.colors.onPrimaryContainer else t.colors.onSurfaceVariant,
                        border = BorderStroke(1.dp, if (selected) t.colors.primary.copy(alpha = 0.28f) else t.colors.onSurface.copy(alpha = 0.08f)),
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
    modifier: Modifier,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val isLiquid = style == GlassStyle.Liquid
    val transition = rememberInfiniteTransition(label = "glass")
    // 两条不同速度的光带：液态玻璃靠它们「流动」，毛玻璃不参与动画。
    val liquid by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_200, easing = LinearEasing), RepeatMode.Restart),
        label = "glass-shift",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "glass-drift",
    )
    val shift = liquid * 1_100f - 550f
    val baseBrush = if (isLiquid) {
        Brush.linearGradient(
            colors = listOf(
                t.colors.surface.copy(alpha = 0.72f),
                t.colors.primary.copy(alpha = 0.18f),
                t.colors.surface.copy(alpha = 0.88f),
                t.colors.secondary.copy(alpha = 0.16f),
                t.colors.surface.copy(alpha = 0.70f),
            ),
            start = Offset(shift, 0f),
            end = Offset(shift + 1_100f, 760f),
        )
    } else {
        // 毛玻璃：上亮下暗的竖向渐变 + 顶部高光，安静不抢内容。
        Brush.verticalGradient(
            colors = listOf(
                t.colors.surface.copy(alpha = 0.90f),
                t.colors.surfaceContainer.copy(alpha = 0.64f),
            ),
        )
    }

    Box(
        modifier
            .shadow(if (isLiquid) 20.dp else 14.dp, shape, clip = false)
            .clip(shape)
            .background(baseBrush)
            .border(1.dp, t.colors.onSurface.copy(alpha = if (isLiquid) 0.16f else 0.10f), shape),
    ) {
        // 顶部高光：两种材质都有，液态玻璃更亮。
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            t.colors.onSurface.copy(alpha = if (isLiquid) 0.14f else 0.08f),
                            t.colors.onSurface.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    ),
                )
            },
        )
        if (isLiquid) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val w = size.width
                    val h = size.height
                    val bandX = w * liquid
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                t.colors.onSurface.copy(alpha = 0f),
                                t.colors.onSurface.copy(alpha = 0.13f),
                                t.colors.onSurface.copy(alpha = 0f),
                            ),
                            start = Offset(bandX - w * 0.30f, 0f),
                            end = Offset(bandX + w * 0.30f, h),
                        ),
                    )
                    val driftX = w * drift
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                t.colors.primary.copy(alpha = 0f),
                                t.colors.primary.copy(alpha = 0.18f),
                                t.colors.primary.copy(alpha = 0f),
                            ),
                            start = Offset(driftX - w * 0.24f, h),
                            end = Offset(driftX + w * 0.24f, 0f),
                        ),
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(t.colors.onSurface.copy(alpha = 0.12f), t.colors.onSurface.copy(alpha = 0f)),
                            center = Offset(bandX, h * 0.16f),
                            radius = h * 1.1f,
                        ),
                    )
                },
            )
        }
        content()
    }
}
