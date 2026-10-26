package com.qimingnan.couple.data.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.auth.DeviceIdentity
import com.qimingnan.couple.data.auth.TokenRefresher
import com.qimingnan.couple.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClientPersistence
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * contract: couple/{cid}/presence (retained) {userId, online, ts}.
 * Optional additions (older clients omit them): mid = ULID messageId for dedup/ordering; sess = the sender's connection
 * start, and will = true on the Last Will, so the receiver can order a will (built at connect time, sent by the broker
 * much later) after the rest of its session. ts is the send timestamp used for latency.
 * Without mid the receiver derives a deterministic id (see [InboundFilter.legacyId]).
 */
@Serializable
data class Presence(
    val userId: String,
    val online: Boolean,
    val ts: Long,
    val mid: String? = null,
    val sess: Long? = null,
    val will: Boolean? = null,
)

sealed interface MqttState {
    data object Idle : MqttState
    data object Connecting : MqttState
    data class Connected(val url: String) : MqttState
    data class Failed(val reason: String, val retryInMillis: Long?) : MqttState
}

data class MqttTarget(val url: String, val userId: String, val coupleId: String)

object MqttTopics {
    fun presence(coupleId: String) = "couple/$coupleId/presence"

    /** 服务端事件推送（contract "Feed"；客户端只订阅，ACL 拒绝客户端发布）。 */
    fun feed(coupleId: String) = "couple/$coupleId/feed"

    /** 服务端事件推送（contract "Chat"；客户端只订阅，ACL 拒绝客户端发布）。 */
    fun chat(coupleId: String) = "couple/$coupleId/chat"
}

/**
 * MQTT over WSS (through Cloudflare).
 *
 * Reliability rules (R6):
 * - keepAlive 30 s: Cloudflare drops idle WebSockets after ~100 s; 30 s keeps traffic well inside that, and a dead
 *   socket is noticed within ~45 s even without a network callback.
 * - cleanSession=false + stable client id ([MqttClientIds]) + QoS1 subscriptions: the broker keeps the session and
 *   queues QoS1 messages while we are away (Doze, network switch, process death). Outbound in-flight QoS1 messages are
 *   persisted on disk so Paho resends them after a restart.
 * - Reconnect immediately when the default network changes (Wi-Fi <-> cellular, network back after none), otherwise
 *   exponential backoff with ±20 % jitter ([Backoff]).
 * - Inbound messages are deduplicated by messageId and kept in per-sender order ([InboundFilter]).
 * - Per-message latency (receive - send ts) and reconnect counts are kept in [stats] (shown in 我的 → 后台保活, and
 *   logged under tag CoupleMqtt).
 * Paho's own automatic reconnect stays off: every reconnect must carry the latest access token.
 */
