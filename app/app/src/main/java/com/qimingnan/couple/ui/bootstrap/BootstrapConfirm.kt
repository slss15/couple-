package com.qimingnan.couple.ui.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.qimingnan.couple.data.bootstrap.BootstrapPreview
import com.qimingnan.couple.data.bootstrap.TrustCheck
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.KeyValueRow
import com.qimingnan.couple.ui.components.LinkButton
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.StatusBanner

/**
 * Confirm page after a couple://bootstrap link, a QR scan or the manual form: shows the host and the bootstrap key
 * fingerprint before anything is stored. When host or key differ from what this phone already trusts, it shows a
 * strong warning and the confirm button stays disabled until the user ticks an explicit checkbox.
 */
@Composable
fun BootstrapConfirmCard(preview: BootstrapPreview, onConfirm: (acceptChange: Boolean) -> Unit, onCancel: () -> Unit) {
    val t = LocalCoupleTokens.current
    var accepted by rememberSaveable(preview) { mutableStateOf(false) }
    val trust = preview.trust

    if (trust.needsExplicitConfirm) {
        StatusBanner(warningText(trust), BannerKind.Error)
    }

    SectionCard("确认服务器") {
        KeyValueRow("服务器", preview.host)
        KeyValueRow("API", preview.baseUrl)
        KeyValueRow("MQTT", preview.mqttUrl)
        preview.configVersion?.let { KeyValueRow("配置版本", it.toString()) }
        Text("公钥指纹", style = t.typography.caption, color = t.colors.onSurfaceVariant)
        Text(
            preview.fingerprint ?: "（调试配置，未签名）",
            style = t.typography.title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            color = if (trust.keyChanged) t.colors.error else t.colors.onSurface,
        )
        Text(
            when (trust) {
                TrustCheck.FirstTime -> "首次连接：请与管理后台首页显示的公钥指纹逐组核对，一致再继续。确认后本机会记住这台服务器。"
                TrustCheck.Same -> "与本机已信任的服务器和公钥一致。"
                else -> "请先通过其他方式（当面、电话）向管理员确认这次更换是有意为之。"
            },
            style = t.typography.body,
            color = t.colors.onSurfaceVariant,
        )
    }

    if (trust.needsExplicitConfirm) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            Checkbox(
                checked = accepted,
                onCheckedChange = { accepted = it },
                colors = CheckboxDefaults.colors(checkedColor = t.colors.error),
            )
            Text(
                if (trust.keyChanged) "我已核对新的公钥指纹，确认信任这台服务器" else "我确认要切换到新的服务器地址",
                style = t.typography.body,
                color = t.colors.onBackground,
            )
        }
        SecondaryButton(
            if (trust.keyChanged) "仍然信任新公钥并切换" else "确认切换服务器",
            onClick = { onConfirm(true) },
            enabled = accepted,
            danger = true,
        )
        LinkButton("取消，保留当前服务器", onCancel)
    } else {
        PrimaryButton("确认并连接", onClick = { onConfirm(false) })
        LinkButton("取消", onCancel)
    }
}

private fun warningText(trust: TrustCheck): String = when (trust) {
    is TrustCheck.HostChanged ->
        "⚠ 服务器地址变了\n已信任：${trust.oldHost}\n这次：${trust.newHost}\n如果你没有更换服务器，这可能是钓鱼二维码，请不要继续。"
    is TrustCheck.KeyChanged ->
        "⚠ 服务器公钥变了\n已信任：${trust.oldFingerprint}\n这次：${trust.newFingerprint}\n公钥只有在服务器重装或密钥泄露后才会变化。不确定就不要继续。"
    is TrustCheck.HostAndKeyChanged ->
        "⚠ 服务器地址和公钥都变了\n地址：${trust.oldHost} → ${trust.newHost}\n指纹：${trust.oldFingerprint} → ${trust.newFingerprint}\n这相当于换了一台完全不同的服务器。不确定就不要继续。"
    else -> ""
}
