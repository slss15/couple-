package com.qimingnan.couple.ui.keepalive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.keepalive.CheckId
import com.qimingnan.couple.data.keepalive.CheckItem
import com.qimingnan.couple.data.keepalive.CheckStatus
import com.qimingnan.couple.data.keepalive.OemIntentLauncher
import com.qimingnan.couple.data.keepalive.OemKeepAlive
import com.qimingnan.couple.data.keepalive.OemPage
import com.qimingnan.couple.data.keepalive.ShizukuAccess
import com.qimingnan.couple.data.mqtt.MqttState
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.KeyValueRow
import com.qimingnan.couple.ui.components.LinkButton
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.StatusBanner
import com.qimingnan.couple.ui.components.StatusDot

/** 我的 → 后台保活：体检 + 各品牌设置直达 + Shizuku 一键豁免 + 常驻连接开关 + 连接诊断。 */
@Composable
fun KeepAliveScreen(onBack: () -> Unit, vm: KeepAliveViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    Column(
        Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        LinkButton("返回", onBack)
        Text("后台保活", style = t.typography.headline, color = t.colors.onBackground)
        Text(
            "手机系统会冻结后台应用，导致收不到 TA 的消息。逐项处理下面标红的项目，返回本页会自动重新检查。",
            style = t.typography.body,
            color = t.colors.onSurfaceVariant,
        )
        val bad = s.checks.count { it.status == CheckStatus.BAD }
        if (s.checks.isNotEmpty()) {
            StatusBanner(
                if (bad == 0) "体检通过" else "有 $bad 项需要处理",
                if (bad == 0) BannerKind.Success else BannerKind.Warning,
            )
        }

        SectionCard("系统权限") {
            s.checks.forEach { item ->
                CheckRow(item)
                when (item.id) {
                    CheckId.NOTIFICATIONS -> if (item.status != CheckStatus.OK) SecondaryButton("允许通知", onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            OemIntentLauncher.openFirst(
                                context,
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    })
                    CheckId.BATTERY_OPTIMIZATION -> if (item.status != CheckStatus.OK) SecondaryButton("忽略电池优化", onClick = {
                        @SuppressLint("BatteryLife")
                        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        OemIntentLauncher.openFirst(context, direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    })
                    CheckId.BACKGROUND_RESTRICTED, CheckId.DATA_SAVER -> if (item.status == CheckStatus.BAD) {
                        SecondaryButton("打开应用设置", onClick = { OemIntentLauncher.openAppDetails(context) })
                    }
                    else -> Unit
                }
            }
        }

        SectionCard("品牌设置：${s.oem.label}") {
            if (s.device.isNotBlank()) KeyValueRow("本机", s.device)
            Text(OemKeepAlive.hint(s.oem), style = t.typography.body, color = t.colors.onSurfaceVariant)
            SecondaryButton("打开自启动设置", onClick = { vm.openOemPage(OemPage.AUTOSTART) })
            SecondaryButton("打开电池 / 后台设置", onClick = { vm.openOemPage(OemPage.BATTERY) })
            s.message?.let { Text(it, style = t.typography.caption, color = t.colors.onSurfaceVariant) }
        }

        SectionCard("Shizuku 一键豁免") {
            Text(
                "通过 Shizuku 把本应用加入 Doze 白名单并允许后台运行（等同于 adb 命令），部分品牌重启后需要重新执行。",
                style = t.typography.body,
                color = t.colors.onSurfaceVariant,
            )
            when (s.shizuku) {
                ShizukuAccess.AUTHORIZED -> PrimaryButton("一键豁免并重新检查", onClick = vm::fixWithShizuku, loading = s.shizukuBusy)
                ShizukuAccess.NOT_AUTHORIZED -> SecondaryButton("授权 Shizuku", onClick = vm::requestShizuku)
                ShizukuAccess.UNSUPPORTED -> Text("Shizuku 版本过旧，请升级", style = t.typography.caption, color = t.colors.warning)
                ShizukuAccess.NOT_RUNNING -> Text("未检测到正在运行的 Shizuku", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            s.shizukuLog?.let {
                Text(it, style = t.typography.caption.copy(fontFamily = FontFamily.Monospace), color = t.colors.onSurface)
            }
        }

        SectionCard("常驻连接") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                Text(
                    "在通知栏保留一条静音通知，让系统不再冻结连接（更省心，但会常驻一条通知）",
                    style = t.typography.body,
                    color = t.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = s.fgsEnabled, onCheckedChange = vm::setFgs)
            }
        }

        SectionCard("连接诊断") {
            val (label, color) = when (val m = s.mqtt) {
                MqttState.Idle -> "未连接" to t.colors.outline
                MqttState.Connecting -> "连接中…" to t.colors.warning
                is MqttState.Connected -> "已连接" to t.colors.success
                is MqttState.Failed -> ("断开：${m.reason}" + (m.retryInMillis?.let { "（${it / 1000} 秒后重试）" } ?: "")) to t.colors.error
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                StatusDot(color)
                Text(label, style = t.typography.body, color = t.colors.onSurface)
            }
            val st = s.stats
            if (st != null) {
                KeyValueRow("回环延迟 p50 / p95", latency(st.echoP50, st.echoP95, st.echoCount))
                KeyValueRow("TA→我 延迟 p50 / p95", latency(st.peerP50, st.peerP95, st.peerCount) + "（含两台手机的时钟误差）")
                KeyValueRow("重连次数", st.reconnects.toString())
                KeyValueRow("网络切换", st.networkChanges.toString())
                KeyValueRow("去重 / 过期丢弃", "${st.duplicatesDropped} / ${st.staleDropped}")
            }
            s.lastEvent?.let { KeyValueRow("最近事件", it) }
            SecondaryButton("立即重连", onClick = vm::reconnect)
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val t = LocalCoupleTokens.current
    val color = when (item.status) {
        CheckStatus.OK -> t.colors.success
        CheckStatus.WARN -> t.colors.warning
        CheckStatus.BAD -> t.colors.error
        CheckStatus.NA -> t.colors.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        StatusDot(color)
        Column {
            Text(item.title, style = t.typography.title, color = t.colors.onSurface)
            Text(item.detail, style = t.typography.caption, color = t.colors.onSurfaceVariant)
        }
    }
}

private fun latency(p50: Long?, p95: Long?, n: Int): String =
    if (n == 0 || p50 == null || p95 == null) "暂无样本" else "$p50 ms / $p95 ms（$n 条）"
