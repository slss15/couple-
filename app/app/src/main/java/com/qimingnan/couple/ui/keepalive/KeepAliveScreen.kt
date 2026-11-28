package com.qimingnan.couple.ui.keepalive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import com.qimingnan.couple.ui.components.RatioBar
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.StatusBanner
import com.qimingnan.couple.ui.components.StatusDot

/** 我的 → 后台保活：体检总览 + 各品牌设置直达 + Shizuku 一键豁免 + 常驻连接开关 + 连接诊断。 */
@Composable
fun KeepAliveScreen(onBack: () -> Unit, vm: KeepAliveViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    // rememberSaveable：授权弹窗期间旋转屏幕/被系统重建后，结果回调里还要靠它
    // 判断"这次授权是用户想开保活"，用 remember 会丢，导致授权成功也开不起来。
    var enablingFgs by rememberSaveable { mutableStateOf(false) }
    fun openNotificationSettings() {
        OemIntentLauncher.openFirst(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (enablingFgs) {
            notificationDenied = !granted
            if (granted) vm.setFgs(true)
            enablingFgs = false
        }
        vm.refresh()
    }

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

        // 体检结论原来是 StatusBanner 里的一行字，扫不出"还差几项"。改成比例条后，
        // 通过项数本身就是一个可比较的量，不用读数字也能看出进度。
        if (s.checks.isNotEmpty()) {
            val total = s.checks.size
            val passed = s.checks.count { it.status == CheckStatus.OK }
            val bad = s.checks.count { it.status == CheckStatus.BAD }
            val warn = s.checks.count { it.status == CheckStatus.WARN }
            SectionCard("体检总览") {
                Text("$passed/$total 项通过", style = t.typography.headline, color = t.colors.onSurface)
                RatioBar(passed.toFloat() / total)
                Text(
                    when {
                        bad > 0 -> "还有 $bad 项会直接影响后台收消息：按每行右侧的按钮处理，回到本页会自动重新检查。"
                        warn > 0 -> "关键项已全部通过，另有 $warn 项建议优化。"
                        passed == total -> "全部通过，后台收消息的环境已就绪。"
                        // NA（系统版本不支持）不算失败，否则老机型会一直显示"未完成"。
                        else -> "其余 ${total - passed} 项在当前系统版本上不适用，不影响收消息。"
                    },
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                )
                if (bad > 0) StatusBanner("有 $bad 项需要处理", BannerKind.Warning)
            }
        }

        SectionCard("系统权限") {
            s.checks.forEach { item ->
                val action = actionFor(item, notificationDenied, { perm -> notifLauncher.launch(perm) }, context) { openNotificationSettings() }
                CheckRow(item = item, action = action)
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
                    "通过静音前台服务尽量维持实时连接；仍可能被系统回收，不代表绝对保活。",
                    style = t.typography.body,
                    color = t.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = s.fgsEnabled, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) { enablingFgs = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    else vm.setFgs(on)
                })
            }
        }

        if (notificationDenied) {
            Text("通知权限未允许，暂未开启常驻连接。", style = t.typography.caption, color = t.colors.error)
            SecondaryButton("去系统设置开启通知", onClick = { openNotificationSettings() })
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
                // 延迟是这页唯一有量纲的数字，原来挤在 KeyValueRow 的一行字符串里，
                // 数字和单位、样本量混在一起看不出量级差。拆成两列数字块，p50/p95 各占一格。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                    MetricBlock("回环 p50", st.echoP50, "${st.echoCount} 条样本", Modifier.weight(1f))
                    MetricBlock("TA→我 p50", st.peerP50, "${st.peerCount} 条样本", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.md)) {
                    MetricBlock("回环 p95", st.echoP95, "最慢 5%", Modifier.weight(1f))
                    // 半宽只有 ~140dp，"最慢 5% · 含时钟误差" 会折成两行、把这一格顶高；
                    // p95 本身已经说明分位，这里只留 TA→我 独有的误差提示。
                    MetricBlock("TA→我 p95", st.peerP95, "含时钟误差", Modifier.weight(1f))
                }
                Text(
                    "重连 ${st.reconnects} · 网络切换 ${st.networkChanges} · 去重/过期丢弃 ${st.duplicatesDropped}/${st.staleDropped}",
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                )
            }
            // 事件串经常很长，单行省略：它只用来对时间线，不该把整张卡撑高。
            s.lastEvent?.let {
                Text(
                    "最近事件：$it",
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SecondaryButton("立即重连", onClick = vm::reconnect)
        }
    }
}

/** 行尾操作：标签 + 点击。抽出来是为了让 CheckRow 只关心"这一行长什么样"。 */
private data class CheckAction(val label: String, val onClick: () -> Unit)

/**
 * 每项检查对应的修复动作。只有没通过的行才有按钮，通过/不适用的行右侧只有状态 pill，
 * 所以按钮不再占一整行，各行高度一致。
 */
