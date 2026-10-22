package com.qimingnan.couple.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.qimingnan.couple.theme.LocalCoupleTokens

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val t = LocalCoupleTokens.current
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = t.shapes.pill,
        modifier = modifier.fillMaxWidth().heightIn(min = t.spacing.minTouchTarget),
        colors = ButtonDefaults.buttonColors(containerColor = t.colors.primary, contentColor = t.colors.onPrimary),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(t.spacing.lg),
                color = t.colors.onPrimary,
                strokeWidth = t.spacing.xxs,
            )
        } else {
            Text(text, style = t.typography.label)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val t = LocalCoupleTokens.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = t.shapes.pill,
        modifier = modifier.fillMaxWidth().heightIn(min = t.spacing.minTouchTarget),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) t.colors.error else t.colors.primary),
    ) { Text(text, style = t.typography.label) }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = t.spacing.minTouchTarget)) {
        Text(text, style = t.typography.label, color = t.colors.primary)
    }
}

@Composable
fun CoupleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val t = LocalCoupleTokens.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = t.typography.body) },
        singleLine = singleLine,
        enabled = enabled,
        shape = t.shapes.medium,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalCoupleTokens.current
    Surface(
        color = t.colors.surfaceContainer,
        contentColor = t.colors.onSurface,
        shape = t.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            if (title != null) Text(title, style = t.typography.title, color = t.colors.onSurface)
            content()
        }
    }
}

enum class BannerKind { Info, Success, Error, Warning }

@Composable
fun StatusBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val (bg, fg) = when (kind) {
        BannerKind.Info -> t.colors.secondaryContainer to t.colors.onSecondaryContainer
        BannerKind.Success -> t.colors.success to t.colors.onSuccess
        BannerKind.Error -> t.colors.error to t.colors.onError
        BannerKind.Warning -> t.colors.warning to t.colors.onWarning
    }
    Surface(color = bg, contentColor = fg, shape = t.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Text(text, style = t.typography.body, modifier = Modifier.padding(t.spacing.md))
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    val t = LocalCoupleTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
        Text(key, style = t.typography.caption, color = t.colors.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(0.65f))
    }
}

@Composable
fun StatusDot(color: Color) {
    val t = LocalCoupleTokens.current
    Box(Modifier.size(t.spacing.sm + t.spacing.xxs).background(color, CircleShape))
}

@Composable
fun FullScreenLoading() {
    val t = LocalCoupleTokens.current
    Box(Modifier.fillMaxSize().background(t.colors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = t.colors.primary)
    }
}
