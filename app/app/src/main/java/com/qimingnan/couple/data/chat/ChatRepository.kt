package com.qimingnan.couple.data.chat

import android.content.Context
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.db.ChatDao
import com.qimingnan.couple.data.db.ChatMessageEntity
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.mqtt.MqttState
import com.qimingnan.couple.data.net.ChatEvent
import com.qimingnan.couple.data.net.ChatReadRequest
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.SendMessageRequest
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.net.humanMessage
import com.qimingnan.couple.di.ApplicationScope
import com.qimingnan.couple.notify.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天仓库（contract "Chat"）：Room 是 UI 的唯一数据源。
 * - 发送：本地 PENDING 行 → [ChatSendWorker] POST（幂等，离线退避重试，进程死亡后 WorkManager 继续）；
 * - 推送：MQTT / 演示进程内流合并（按 id 去重）；
 * - 补拉：重连/进入聊天页时 `after=最后一条已确认` 循环直到 hasMore=false，再拉 /chat/state；
 * - 已读：聊天页可见时由 ViewModel 调 [markRead]（防抖后调用，服务端单调）。
 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: CoupleApi,
    private val dao: ChatDao,
    private val session: SessionManager,
    private val mqtt: MqttManager,
    private val notifier: Notifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val messages = dao.observeMessages()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _myReadUpTo = MutableStateFlow<String?>(null)
    val myReadUpTo: StateFlow<String?> = _myReadUpTo.asStateFlow()

    private val _partnerReadUpTo = MutableStateFlow<String?>(null)
    val partnerReadUpTo: StateFlow<String?> = _partnerReadUpTo.asStateFlow()

    private var started = false
    private var hasMoreInitialized = false
    private var lastReadSent: String? = null
    private val catchUpMutex = Mutex()
    private val readMutex = Mutex()

    /** AppCoordinator.start() 之后调用一次。 */
    fun start() {
        if (started) return
        started = true
        notifier.createChannels()
        scope.launch {
            // MQTT 与演示进程内流不会同时活跃；两个都收。
            launch { mqtt.chatEvents.collect { applyEvent(it) } }
            launch { DemoBackend.chatEvents.collect { applyEvent(it) } }
            launch {
                mqtt.state.collect {
                    if (it is MqttState.Connected) catchUp()
                }
            }
        }
    }

    private suspend fun applyEvent(payload: String) {
        when (val e = ChatEvent.parse(payload) ?: return) {
            is ChatEvent.Message -> {
                val existed = dao.message(e.message.id) != null
                dao.upsert(e.message.toEntity())
                if (!existed && e.message.senderId != selfId() && e.message.recalledAt == null) {
                    maybeNotify(e.message.text)
                }
            }
            is ChatEvent.Recall -> dao.markRecalled(e.id, e.recalledAt)
            is ChatEvent.Read -> {
                if (e.userId == selfId()) _myReadUpTo.value = e.upToId else _partnerReadUpTo.value = e.upToId
            }
        }
    }

    private fun maybeNotify(text: String) {
        if (text.isBlank()) return
        if (notifier.isScreenVisible("chat")) return
        notifier.notify(Notifier.CH_CHAT, Notifier.ID_CHAT, "新消息", text)
    }

    private fun selfId(): String? = (session.state.value as? SessionState.LoggedIn)?.user?.id

    /** 重连 / 进入聊天页 / 前台恢复：补拉新消息与已读状态。 */
    suspend fun catchUp() {
        catchUpMutex.withLock {
            runCatching {
                val lastKnown = dao.newestConfirmedId()
                if (lastKnown == null) {
                    val page = api.chatMessages(after = null, before = null, limit = PAGE)
                    dao.upsertAll(page.messages.map { it.toEntity() })
                    _hasMore.value = page.hasMore
                    hasMoreInitialized = true
                } else {
                    var cursor = lastKnown
                    var loops = 0
                    while (loops < MAX_CATCH_UP_LOOPS) {
                        val page = api.chatMessages(after = cursor, before = null, limit = PAGE)
                        if (page.messages.isEmpty()) break
                        dao.upsertAll(page.messages.map { it.toEntity() })
                        cursor = page.messages.last().id
                        if (!page.hasMore) break
                        loops++
                    }
                    if (!hasMoreInitialized) {
                        // 进程重启后本地已有历史：确认服务器上是否还有更旧的一页。
                        val oldest = dao.oldestConfirmedId()
                        _hasMore.value = oldest != null && api.chatMessages(before = oldest, limit = 1).messages.isNotEmpty()
                        hasMoreInitialized = true
                    }
                }
                val state = api.chatState()
                _myReadUpTo.value = state.myReadUpTo
                _partnerReadUpTo.value = state.partnerReadUpTo
                _error.value = null
            }.onFailure {
                _error.value = it.message ?: it.javaClass.simpleName
            }
        }
    }

    /** 上滑加载更早的一页（before = 本地最旧的已确认消息）。 */
    suspend fun loadOlder() {
        val oldest = dao.oldestConfirmedId() ?: return
        if (!_hasMore.value) return
        runCatching { api.chatMessages(before = oldest, limit = PAGE) }
            .onSuccess { page ->
                dao.upsertAll(page.messages.map { it.toEntity() })
                _hasMore.value = page.hasMore
                hasMoreInitialized = true
            }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
    }

    /** 本地插入 PENDING 行并交给 worker；返回新消息 id（供 UI 立即定位）。 */
    fun send(text: String, replyTo: String?): String? {
        val me = selfId() ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val id = Ulid.next()
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            dao.upsert(
                ChatMessageEntity(
                    id = id, senderId = me, text = trimmed, replyTo = replyTo, clientTs = now,
                    createdAt = null, sortAt = now, recalledAt = null, pendingState = PENDING,
                ),
            )
            ChatSendWorker.enqueue(context, id)
        }
        return id
    }

    fun retryPending(id: String) {
        scope.launch(Dispatchers.IO) {
            dao.updatePendingState(id, PENDING)
            ChatSendWorker.enqueue(context, id)
        }
    }

    /** worker 调用：POST；IO 失败保持 PENDING（WorkManager 退避），4xx（409/429 除外）→ FAILED。 */
    suspend fun sendPending(id: String): Boolean {
        val row = dao.message(id) ?: return true
        if (row.pendingState != PENDING) return true
        return try {
            val sent = api.sendMessage(SendMessageRequest(id = id, text = row.text, replyTo = row.replyTo, clientTs = row.clientTs))
            val createdAt = sent.createdAt.takeIf { it > 0 } ?: row.clientTs
            dao.confirm(sent.id, sent.text, createdAt, createdAt, sent.recalledAt)
            true
        } catch (e: HttpException) {
            val code = e.code()
            if (code != 409 && code != 429) dao.updatePendingState(id, FAILED)
            false
        } catch (e: SerializationException) {
            dao.updatePendingState(id, FAILED)
            false
        } catch (e: Exception) {
            false // 网络/IO：保持 PENDING 等待重试
        }
    }

    /** 撤回（仅自己、120 秒内由服务端校验）。 */
    suspend fun recall(id: String) {
        when (val r = apiCall { api.recallMessage(id) }) {
            is com.qimingnan.couple.data.net.ApiResult.Ok -> dao.markRecalled(id, r.value.recalledAt ?: System.currentTimeMillis())
            is com.qimingnan.couple.data.net.ApiResult.Err -> _error.value = r.error.humanMessage()
        }
    }

    /** 聊天页可见时上报已读；服务端单调，重复/回退会被忽略。 */
    suspend fun markRead(upToId: String) {
        readMutex.withLock {
            if (upToId == lastReadSent || upToId == _myReadUpTo.value) return
            when (val r = apiCall { api.markChatRead(ChatReadRequest(upToId)) }) {
                is com.qimingnan.couple.data.net.ApiResult.Ok -> {
                    lastReadSent = upToId
                    _myReadUpTo.value = upToId
                }
                is com.qimingnan.couple.data.net.ApiResult.Err -> Unit // 下次出现新消息时重试
            }
        }
    }

    fun clearLocal() {
        scope.launch(Dispatchers.IO) {
            dao.clearAll()
            _hasMore.value = false
            _myReadUpTo.value = null
            _partnerReadUpTo.value = null
            _error.value = null
            lastReadSent = null
            hasMoreInitialized = false
        }
    }

    companion object {
        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
        const val PAGE = 50
        private const val MAX_CATCH_UP_LOOPS = 20
    }
}
