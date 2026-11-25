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
    content: @Composable RowScope.() -> Unit,
) {
    val t = LocalCoupleTokens.current
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        GlassSurface(
            style = style,
            modifier = Modifier.fillMaxWidth(),
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
    val transition = rememberInfiniteTransition(label = "glass")
    val liquid by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_800, easing = LinearEasing), RepeatMode.Restart),
        label = "glass-shift",
    )
    val shift = liquid * 900f - 450f
    val baseBrush = if (style == GlassStyle.Liquid) {
        Brush.linearGradient(
            colors = listOf(
                t.colors.surface.copy(alpha = 0.78f),
                t.colors.primary.copy(alpha = 0.14f),
                t.colors.surface.copy(alpha = 0.84f),
                t.colors.secondary.copy(alpha = 0.12f),
                t.colors.surface.copy(alpha = 0.76f),
            ),
            start = Offset(shift, 0f),
            end = Offset(shift + 900f, 700f),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(t.colors.surface.copy(alpha = 0.86f), t.colors.surfaceContainer.copy(alpha = 0.72f)),
        )
    }

    Box(
        modifier
            .shadow(14.dp, shape, clip = false)
            .clip(shape)
            .background(baseBrush)
            .border(1.dp, t.colors.onSurface.copy(alpha = 0.12f), shape),
    ) {
        if (style == GlassStyle.Liquid) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val bandX = size.width * liquid
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                t.colors.onSurface.copy(alpha = 0f),
                                t.colors.onSurface.copy(alpha = 0.10f),
                                t.colors.onSurface.copy(alpha = 0f),
                            ),
                            start = Offset(bandX - size.width * 0.28f, 0f),
                            end = Offset(bandX + size.width * 0.28f, size.height),
                        ),
                    )
                },
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(t.colors.onSurface.copy(alpha = 0.08f), t.colors.onSurface.copy(alpha = 0f)),
                ),
            ),
        )
        content()
    }
}