@Singleton
class MqttManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val refresher: TokenRefresher,
    private val device: DeviceIdentity,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val clock: Clock = Clock.System
    private val mutex = Mutex()
    private var client: MqttAsyncClient? = null
    private var target: MqttTarget? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var connectedBefore = false
    private var networkAvailable = true
    /** Start of the current connection attempt; sent as `sess` so receivers can order our Last Will correctly. */
    @Volatile private var sessionStart = 0L

    private val backoff = Backoff()
    private val inbound = InboundFilter()
    private val latency = LatencyStats()
    private val networkDetector = NetworkChangeDetector()
    /** mid -> send ts of our own publishes, to measure the broker echo. */
    private val sentByMe = ConcurrentHashMap<String, Long>()

    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    /** 订阅到的在线状态（userId -> Presence），“我的”页面显示 TA 是否在线。 */
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    private val _feedEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 128)
    /** couple/{cid}/feed 的原始 JSON 载荷（由 FeedRepository 解析合并）。 */
    val feedEvents: kotlinx.coroutines.flow.SharedFlow<String> = _feedEvents

    private val _chatEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 128)
    /** couple/{cid}/chat 的原始 JSON 载荷（由 ChatRepository 解析合并）。 */
    val chatEvents: kotlinx.coroutines.flow.SharedFlow<String> = _chatEvents

    private val _stats = MutableStateFlow(latency.snapshot())
    val stats: StateFlow<LatencyStats.Snapshot> = _stats.asStateFlow()

    private val _lastEvent = MutableStateFlow<String?>(null)
    /** Last connection event in words (network switch, reconnect reason), for the diagnostics card. */
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    init {
        registerNetworkCallback()
    }

    /** 目标变化（登录/配对/换服务器）时调用；null 表示断开。 */
    fun setTarget(newTarget: MqttTarget?) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (newTarget == target && client?.isConnected == true) return@withLock
                disconnectLocked(publishOffline = true, fast = false)
                if (newTarget != target) {
                    _presence.value = emptyMap()
                    inbound.reset()
                    connectedBefore = false
                }
                target = newTarget
                attempt = 0
                if (newTarget != null) connectLocked(newTarget) else _state.value = MqttState.Idle
            }
        }
    }

    /** 调试账号（离线演示）：不连 broker，直接给出伴侣在线状态。 */
    fun setDemoPresence(presence: Map<String, Presence>) {
        _presence.value = presence
        _state.value = MqttState.Idle
    }

    fun reconnectNow(reason: String = "手动重连") {
        val t = target ?: return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (target != t) return@withLock
                _lastEvent.value = reason
                attempt = 0
                disconnectLocked(publishOffline = false, fast = true)
                connectLocked(t)
            }
        }
    }

    /**
     * Debug/measurement hook (used by app/scripts/reliability-test.sh through a debug-only broadcast): publishes the
     * current presence (online) once more with a fresh messageId and logs it as `tx mid=… ts=… tag=…`.
     */
    fun publishProbe(tag: String?) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val t = target
                val c = client
                if (t == null || c == null || !c.isConnected) {
                    Log.i(TAG, "tx skipped (not connected) tag=$tag")
                    return@withLock
                }
                val bytes = presenceBytes(t.userId, online = true, track = true)
                val p = CoupleJson.decodeFromString(Presence.serializer(), bytes.toString(Charsets.UTF_8))
                runCatching { c.publish(MqttTopics.presence(t.coupleId), bytes, 1, true) }
                    .onSuccess { Log.i(TAG, "tx mid=${p.mid} ts=${p.ts} tag=${tag ?: "-"}") }
                    .onFailure { Log.w(TAG, "tx failed tag=$tag: $it") }
            }
        }
    }

    private suspend fun connectLocked(t: MqttTarget) {
        reconnectJob?.cancel()
        _state.value = MqttState.Connecting
        val token = refresher.validAccessTokenBlocking()
        if (token == null) {
            scheduleReconnect(t, "没有可用的登录凭证")
            return
        }
        val clientId = MqttClientIds.forDevice(t.userId, device.deviceId)
        val c = try {
            MqttAsyncClient(t.url, clientId, persistence())
        } catch (e: Exception) {
            _state.value = MqttState.Failed("MQTT 地址无效：${e.message}", null)
            return
        }
        val topic = MqttTopics.presence(t.coupleId)
        sessionStart = clock.nowMillis()
        val opts = MqttConnectOptions().apply {
            userName = t.userId
            password = token.toCharArray()
            isCleanSession = false
            keepAliveInterval = KEEP_ALIVE_SECONDS
            connectionTimeout = 15
            isAutomaticReconnect = false
            maxInflight = 50
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            setWill(topic, presenceBytes(t.userId, online = false, track = false, will = true), 1, true)
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                scope.launch(Dispatchers.IO) {
                    mutex.withLock {
                        // Ignore callbacks from a client we already replaced.
                        if (target == t && client === c) {
                            client = null
                            runCatching { c.close(true) }
                            scheduleReconnect(t, "连接断开：${cause?.message ?: "未知原因"}")
                        }
                    }
                }
            }

            override fun messageArrived(topicName: String?, message: MqttMessage?) {
                if (message == null || topicName == null) return
                if (topicName == topic) {
                    onPresence(t, message)
                } else if (topicName == MqttTopics.feed(t.coupleId)) {
                    // 服务端事件：载荷原样下发，解析与合并在 FeedRepository（坏载荷不影响连接）。
                    _feedEvents.tryEmit(message.payload.toString(Charsets.UTF_8))
                } else if (topicName == MqttTopics.chat(t.coupleId)) {
                    _chatEvents.tryEmit(message.payload.toString(Charsets.UTF_8))
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        try {
            c.connect(opts).await()
            client = c
            attempt = 0
            if (connectedBefore) latency.recordReconnect()
            connectedBefore = true
            // Re-subscribing is cheap and also restores the subscription if the broker expired our session.
            c.subscribe(topic, 1).await()
            c.subscribe(MqttTopics.feed(t.coupleId), 1).await()
            c.subscribe(MqttTopics.chat(t.coupleId), 1).await()
            c.publish(topic, presenceBytes(t.userId, online = true, track = true), 1, true).await()
            _state.value = MqttState.Connected(t.url)
            publishStats("connected")
        } catch (e: MqttException) {
            if (client === c) client = null
            runCatching { c.close(true) }
            val authFailed = e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            if (authFailed) {
                // token 可能刚好过期或被吊销：强制刷新一次再重连
                refresher.refreshBlocking(token)
            }
            scheduleReconnect(t, "${e.reasonCode} ${e.message}")
        } catch (e: Exception) {
            if (client === c) client = null
            runCatching { c.close(true) }
            scheduleReconnect(t, e.message)
        }
    }

    private fun onPresence(t: MqttTarget, message: MqttMessage) {
        val now = clock.nowMillis()
        val p = runCatching {
            CoupleJson.decodeFromString(Presence.serializer(), message.payload.toString(Charsets.UTF_8))
        }.getOrNull() ?: return
        val mid = p.mid ?: InboundFilter.legacyId(p.userId, p.ts, p.online.toString())
        val verdict = inbound.offer(p.userId, mid, p.ts, session = p.sess ?: p.ts, isWill = p.will == true)
        if (BuildConfig.DEBUG) {
            // Per-message trace for the real-device reliability script (debug builds only).
            Log.i(TAG, "rx mid=$mid from=${p.userId} sendTs=${p.ts} rxTs=$now retained=${message.isRetained} " +
                "dup=${message.isDuplicate} verdict=$verdict")
        }
        when (verdict) {
            InboundFilter.Verdict.DUPLICATE -> latency.recordDuplicate()
            // Presence is a state topic: an older state must never overwrite a newer one.
            InboundFilter.Verdict.STALE -> latency.recordStale()
            InboundFilter.Verdict.ACCEPT -> {
                val mine = p.mid?.let { sentByMe.remove(it) }
                when {
                    mine != null -> latency.recordEcho(mine, now)
                    // A retained replay or a Last Will (built at connect time) says nothing about delivery latency.
                    !message.isRetained && p.will != true && p.userId != t.userId -> latency.recordPeer(p.ts, now)
                }
                _presence.update { it + (p.userId to p) }
            }
        }
        publishStats(null)
    }

    private fun scheduleReconnect(t: MqttTarget, reason: String?) {
        reconnectJob?.cancel()
        if (!networkAvailable) {
            // Nothing to retry against; the network callback reconnects as soon as a network is back.
            _state.value = MqttState.Failed("网络不可用，恢复后自动重连", null)
            return
        }
        attempt++
        val wait = backoff.delayFor(attempt)
        _state.value = MqttState.Failed(reason ?: "连接失败", wait)
        Log.i(TAG, "reconnect #$attempt in ${wait}ms: $reason")
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(wait)
            mutex.withLock {
                if (target == t && client?.isConnected != true) connectLocked(t)
            }
        }
    }

    /**
     * @param fast skip the graceful DISCONNECT handshake (used on network changes, where the old socket is
     * usually already dead and waiting for it would only delay the reconnect).
     */
    private suspend fun disconnectLocked(publishOffline: Boolean, fast: Boolean) {
        reconnectJob?.cancel()
        val c = client ?: return
        client = null
        val t = target
        try {
            if (publishOffline && c.isConnected && t != null) {
                c.publish(MqttTopics.presence(t.coupleId), presenceBytes(t.userId, online = false, track = false), 1, true).await()
            }
            if (fast) {
                runCatching { c.disconnectForcibly(0, 500, false) }
            } else if (c.isConnected) {
                c.disconnect(2_000).await()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { c.close(true) }
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                handle(networkDetector.onAvailable(info(network, caps)))
            }

            override fun onLost(network: Network) {
                handle(networkDetector.onLost(network.toString()))
            }
        }
        if (cm.activeNetwork == null) {
            networkDetector.markOffline()
            networkAvailable = false
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    private fun handle(action: NetworkChangeDetector.Action) {
        when (action) {
            NetworkChangeDetector.Action.None -> networkAvailable = true
            NetworkChangeDetector.Action.Lost -> {
                networkAvailable = false
                _lastEvent.value = "网络断开"
                Log.i(TAG, "default network lost")
            }
            is NetworkChangeDetector.Action.Reconnect -> {
                networkAvailable = true
                latency.recordNetworkChange()
                Log.i(TAG, "network change -> reconnect now: ${action.reason}")
                if (action.force || _state.value !is MqttState.Connected) reconnectNow(action.reason)
            }
        }
    }

    private fun info(network: Network, caps: NetworkCapabilities?): NetworkChangeDetector.NetworkInfo {
        val transports = buildSet {
            if (caps == null) return@buildSet
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkChangeDetector.Transport.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkChangeDetector.Transport.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkChangeDetector.Transport.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkChangeDetector.Transport.VPN)
            if (isEmpty()) add(NetworkChangeDetector.Transport.OTHER)
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return NetworkChangeDetector.NetworkInfo(network.toString(), transports, validated)
    }

    private fun persistence(): MqttClientPersistence = runCatching {
        val dir = File(context.noBackupFilesDir, "mqtt").apply { mkdirs() }
        MqttDefaultFilePersistence(dir.absolutePath)
    }.getOrElse { MemoryPersistence() }

    private fun publishStats(event: String?) {
        val snap = latency.snapshot()
        _stats.value = snap
        if (event != null || (snap.echoCount + snap.peerCount) % LOG_EVERY == 0) {
            Log.i(
                TAG,
                "stats${event?.let { "[$it]" } ?: ""}: echo n=${snap.echoCount} p50=${snap.echoP50}ms p95=${snap.echoP95}ms; " +
                    "peer n=${snap.peerCount} p50=${snap.peerP50}ms p95=${snap.peerP95}ms; reconnects=${snap.reconnects} " +
                    "netChanges=${snap.networkChanges} dup=${snap.duplicatesDropped} stale=${snap.staleDropped}",
            )
        }
    }

    private fun presenceBytes(userId: String, online: Boolean, track: Boolean, will: Boolean = false): ByteArray {
        val ts = clock.nowMillis()
        val mid = Ulid.next(ts)
        if (track) {
            sentByMe[mid] = ts
            if (sentByMe.size > 256) sentByMe.keys.take(sentByMe.size - 256).forEach(sentByMe::remove)
        }
        val p = Presence(userId, online, ts, mid, sess = sessionStart, will = if (will) true else null)
        return CoupleJson.encodeToString(Presence.serializer(), p).toByteArray()
    }

    companion object {
        const val TAG = "CoupleMqtt"
        const val KEEP_ALIVE_SECONDS = 30
        private const val LOG_EVERY = 20
    }
}

private suspend fun IMqttToken.await(): Unit = suspendCoroutine { cont ->
    val done = AtomicBoolean(false)
    fun finish(ex: Throwable?) {
        if (!done.compareAndSet(false, true)) return
        if (ex == null) cont.resume(Unit) else cont.resumeWithException(ex)
    }
    actionCallback = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) = finish(null)
        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) =
            finish(exception ?: MqttException(MqttException.REASON_CODE_UNEXPECTED_ERROR.toInt()))
    }
    // 已完成的 token 不会再回调
    if (isComplete) finish(exception)
}
