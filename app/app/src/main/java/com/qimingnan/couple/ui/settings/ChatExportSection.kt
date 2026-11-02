package com.qimingnan.couple.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.chat.ChatExportFormat
import com.qimingnan.couple.data.chat.ChatExportRepository
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 我的 → 设置 → 导出聊天记录：选格式 → 下载到缓存 → SAF CreateDocument 选位置 → 复制。
 * 下载好的文件留在 [pending] 里，旋转屏幕 / 进程内重建也不会丢；用户取消选位置就删掉缓存。
 */
@HiltViewModel
class ChatExportViewModel @Inject constructor(private val repo: ChatExportRepository) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pending = MutableStateFlow<ChatExportRepository.Downloaded?>(null)
    /** 已下载、等待用户在 SAF 里选保存位置的文件。 */
    val pending: StateFlow<ChatExportRepository.Downloaded?> = _pending.asStateFlow()

    /** SAF 是否已为 [pending] 打开过（避免重组时重复弹出）。 */
    var pickerLaunched = false

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun start(format: ChatExportFormat) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.download(format)
                .onSuccess { pickerLaunched = false; _pending.value = it }
                .onFailure { _busy.value = false; _messages.send(it.message ?: "导出失败") }
        }
    }

    fun onPicked(uri: Uri?) {
        val d = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            if (uri == null) {
                repo.discard(d)
                _messages.send("已取消导出")
            } else {
                repo.saveTo(d, uri)
                    .onSuccess { _messages.send("聊天记录已导出") }
                    .onFailure { _messages.send(it.message ?: "保存失败") }
            }
            _busy.value = false
        }
    }

    override fun onCleared() {
        _pending.value?.let(repo::discard)
    }
}

@Composable
fun ChatExportSection(vm: ChatExportViewModel) {
    val t = LocalCoupleTokens.current
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }

    // CreateDocument 的 MIME 在构造时固定，所以每种格式一个 launcher。
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ChatExportFormat.JSON.mime)) { vm.onPicked(it) }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ChatExportFormat.TXT.mime)) { vm.onPicked(it) }

    LaunchedEffect(Unit) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(pending) {
        val d = pending ?: return@LaunchedEffect
        if (vm.pickerLaunched) return@LaunchedEffect
        vm.pickerLaunched = true
        runCatching { (if (d.format == ChatExportFormat.JSON) jsonLauncher else txtLauncher).launch(d.suggestedName) }
            .onFailure { vm.onPicked(null) } // 没有可用的文件管理器
    }

    SectionCard("聊天记录") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = t.spacing.minTouchTarget)
                .clickable(enabled = !busy, role = Role.Button) { showDialog = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("导出聊天记录", style = t.typography.body, color = t.colors.onSurface)
                Text("导出你们的全部聊天，保存到手机或网盘（每分钟 1 次）", style = t.typography.caption, color = t.colors.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    if (showDialog) {
        var format by remember { mutableStateOf(ChatExportFormat.TXT) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("导出聊天记录") },
            text = {
                Column {
                    ChatExportFormat.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = t.spacing.minTouchTarget)
                                .selectable(selected = f == format, role = Role.RadioButton) { format = f },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = f == format, onClick = null)
                            Text(f.label, style = t.typography.body, color = t.colors.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false; vm.start(format) }) { Text("导出") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
        )
    }
}
