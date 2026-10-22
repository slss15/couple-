package com.qimingnan.couple.data.mqtt

import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.auth.DeviceIdentity
import com.qimingnan.couple.data.auth.TokenRefresher
import com.qimingnan.couple.di.ApplicationScope
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
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

/** contract: couple/{cid}/presence（retained）{userId, online, ts} */
@Serializable
data class Presence(val userId: String, val online: Boolean, val ts: Long)

sealed interface MqttState {
    data object Idle : MqttState
    data object Connecting : MqttState
    data class Connected(val url: String) : MqttState
    data class Failed(val reason: String, val retryInMillis: Long?) : MqttState
}

data class MqttTarget(val url: String, val userId: String, val coupleId: String)

object MqttTopics {
    fun presence(coupleId: String) = "couple/$coupleId/presence"
}

/**
 * P0：只证明 MQTT over WSS 能连通。
 * username = userId，password = accessToken；连上后发布 retained 在线状态，遗嘱为 retained 离线状态。
 * 不用 Paho 自带的自动重连：每次重连都要换上最新的 access token。
 */
@Singleton
class MqttManager @Inject constructor(
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

    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    /** 订阅到的在线状态（userId -> Presence），“我的”页面显示 TA 是否在线。 */
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    /** 目标变化（登录/配对/换服务器）时调用；null 表示断开。 */
    fun setTarget(newTarget: MqttTarget?) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (newTarget == target && client?.isConnected == true) return@withLock
                disconnectLocked(publishOffline = true)
                target = newTarget
                attempt = 0
                if (newTarget != null) connectLocked(newTarget)
            }
        }
    }

    fun reconnectNow() {
        val t = target ?: return
        scope.launch(Dispatchers.IO) { mutex.withLock { attempt = 0; disconnectLocked(false); connectLocked(t) } }
    }

    private suspend fun connectLocked(t: MqttTarget) {
        reconnectJob?.cancel()
        _state.value = MqttState.Connecting
        val token = refresher.validAccessTokenBlocking()
        if (token == null) {
            scheduleReconnect(t, "没有可用的登录凭证")
            return
        }
        val clientId = "a-${t.userId}-${device.deviceId}".take(64)
        val c = try {
            MqttAsyncClient(t.url, clientId, MemoryPersistence())
        } catch (e: Exception) {
            _state.value = MqttState.Failed("MQTT 地址无效：${e.message}", null)
            return
        }
        val topic = MqttTopics.presence(t.coupleId)
        val opts = MqttConnectOptions().apply {
            userName = t.userId
            password = token.toCharArray()
            isCleanSession = true
            keepAliveInterval = 60
            connectionTimeout = 15
            isAutomaticReconnect = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            setWill(topic, presenceBytes(t.userId, online = false), 1, true)
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                _state.value = MqttState.Failed("连接断开：${cause?.message ?: "未知原因"}", null)
                scope.launch(Dispatchers.IO) { mutex.withLock { if (target == t) scheduleReconnect(t, cause?.message) } }
            }

            override fun messageArrived(topicName: String?, message: MqttMessage?) {
                if (message == null || topicName != topic) return
                runCatching {
                    CoupleJson.decodeFromString(Presence.serializer(), message.payload.toString(Charsets.UTF_8))
                }.onSuccess { p -> _presence.update { it + (p.userId to p) } }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        try {
            c.connect(opts).await()
            client = c
            attempt = 0
            c.subscribe(topic, 1).await()
            c.publish(topic, presenceBytes(t.userId, online = true), 1, true).await()
            _state.value = MqttState.Connected(t.url)
        } catch (e: MqttException) {
            runCatching { c.close(true) }
            val authFailed = e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            if (authFailed) {
                // token 可能刚好过期或被吊销：强制刷新一次再重连
                refresher.refreshBlocking(token)
            }
            scheduleReconnect(t, "${e.reasonCode} ${e.message}")
        } catch (e: Exception) {
            runCatching { c.close(true) }
            scheduleReconnect(t, e.message)
        }
    }

    private fun scheduleReconnect(t: MqttTarget, reason: String?) {
        attempt++
        val backoff = min(60_000L, 2_000L shl min(attempt - 1, 5))
        _state.value = MqttState.Failed(reason ?: "连接失败", backoff)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(backoff)
            mutex.withLock {
                if (target == t && client?.isConnected != true) connectLocked(t)
            }
        }
    }

    private suspend fun disconnectLocked(publishOffline: Boolean) {
        reconnectJob?.cancel()
        val c = client ?: run { _state.value = MqttState.Idle; return }
        val t = target
        try {
            if (publishOffline && c.isConnected && t != null) {
                c.publish(MqttTopics.presence(t.coupleId), presenceBytes(t.userId, online = false), 1, true).await()
            }
            if (c.isConnected) c.disconnect(2_000).await()
        } catch (_: Exception) {
        } finally {
            runCatching { c.close(true) }
            client = null
            _presence.value = emptyMap()
            _state.value = MqttState.Idle
        }
    }

    private fun presenceBytes(userId: String, online: Boolean): ByteArray =
        CoupleJson.encodeToString(Presence.serializer(), Presence(userId, online, clock.nowMillis())).toByteArray()
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
