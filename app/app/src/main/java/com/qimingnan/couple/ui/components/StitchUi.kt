package com.qimingnan.couple.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.theme.LocalCoupleTokens

/**
 * Stitch 设计稿（Tender Tangerine & Cream）里反复出现的视觉件，抽出来共用：
 * 6 段胶囊进度条、已选汇总胶囊、大选项卡、占比条、月份切换、账目行。
 * 只用主题 token 取色，不写死颜色；逻辑一律不放在这里，只做展示。
 */

/** 6 段胶囊进度条：已完成/当前段是砂糖橘，当段更宽，未完成是浅色轨道。 */
@Composable
fun StitchProgressCapsules(step: Int, total: Int = 6, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            val done = i <= step
            Box(
                Modifier
                    .width(if (i == step) 24.dp else 12.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (done) t.colors.primaryContainer else t.colors.surfaceVariant),
            )
        }
    }
}

/** 已选汇总胶囊：选到哪步显示到哪步，例如 "32 元 · 支出 · 微信零钱 · 餐饮"。 */
@Composable
fun PickedSummaryPill(text: String, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Surface(
        color = t.colors.surfaceContainer,
        shape = t.shapes.pill,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选：", style = t.typography.label, color = t.colors.primary)
            Text(text, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Check, contentDescription = null, tint = t.colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 大选项卡（一行一个选项，点一下就选）：前面是圆形图标/字形，后面是标题 + 一句话说明，
 * 选中的卡描边变橘、底色变浅。图标只传核心库已有的，或传字形（大类用首字）。
 */
@Composable
fun OptionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    selected: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val t = LocalCoupleTokens.current
    Surface(
        color = if (selected) t.colors.surfaceContainer else t.colors.surface,
        shape = t.shapes.large,
        modifier = modifier.fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) t.colors.primaryContainer else t.colors.outline.copy(alpha = 0.4f),
                shape = t.shapes.large,
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(t.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = t.typography.title, color = t.colors.onSurface)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = t.typography.caption, color = t.colors.onSurfaceVariant)
                }
            }
            trailing()
        }
    }
}

/** 圆形字形头像：大类/分类没有核心库图标时用首字（餐、交、购…），不硬编图标语义。 */
@Composable
fun GlyphAvatar(glyph: String, modifier: Modifier = Modifier, container: Color? = null) {
    val t = LocalCoupleTokens.current
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .background(container ?: t.colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = t.typography.title, color = t.colors.onPrimaryContainer)
    }
}

/** 单色占比条：轨道浅色，填充砂糖橘，fraction 超过 1 按 1 算。 */
@Composable
fun RatioBar(fraction: Float, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Box(
        modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            .background(t.colors.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp)
                .clip(CircleShape).background(t.colors.primaryContainer),
        )
    }
}

/** 月份切换：‹ 上月 | 2026年11月 | 下月 ›，三个 Tab 共用同一个月。 */
@Composable
fun MonthSwitcher(label: String, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月", tint = t.colors.onSurface)
        }
        Text(
            label,
            style = t.typography.title,
            color = t.colors.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月", tint = t.colors.onSurface)
        }
    }
}

/** 账目行：字形头像 + 分类标题 + 副标题（账户/日期）+ 右侧带符号的金额。 */
@Composable
fun LedgerEntryRow(
    glyph: String,
    title: String,
    subtitle: String,
    amountText: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    note: String = "",
) {
    val t = LocalCoupleTokens.current
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        GlyphAvatar(glyph, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.typography.body, color = t.colors.onSurface)
            Text(subtitle, style = t.typography.caption, color = t.colors.onSurfaceVariant)
            if (note.isNotBlank()) Text(note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
        }
        Text(amountText, style = t.typography.body, color = t.colors.onSurface)
    }
}
