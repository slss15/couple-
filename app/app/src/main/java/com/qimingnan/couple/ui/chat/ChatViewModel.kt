package com.qimingnan.couple.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.chat.ChatReadReceipts
import com.qimingnan.couple.data.chat.ChatRepository
import com.qimingnan.couple.data.chat.ReceiptStatus
import com.qimingnan.couple.data.chat.TypingEvent
import com.qimingnan.couple.data.chat.TypingThrottle
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.db.ChatMessageEntity
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.notify.Notifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: ChatRepository,
    private val profileDao: ProfileDao,
    private val couple: CoupleRepository,
    private val session: SessionManager,
    private val mqtt: MqttManager,
    private val notifier: Notifier,
) : ViewModel() {

    data class UiState(
        val rows: List<ChatMessageEntity> = emptyList(),
        val selfId: String? = null,
        val partnerId: String? = null,
        val partnerName: String = "TA",
        val partnerOnline: Boolean = false,
        val hasMore: Boolean = false,
        val error: String? = null,
        /** 我方每条消息的状态：已读 / 未读 / 发送中 / 失败（显示在气泡下方）。 */
        val receipts: Map<String, ReceiptStatus> = emptyMap(),
        val empty: Boolean = true,
        /** 伴侣正在输入（最后一次 typing=true 不超过 6 秒）。 */
        val partnerTyping: Boolean = false,
    )

    private data class Core(
        val rows: List<ChatMessageEntity> = emptyList(),
        val hasMore: Boolean = false,
        val error: String? = null,
        val partnerReadUpTo: String? = null,
    )

    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private val partnerTyping = MutableStateFlow(false)
    private var partnerTypingJob: Job? = null
    private val typingThrottle = TypingThrottle()
    private var idleJob: Job? = null
    private var visible = false
    private var readJob: Job? = null

    val state: StateFlow<UiState> = combine(
        combine(repo.messages, repo.hasMore, repo.error, repo.partnerReadUpTo) { rows, hasMore, error, readUpTo ->
            Core(rows, hasMore, error, readUpTo)
        },
        names,
        combine(mqtt.presence, partnerTyping, ::Pair),
        couple.state,
        session.state,
    ) { core, nameMap, (presence, typing), coupleState, sessionState ->
        val selfId = (sessionState as? SessionState.LoggedIn)?.user?.id
        val members = (coupleState as? CoupleState.Paired)?.couple?.members.orEmpty()
        val partnerId = members.firstOrNull { it.id != selfId }?.id
            ?: core.rows.firstOrNull { it.senderId != selfId }?.senderId
        UiState(
            rows = core.rows,
            selfId = selfId,
            partnerId = partnerId,
            partnerName = partnerId?.let { nameMap[it] } ?: "TA",
            partnerOnline = partnerId?.let { presence[it]?.online } == true,
            hasMore = core.hasMore,
            error = core.error,
            receipts = ChatReadReceipts.statuses(core.rows, core.partnerReadUpTo, selfId),
            empty = core.rows.isEmpty(),
            partnerTyping = typing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        repo.start()
        viewModelScope.launch {
            // MQTT 与演示进程内流不会同时活跃；两个都收。
            merge(mqtt.typingEvents, DemoBackend.typingEvents).collect { onTypingEvent(it) }
        }
        viewModelScope.launch {
            profileDao.observeAll().collect { list ->
                names.value = list.associate { it.id to it.displayName.ifBlank { it.username } }
            }
        }
        viewModelScope.launch {
            repo.messages.collect { rows ->
                if (!visible) return@collect
                val pid = state.value.partnerId ?: rows.firstOrNull { it.senderId != selfId() }?.senderId ?: return@collect
                val newest = rows.lastOrNull { it.senderId == pid && it.pendingState == null } ?: return@collect
                scheduleRead(newest.id)
            }
        }
    }

    private fun onTypingEvent(payload: String) {
        val e = TypingEvent.parse(payload) ?: return
        if (e.userId == selfId()) return // 自己发出的回显
        partnerTypingJob?.cancel()
        if (!e.typing) {
            partnerTyping.value = false
            return
        }
        partnerTyping.value = true
        // 以本地收到时间计 6 秒过期（不依赖两端时钟一致），丢了 typing=false 也会自动消失。
        partnerTypingJob = viewModelScope.launch {
            delay(TypingThrottle.STALE_MS)
            partnerTyping.value = false
        }
    }

    /** 输入框文本变化：节流发布 typing=true（≤1 次/3 秒），5 秒无变化发布 typing=false。 */
    fun onInputChanged(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (typingThrottle.onTextChanged(System.currentTimeMillis())) mqtt.publishTyping(true)
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(TypingThrottle.IDLE_MS)
            stopTyping()
        }
    }

    private fun stopTyping() {
        idleJob?.cancel()
        idleJob = null
        if (typingThrottle.onStopped()) mqtt.publishTyping(false)
    }

    override fun onCleared() {
        stopTyping()
        super.onCleared()
    }

    private fun selfId(): String? = (session.state.value as? SessionState.LoggedIn)?.user?.id

    /** 进入聊天 Tab：补拉 + 标记已读。 */
    fun enter() {
        visible = true
        notifier.setScreenVisible("chat", true)
        viewModelScope.launch {
            repo.catchUp()
            val rows = state.value.rows
            val pid = state.value.partnerId ?: rows.firstOrNull { it.senderId != selfId() }?.senderId
            rows.lastOrNull { it.senderId == pid && it.pendingState == null }?.let { repo.markRead(it.id) }
        }
    }

    fun leave() {
        visible = false
        notifier.setScreenVisible("chat", false)
        readJob?.cancel()
    }

    private fun scheduleRead(upToId: String) {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            delay(400) // 连续消息合并成一次已读上报
            repo.markRead(upToId)
        }
    }

    fun send(text: String, replyTo: String?) {
        stopTyping()
        repo.send(text, replyTo)
    }

    fun sendImage(uri: android.net.Uri, replyTo: String?) = repo.sendImage(uri, replyTo)

    fun sendSticker(s: com.qimingnan.couple.data.net.StickerResult, replyTo: String?) = repo.sendSticker(s, replyTo)

    /** 表情包面板：搜索结果（输入停顿 400ms 后搜索）。 */
    val stickers = kotlinx.coroutines.flow.MutableStateFlow<List<com.qimingnan.couple.data.net.StickerResult>>(emptyList())
    val stickerLoading = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var stickerJob: kotlinx.coroutines.Job? = null

    fun searchStickers(q: String) {
        stickerJob?.cancel()
        if (q.isBlank()) { stickers.value = emptyList(); stickerLoading.value = false; return }
        stickerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            stickerLoading.value = true
            stickers.value = repo.searchStickers(q)
            stickerLoading.value = false
        }
    }

    fun mediaUrl(mediaId: String, thumb: Boolean) = repo.mediaUrl(mediaId, thumb)

    fun retryPending(id: String) = repo.retryPending(id)

    fun recall(id: String) = viewModelScope.launch { repo.recall(id) }

    fun loadOlder() = viewModelScope.launch { repo.loadOlder() }
}
