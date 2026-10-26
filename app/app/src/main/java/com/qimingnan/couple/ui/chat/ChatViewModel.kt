package com.qimingnan.couple.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.chat.ChatReadReceipts
import com.qimingnan.couple.data.chat.ChatRepository
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
        /** 我的最后一条「TA 已读」的消息 id（显示在气泡下方）。 */
        val lastMineReadId: String? = null,
        val empty: Boolean = true,
    )

    private data class Core(
        val rows: List<ChatMessageEntity> = emptyList(),
        val hasMore: Boolean = false,
        val error: String? = null,
        val partnerReadUpTo: String? = null,
    )

    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private var visible = false
    private var readJob: Job? = null

    val state: StateFlow<UiState> = combine(
        combine(repo.messages, repo.hasMore, repo.error, repo.partnerReadUpTo) { rows, hasMore, error, readUpTo ->
            Core(rows, hasMore, error, readUpTo)
        },
        names,
        mqtt.presence,
        couple.state,
        session.state,
    ) { core, nameMap, presence, coupleState, sessionState ->
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
            lastMineReadId = ChatReadReceipts.lastMineReadId(core.rows, core.partnerReadUpTo, selfId),
            empty = core.rows.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        repo.start()
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
        repo.send(text, replyTo)
    }

    fun retryPending(id: String) = repo.retryPending(id)

    fun recall(id: String) = viewModelScope.launch { repo.recall(id) }

    fun loadOlder() = viewModelScope.launch { repo.loadOlder() }
}
