package com.qimingnan.couple.ui.update

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.update.UpdateLogic
import com.qimingnan.couple.data.update.UpdatePhase
import com.qimingnan.couple.data.update.UpdateRepository
import com.qimingnan.couple.data.update.UpdateState
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.StatusBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repo: UpdateRepository) : ViewModel() {
    val state: StateFlow<UpdateState> = repo.state

    fun startUpdate() = repo.startUpdate()
    fun install() = repo.install()
    fun dismiss() = repo.dismiss()
    fun reopen() = repo.reopen()
    fun onResume() = repo.onResume()
    fun requestShizukuPermission() = repo.requestShizukuPermission()

    /** 「检查更新」 in 我的; [onResult] gets a short message. */
    fun checkNow(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repo.check(manual = true)) }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/**
 * Update prompt, hosted at the root so it covers every screen. A forced update cannot be dismissed (back / outside
 * taps are ignored and there is no "later" button).
 */
@Composable
fun UpdateDialogHost(vm: UpdateViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startUpdate() }

    val info = s.available ?: return
    if (!s.showDialog) return
    val t = LocalCoupleTokens.current
    val phase = s.phase

    fun onPrimary() {
        when (phase) {
            is UpdatePhase.Ready, UpdatePhase.NeedsUnknownSources, UpdatePhase.WaitingForUser -> vm.install()
            else -> if (!UpdateRepository.hasNotificationPermission(context) && Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                vm.startUpdate()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!s.force) vm.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !s.force, dismissOnClickOutside = !s.force),
        title = { Text(if (s.force) "需要更新到 ${info.versionName}" else "发现新版本 ${info.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                val why = UpdateLogic.reasonText(s.reason, s.peer)
                if (s.force) {
                    StatusBanner(why ?: "当前版本已停止支持，更新后才能继续使用。", BannerKind.Warning)
                } else if (why != null) {
                    StatusBanner(why, BannerKind.Info)
                }
                Text(
                    "当前 ${s.currentVersionName} → ${info.versionName} · ${formatBytes(info.size)}",
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                )
                val notes = info.notes?.takeIf { it.isNotBlank() }
                if (notes != null) {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Text(notes, style = t.typography.body, color = t.colors.onSurface)
                    }
                }
                when (phase) {
                    is UpdatePhase.Downloading -> {
                        val total = if (phase.total > 0) phase.total else info.size
                        LinearProgressIndicator(
                            progress = { if (total > 0) (phase.done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已下载 ${formatBytes(phase.done)} / ${formatBytes(total)}（可切到后台，下载会继续）", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    }
                    is UpdatePhase.Ready -> Text("安装包已下载并通过校验（sha256 + 签名证书）", style = t.typography.caption, color = t.colors.success)
                    UpdatePhase.Installing -> Text("正在安装…安装完成后 App 会重新启动", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    UpdatePhase.WaitingForUser -> Text("请在系统弹窗中确认安装", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                    UpdatePhase.NeedsUnknownSources -> StatusBanner("请在设置中允许本应用「安装未知应用」，返回后点“安装”", BannerKind.Info)
                    is UpdatePhase.Failed -> StatusBanner(phase.message, BannerKind.Error)
                    UpdatePhase.Idle -> Unit
                }
            }
        },
        confirmButton = {
            val (label, enabled) = when (phase) {
                is UpdatePhase.Downloading -> "下载中…" to false
                UpdatePhase.Installing -> "安装中…" to false
                is UpdatePhase.Ready, UpdatePhase.NeedsUnknownSources, UpdatePhase.WaitingForUser -> "安装" to true
                is UpdatePhase.Failed -> "重试" to true
                UpdatePhase.Idle -> "立即更新" to true
            }
            TextButton(onClick = ::onPrimary, enabled = enabled) { Text(label) }
        },
        dismissButton = if (s.force) null else {
            {
                TextButton(onClick = vm::dismiss) {
                    Text(if (phase is UpdatePhase.Downloading) "后台下载" else "稍后")
                }
            }
        },
    )
}
