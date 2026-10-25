package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.config.AppConfig
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import io.moquette.broker.security.IAuthorizatorPolicy
import io.moquette.broker.subscriptions.Topic
import io.moquette.interception.AbstractInterceptHandler
import io.moquette.interception.messages.InterceptConnectMessage
import io.moquette.interception.messages.InterceptConnectionLostMessage
import io.moquette.interception.messages.InterceptDisconnectMessage
import io.netty.buffer.Unpooled
import io.netty.handler.codec.mqtt.MqttMessageBuilders
import io.netty.handler.codec.mqtt.MqttQoS
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process MQTT broker (Moquette) for hosts that cannot run EMQX (Windows without virtualization).
 *
 * - MQTT over WebSocket on [AppConfig.mqttBindHost]:[AppConfig.mqttWsPort][AppConfig.mqttWsPath]; optional plain TCP.
 * - Auth: username = userId, password = access JWT ([MqttAccessService.authenticate], same as the EMQX hook),
 *   plus a client-id-to-user binding because persistent sessions are keyed by client id.
 * - ACL: [MqttAccessService.canAccess] for every SUBSCRIBE and PUBLISH, same rule as the EMQX ACL hook.
 * - Persistent sessions (cleanSession=false) keep subscriptions and queue QoS1/2 messages while the client is away;
 *   with [AppConfig.mqttDataDir] set they survive restarts. Sessions idle longer than
 *   [AppConfig.mqttSessionExpiry] are purged (Moquette 0.17 has no per-session queue length cap; expiry is the bound).
 * - Last Will: Moquette 0.17 delivers wills but (a) does not authorize the will topic and (b) does not store a
 *   retained will as the retained message. [WillGuard] closes both gaps: wills on foreign topics are stripped and the
 *   client is disconnected, and a retained will is re-published as a retained QoS1 message so a partner that connects
 *   later sees "offline" instead of a stale "online".
 */