private fun actionFor(
    item: CheckItem,
    notificationDenied: Boolean,
    requestNotification: (String) -> Unit,
    context: Context,
    openNotificationSettings: () -> Unit,
): CheckAction? = when (item.id) {
    CheckId.NOTIFICATIONS -> {
        when {
            item.status == CheckStatus.OK -> null
            // 勾了「不再询问」之后系统不会再弹窗、launch 立刻返回 false，
            // 用户在这个页面就彻底没有出路了。给一个直达通知设置的入口。
            notificationDenied -> CheckAction("去系统设置") { openNotificationSettings() }
            else -> CheckAction("允许通知") {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestNotification(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }
        }
    }
    CheckId.BATTERY_OPTIMIZATION -> {
        if (item.status == CheckStatus.OK) {
            null
        } else {
            CheckAction("忽略电池优化") {
                @SuppressLint("BatteryLife")
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                OemIntentLauncher.openFirst(context, direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
    CheckId.BACKGROUND_RESTRICTED, CheckId.DATA_SAVER -> {
        if (item.status == CheckStatus.BAD) {
            CheckAction("打开应用设置") { OemIntentLauncher.openAppDetails(context) }
        } else {
            null
        }
    }
    else -> null
}

/**
 * 一行检查：状态点 + 标题/原因 + 状态 pill + 行尾操作。
 * 操作从卡内全宽 SecondaryButton 挪到行尾，行高不再被"有没有按钮"左右。
 */
@Composable
private fun CheckRow(item: CheckItem, action: CheckAction?) {
    val t = LocalCoupleTokens.current
    val dotColor = when (item.status) {
        CheckStatus.OK -> t.colors.success
        CheckStatus.WARN -> t.colors.warning
        CheckStatus.BAD -> t.colors.error
        CheckStatus.NA -> t.colors.outline
    }
    val pillText = when (item.status) {
        CheckStatus.OK -> "通过"
        CheckStatus.WARN, CheckStatus.BAD -> "需处理"
        CheckStatus.NA -> "不适用"
    }
    val pillContent = when (item.status) {
        CheckStatus.OK -> t.colors.success
        CheckStatus.WARN -> t.colors.warning
        CheckStatus.BAD -> t.colors.error
        CheckStatus.NA -> t.colors.onSurfaceVariant
    }
    val pillContainer = when (item.status) {
        // 调色板没有 successContainer/warningContainer：用语义色 12% 叠在卡片底上做容器。
        // 再深一档（16% 左右）浅色主题里 12sp 语义色文字的对比度就掉到 4.5 以下；
        // 直接拿 success 当填充又会在浅色下变成一块比标题还重的深色块。
        CheckStatus.NA -> t.colors.surfaceVariant
        else -> pillContent.copy(alpha = 0.12f)
    }
    Row(
        // 统一最小行高：有按钮的行被 48dp 的 TextButton 撑住，没按钮的行也保持同一高度。
        Modifier.fillMaxWidth().heightIn(min = t.spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        StatusDot(dotColor)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = t.typography.title, color = t.colors.onSurface)
            // 原因最多两行：再长会把这行单独撑高，等高就没了；pill 和按钮已经给出了出路。
            Text(
                item.detail,
                style = t.typography.caption,
                color = t.colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(pillText, pillContainer, pillContent)
        action?.let { RowAction(it) }
    }
}

/** 状态 pill：pill 形状 + 语义容器色，文案只说结论，细节交给左边的 title/detail。 */
@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    val t = LocalCoupleTokens.current
    Surface(color = container, contentColor = content, shape = t.shapes.pill) {
        Text(
            text,
            style = t.typography.caption,
            color = content,
            modifier = Modifier.padding(horizontal = t.spacing.sm, vertical = t.spacing.xxs),
        )
    }
}

/** 行尾 TextButton：内边距收窄到 sm，省下的宽度留给标题和原因；高度仍满足 48dp 触摸目标。 */
@Composable
private fun RowAction(action: CheckAction) {
    val t = LocalCoupleTokens.current
    TextButton(
        onClick = action.onClick,
        contentPadding = PaddingValues(horizontal = t.spacing.sm),
        modifier = Modifier.heightIn(min = t.spacing.minTouchTarget),
    ) {
        Text(action.label, style = t.typography.label, color = t.colors.primary)
    }
}

/**
 * 诊断数字块：caption 说明测的是什么，headline 放数字、caption 放单位，最下面 caption 放对照（样本量/分位）。
 * 拆开之后 "42" 和 "ms" 各自有字号层级，才看得出这是延迟而不是一句日志。
 */
@Composable
private fun MetricBlock(label: String, value: Long?, note: String, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Column(modifier) {
        Text(label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.toString() ?: "—", style = t.typography.headline, color = t.colors.onSurface)
            if (value != null) {
                Text(
                    "ms",
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = t.spacing.xxs, bottom = t.spacing.xs),
                )
            }
        }
        // 没样本时 note 里的分位数也是空的，统一换成"暂无样本"，避免一行 "p95 null"。
        Text(if (value == null) "暂无样本" else note, style = t.typography.caption, color = t.colors.onSurfaceVariant)
    }
}
