package com.qimingnan.couple.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.NotifySettings
import com.qimingnan.couple.data.net.NotifyUid
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** 设置 → 消息提醒（contract "Chat → WxPusher reminders"）：App 离线一段时间后，TA 的消息通过 WxPusher 提醒到微信。 */
@HiltViewModel
class NotifyViewModel @Inject constructor(private val api: CoupleApi) : ViewModel() {
    data class UiState(
        val uid: String = "",
        val preview: Boolean = false,
        val loaded: Boolean = false,
        val busy: Boolean = false,
    ) {
        val uidValid: Boolean get() = NotifyUid.isValid(uid)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { api.notifySettings() }
                .onSuccess { s -> _state.update { it.copy(uid = s.wxpusherUid.orEmpty(), preview = s.preview, loaded = true) } }
                .onFailure { _state.update { it.copy(loaded = true) } }
        }
    }

    fun setUid(v: String) = _state.update { it.copy(uid = v.take(64)) }
    fun setPreview(v: Boolean) = _state.update { it.copy(preview = v) }

    fun save() {
        val s = _state.value
        if (s.busy || !s.uidValid) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { api.updateNotify(NotifySettings(NotifyUid.normalize(s.uid), s.preview)) }
                .onSuccess { r ->
                    _state.update { it.copy(uid = r.wxpusherUid.orEmpty(), preview = r.preview, busy = false) }
                    _messages.send(if (r.wxpusherUid == null) "已关闭微信提醒" else "已保存")
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _messages.send(if (e is HttpException && e.code() == 400) "UID 格式不对，应以 UID_ 开头" else "保存失败，请稍后再试")
                }
        }
    }
}

@Composable
fun NotifySection(vm: NotifyViewModel) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    SectionCard("消息提醒") {
        Text(
            "App 离线几分钟后，TA 的新消息会通过 WxPusher 提醒到微信（每 10 分钟最多一条）。关注 WxPusher 公众号后，在「我的 → 我的 UID」里复制 UID。",
            style = t.typography.caption,
            color = t.colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = s.uid,
            onValueChange = vm::setUid,
            label = { Text("WxPusher UID") },
            placeholder = { Text("UID_xxxxxxxx（留空 = 不提醒）") },
            singleLine = true,
            isError = !s.uidValid,
            supportingText = if (!s.uidValid) ({ Text("应以 UID_ 开头") }) else null,
            enabled = s.loaded && !s.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒里显示消息内容", style = t.typography.body, color = t.colors.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = s.preview, onCheckedChange = vm::setPreview, enabled = s.loaded && !s.busy)
        }
        Text(
            if (s.preview) "微信里会看到消息原文（图片 / 表情显示为 [图片] / [表情]）" else "微信里只显示「TA 发来一条消息」",
            style = t.typography.caption,
            color = t.colors.onSurfaceVariant,
        )
        TextButton(onClick = vm::save, enabled = s.loaded && !s.busy && s.uidValid) { Text(if (s.busy) "保存中…" else "保存") }
    }
}