class EmbeddedMqttBroker(
    private val config: AppConfig,
    private val access: MqttAccessService,
    /** Active couple ids, used to clear stale retained presence after a restart with a persistent store. */
    private val activeCoupleIds: suspend () -> List<String>,
) {
    private val log = LoggerFactory.getLogger(EmbeddedMqttBroker::class.java)
    private val server = Server()
    @Volatile private var running = false

    fun start() {
        val p = Properties()
        p["host"] = config.mqttBindHost
        p["port"] = config.mqttTcpPort?.toString() ?: "disabled"
        p["websocket_port"] = config.mqttWsPort.toString()
        p["websocket_path"] = config.mqttWsPath
        p["allow_anonymous"] = "false"
        p["allow_zero_byte_client_id"] = "false"
        p["reauthorize_subscriptions_on_connect"] = "true"
        p["telemetry_enabled"] = "false"
        p["netty.mqtt.message_size"] = config.mqttMaxMessageBytes.toString()
        p["websocket_max_frame_size"] = config.mqttMaxMessageBytes.toString()
        p["persistent_client_expiration"] = config.mqttSessionExpiry
        val dataDir = config.mqttDataDir
        if (dataDir != null) {
            p["persistence_enabled"] = "true"
            p["data_path"] = dataDir
            p["persistent_queue_type"] = "h2" // plain files; the segmented store memory-maps files (awkward on Windows)
        } else {
            p["persistence_enabled"] = "false"
        }
        server.startServer(MemoryConfig(p), listOf(WillGuard()), null, Authenticator(), Authorizer())
        running = true
        log.info(
            "Embedded MQTT broker listening on ws://{}:{}{}{} (persistence: {}, session expiry {})",
            config.mqttBindHost, config.mqttWsPort, config.mqttWsPath,
            config.mqttTcpPort?.let { ", tcp://${config.mqttBindHost}:$it" } ?: "",
            dataDir ?: "memory", config.mqttSessionExpiry,
        )
        if (dataDir != null) clearRetainedPresence()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server.stopServer() }.onFailure { log.warn("Embedded MQTT broker stop failed", it) }
        log.info("Embedded MQTT broker stopped")
    }

    /**
     * Wills live only in memory: after a restart a client that died meanwhile would stay "online" forever in the
     * persisted retained store. Clear couple/{cid}/presence; live clients re-publish on reconnect.
     */
    private fun clearRetainedPresence() {
        val ids = runCatching { runBlocking { activeCoupleIds() } }.getOrElse {
            log.warn("Could not list couples to clear retained presence", it); emptyList()
        }
        for (cid in ids) publish("couple/$cid/presence", ByteArray(0), MqttQoS.AT_LEAST_ONCE, retain = true)
    }

    private fun publish(topic: String, payload: ByteArray, qos: MqttQoS, retain: Boolean) {
        val msg = MqttMessageBuilders.publish()
            .topicName(topic)
            .qos(qos)
            .retained(retain)
            .payload(Unpooled.wrappedBuffer(payload))
            .build()
        server.internalPublish(msg, INTERNAL_CLIENT_ID)
    }

    /** Server→client event push (contract "Feed" §MQTT push): QoS 1, not retained. */
    fun publishToCouple(coupleId: String, topic: String, payload: String) {
        if (!running) return
        publish(topic, payload.toByteArray(), MqttQoS.AT_LEAST_ONCE, retain = false)
    }

    private inner class Authenticator : IAuthenticator {
        override fun checkValid(clientId: String?, username: String?, password: ByteArray?): Boolean = runBlocking {
            val user = username.orEmpty()
            if (!access.clientIdAllowed(user, clientId)) {
                access.recordDenied(user, clientId, "client_id_not_bound_to_user")
                return@runBlocking false
            }
            access.authenticate(user, password?.toString(Charsets.UTF_8), clientId)
        }
    }

    private inner class Authorizer : IAuthorizatorPolicy {
        override fun canWrite(topic: Topic, user: String?, client: String?): Boolean = check(topic, user)
        override fun canRead(topic: Topic, user: String?, client: String?): Boolean = check(topic, user)
        private fun check(topic: Topic, user: String?): Boolean =
            runCatching { runBlocking { access.canAccess(user, topic.toString()) } }.getOrDefault(false)
    }

    private data class RetainedWill(val topic: String, val payload: ByteArray, val qos: MqttQoS)

    private inner class WillGuard : AbstractInterceptHandler() {
        private val wills = ConcurrentHashMap<String, RetainedWill>()

        override fun getID(): String = "couple-will-guard"

        override fun getInterceptedMessageTypes(): Array<Class<*>> = arrayOf(
            InterceptConnectMessage::class.java,
            InterceptDisconnectMessage::class.java,
            InterceptConnectionLostMessage::class.java,
        )

        override fun onSessionLoopError(error: Throwable?) {
            log.warn("MQTT session loop error", error)
        }

        override fun onConnect(msg: InterceptConnectMessage) {
            val clientId = msg.clientID
            wills.remove(clientId)
            if (!msg.isWillFlag) return
            val topic = msg.willTopic
            val allowed = runCatching { runBlocking { access.canAccess(msg.username, topic) } }.getOrDefault(false)
            if (!allowed) {
                log.warn("MQTT client {} (user {}) set a will on a forbidden topic; stripping it and disconnecting", clientId, msg.username)
                stripWill(clientId)
                runCatching { runBlocking { access.recordDenied(msg.username, clientId, "will_topic_denied") } }
                server.disconnectClient(clientId)
                return
            }
            if (msg.isWillRetain) {
                val qos = MqttQoS.valueOf(msg.willQos.toInt()).let { if (it == MqttQoS.AT_MOST_ONCE) MqttQoS.AT_LEAST_ONCE else it }
                wills[clientId] = RetainedWill(topic, msg.willMessage.copyOf(), qos)
            }
        }

        override fun onDisconnect(msg: InterceptDisconnectMessage) {
            wills.remove(msg.clientID) // clean DISCONNECT: no will
        }

        override fun onConnectionLost(msg: InterceptConnectionLostMessage) {
            val will = wills.remove(msg.clientID) ?: return
            // A quick reconnect may already be live (interceptor callbacks are asynchronous): never overwrite it.
            if (server.listConnectedClients().any { it.clientID == msg.clientID }) return
            runCatching { publish(will.topic, will.payload, will.qos, retain = true) }
                .onFailure { log.warn("Could not store retained will for {}", msg.clientID, it) }
        }

        /** Best effort: Moquette exposes no API to drop a will, so clear Session.will before the connection closes. */
        private fun stripWill(clientId: String) {
            try {
                val registry = Server::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(server)
                val pool = registry.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(registry) as Map<*, *>
                val session = pool[clientId] ?: return
                session.javaClass.getDeclaredField("will").apply { isAccessible = true }.set(session, null)
            } catch (e: Exception) {
                log.warn("Could not strip forbidden will of {} ({}); Moquette internals changed?", clientId, e.toString())
            }
        }
    }

    companion object {
        const val INTERNAL_CLIENT_ID = "couple-backend-internal"
    }
}
