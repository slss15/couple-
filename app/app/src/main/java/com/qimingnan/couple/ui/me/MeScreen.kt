package com.qimingnan.couple.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.data.mqtt.MqttState
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.CoupleTextField
import com.qimingnan.couple.ui.components.KeyValueRow
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.StatusBanner
import com.qimingnan.couple.ui.components.StatusDot
import java.text.DateFormat
import java.util.Date

@Composable
fun MeScreen(onRescan: () -> Unit, modifier: Modifier = Modifier, vm: MeViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        SectionCard(stringResource(R.string.me_profile)) {
            val me = s.me
            if (me != null) {
                Text(me.displayName, style = t.typography.headline, color = t.colors.onSurface)
                KeyValueRow(stringResource(R.string.auth_username), me.username)
                KeyValueRow("ID", me.id)
            }
        }

        SectionCard(stringResource(R.string.me_couple)) {
            val couple = s.couple
            val partner = s.partner
            if (couple != null && partner != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    val online = s.partnerPresence?.online == true
                    StatusDot(if (online) t.colors.success else t.colors.outline)
                    Text(
                        "${stringResource(R.string.me_partner)}：${partner.displayName} · " +
                            stringResource(if (online) R.string.online else R.string.offline),
                        style = t.typography.title,
                        color = t.colors.onSurface,
                    )
                }
                KeyValueRow("", stringResource(R.string.me_paired_at, dateFmt.format(Date(couple.pairedAt))))
                KeyValueRow("Couple ID", couple.id)
                if (s.coupleFromCache) StatusBanner("离线缓存数据", BannerKind.Warning)
            }
        }

        SectionCard(stringResource(R.string.me_mqtt)) {
            val (label, color) = when (val m = s.mqtt) {
                MqttState.Idle -> "未连接" to t.colors.outline
                MqttState.Connecting -> "连接中…" to t.colors.warning
                is MqttState.Connected -> "已连接" to t.colors.success
                is MqttState.Failed -> ("失败：${m.reason}" + (m.retryInMillis?.let { "（${it / 1000} 秒后重试）" } ?: "")) to t.colors.error
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                StatusDot(color)
                Text(label, style = t.typography.body, color = t.colors.onSurface)
            }
            SecondaryButton("重新连接", onClick = vm::reconnectMqtt)
        }

        SectionCard(stringResource(R.string.me_server)) {
            val c = s.config
            if (c != null) {
                KeyValueRow("API", c.baseUrl)
                KeyValueRow("MQTT", c.mqttUrl)
                KeyValueRow("版本", c.configVersion.toString())
                KeyValueRow("签名", if (c.signed) "已校验" else "调试配置（未签名）")
                s.pinFingerprint?.let { KeyValueRow("公钥指纹", it) }
            }
            SecondaryButton(stringResource(R.string.me_rescan), onClick = onRescan)
        }

        SectionCard(stringResource(R.string.me_unpair)) {
            val u = s.unpair
            u.error?.let { StatusBanner(it, BannerKind.Error) }
            if (u.requestedId != null) {
                Text(stringResource(R.string.me_unpair_request_sent), style = t.typography.body, color = t.colors.onSurfaceVariant)
                Text(u.requestedId, style = t.typography.title, color = t.colors.primary)
            }
            SecondaryButton(stringResource(R.string.me_unpair_request), onClick = vm::requestUnpair, enabled = !u.busy, danger = true)
            CoupleTextField(u.confirmInput, vm::onConfirmInput, stringResource(R.string.me_unpair_request_id) + "（可留空）")
            SecondaryButton(stringResource(R.string.me_unpair_confirm), onClick = vm::confirmUnpair, enabled = !u.busy, danger = true)
        }

        SecondaryButton(stringResource(R.string.me_logout), onClick = vm::logout, danger = true)
    }
}
