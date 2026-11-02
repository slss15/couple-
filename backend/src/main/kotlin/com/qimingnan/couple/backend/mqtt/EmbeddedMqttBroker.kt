package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.config.AppConfig
import io.moquette.broker.ISessionsRepository
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
 * - ACL: [MqttAccessService.canRead] for SUBSCRIBE and [MqttAccessService.canWrite] for PUBLISH
 *   (clients publish only their own presence/typing; feed/chat/events are server-pushed), same rules as the EMQX ACL hook.
 * - Persistent sessions (cleanSession=false) keep subscriptions and queue QoS1/2 messages while the client is away;
 *   with [AppConfig.mqttDataDir] set they survive restarts. Sessions idle longer than
 *   [AppConfig.mqttSessionExpiry] are purged.
 * - Last Will (Moquette 0.18.1): Moquette checks the will topic only when it fires, and then as the anonymous
 *   "will_publisher" (no user, no client), so it cannot apply our per-user ACL. [Authorizer] therefore denies every
 *   broker-fired will, and [WillGuard] is the only will path: at CONNECT it checks the will topic with
 *   [MqttAccessService.canWrite] for the connecting user (a forbidden will is stripped and the client disconnected),
 *   and on an abrupt connection loss it publishes the approved will itself (keeping its retain flag, so a retained
 *   "offline" presence is stored for partners that connect later). A will Moquette might still hold for a rejected
 *   client can never be published, even if stripping it fails.
 */
class EmbeddedMqttBroker(
    private val config: AppConfig,
    private val access: MqttAccessService,
    /** Active couple ids, used to clear stale retained presence after a restart with a persistent store. */
    private val activeCoupleIds: suspend () -> List<String>,
    /** Connection presence for chat reminders (contract "Chat → WxPusher reminders"); null = not tracked. */
    private val presence: com.qimingnan.couple.backend.notify.PresenceTracker? = null,
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
    /** Server push (QoS 1, not retained) to any topic; bypasses the client ACL. */
    fun publishServer(topic: String, payload: String) {
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
        override fun canWrite(topic: Topic, user: String?, client: String?): Boolean =
            // Broker-fired wills carry no identity; WillGuard publishes the approved ones (see class doc).
            if (client == MOQUETTE_WILL_PUBLISHER) false else runCatching { runBlocking { access.canWrite(user, topic.toString()) } }.getOrDefault(false)

        override fun canRead(topic: Topic, user: String?, client: String?): Boolean =
            runCatching { runBlocking { access.canRead(user, topic.toString()) } }.getOrDefault(false)
    }

    private data class ApprovedWill(val topic: String, val payload: ByteArray, val qos: MqttQoS, val retain: Boolean)

    private inner class WillGuard : AbstractInterceptHandler() {
        private val wills = ConcurrentHashMap<String, ApprovedWill>()

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
            msg.username?.takeIf { clientId != INTERNAL_CLIENT_ID }?.let { presence?.connected(it, clientId) }
            if (!msg.isWillFlag) return
            val topic = msg.willTopic
            val allowed = runCatching { runBlocking { access.canWrite(msg.username, topic) } }.getOrDefault(false)
            if (!allowed) {
                log.warn("MQTT client {} (user {}) set a will on a forbidden topic; stripping it and disconnecting", clientId, msg.username)
                stripWill(clientId)
                runCatching { runBlocking { access.recordDenied(msg.username, clientId, "will_topic_denied") } }
                server.disconnectClient(clientId)
                return
            }
            val qos = MqttQoS.valueOf(msg.willQos.toInt()).let {
                // A retained presence must be stored reliably; QoS 0 is only kept for non-retained wills.
                if (msg.isWillRetain && it == MqttQoS.AT_MOST_ONCE) MqttQoS.AT_LEAST_ONCE else it
            }
            wills[clientId] = ApprovedWill(topic, msg.willMessage.copyOf(), qos, msg.isWillRetain)
        }

        override fun onDisconnect(msg: InterceptDisconnectMessage) {
            wills.remove(msg.clientID) // clean DISCONNECT: no will
            markGone(msg.clientID, msg.username)
        }

        /** A quick reconnect with the same client id may already be live (callbacks are asynchronous). */
        private fun markGone(clientId: String, username: String?) {
            val p = presence ?: return
            if (username == null || clientId == INTERNAL_CLIENT_ID) return
            if (runCatching { server.listConnectedClients().any { it.clientID == clientId } }.getOrDefault(false)) return
            p.disconnected(username, clientId)
        }

        override fun onConnectionLost(msg: InterceptConnectionLostMessage) {
            markGone(msg.clientID, msg.username)
            val will = wills.remove(msg.clientID) ?: return
            // A quick reconnect may already be live (interceptor callbacks are asynchronous): never overwrite it.
            if (server.listConnectedClients().any { it.clientID == msg.clientID }) return
            runCatching { publish(will.topic, will.payload, will.qos, retain = will.retain) }
                .onFailure { log.warn("Could not publish will for {}", msg.clientID, it) }
        }

        /**
         * Hygiene only (the Authorizer already blocks broker-fired wills): Moquette exposes no API to drop a will,
         * so replace the session's SessionData with withoutWill() before the connection closes.
         */
        private fun stripWill(clientId: String) {
            try {
                val registry = Server::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(server)
                val pool = registry.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(registry) as Map<*, *>
                val session = pool[clientId] ?: return // io.moquette.broker.Session is package-private
                val data = session.javaClass.getDeclaredField("data").apply { isAccessible = true }.get(session)
                    as ISessionsRepository.SessionData
                if (!data.hasWill()) return
                session.javaClass.getDeclaredMethod("updateSessionData", ISessionsRepository.SessionData::class.java)
                    .apply { isAccessible = true }.invoke(session, data.withoutWill())
            } catch (e: Exception) {
                log.warn("Could not strip forbidden will of {} ({}); Moquette internals changed?", clientId, e.toString())
            }
        }
    }

    companion object {
        const val INTERNAL_CLIENT_ID = "couple-backend-internal"
        /** Client id Moquette 0.18 passes to the authorizer when it fires a Last Will (PostOffice.WILL_PUBLISHER). */
        const val MOQUETTE_WILL_PUBLISHER = "will_publisher"
    }
}
