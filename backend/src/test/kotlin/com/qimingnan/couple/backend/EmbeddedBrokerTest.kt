package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.config.MqttBrokerMode
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end tests of MQTT_BROKER_MODE=embedded with a real Paho client over WebSocket. */
class EmbeddedBrokerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Received(val topic: String, val payload: String, val retained: Boolean)

    private class Client(val paho: MqttClient) {
        val inbox = LinkedBlockingQueue<Received>()
        fun next(timeoutMs: Long = 5_000): Received? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
        fun close() = runCatching { if (paho.isConnected) paho.disconnect(1_000); paho.close() }
    }

    private fun connect(
        port: Int,
        user: String,
        token: String,
        clientId: String = "a-$user-dev",
        clean: Boolean = true,
        will: Pair<String, String>? = null,
    ): Client {
        val c = MqttClient("ws://127.0.0.1:$port/mqtt", clientId, MemoryPersistence())
        val client = Client(c)
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                client.inbox.add(Received(topic, String(message.payload), message.isRetained))
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        c.connect(options(user, token, clean, will))
        return client
    }

    private fun options(user: String, token: String, clean: Boolean = true, will: Pair<String, String>? = null) =
        MqttConnectOptions().apply {
            userName = user
            password = token.toCharArray()
            isCleanSession = clean
            keepAliveInterval = 45
            connectionTimeout = 10
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            if (will != null) setWill(will.first, will.second.toByteArray(), 1, true)
        }

    private suspend fun HttpClient.pair(a: TokenPair, b: TokenPair): String {
        val code = postJson("/api/v1/pair/code", token = a.accessToken).json()["code"]!!.jsonPrimitive.content
        return postJson("/api/v1/pair/join", obj("code" to code), b.accessToken).json()["id"]!!.jsonPrimitive.content
    }

    private fun embedded(port: Int, dataDir: String? = null) =
        testConfig(brokerMode = MqttBrokerMode.EMBEDDED, mqttWsPort = port, mqttDataDir = dataDir, mqttHookSecret = "hook-secret")

    @Test
    fun authAclRetainedAndWill() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val a = http.register("alice")
            val b = http.register("bob")
            val cid = http.pair(a, b)
            val presence = "couple/$cid/presence"

            // Bad token / wrong user / client id of somebody else are all rejected at CONNECT.
            val bad = assertFailsWith<MqttException> { connect(port, a.user.id, "garbage") }
            assertTrue(bad.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                bad.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(), "reason ${bad.reasonCode}")
            assertFailsWith<MqttException> { connect(port, b.user.id, a.accessToken) }
            assertFailsWith<MqttException> { connect(port, a.user.id, a.accessToken, clientId = "a-${b.user.id}-dev") }

            // Good token connects; retained presence is delivered to the partner who subscribes later.
            val alice = connect(port, a.user.id, a.accessToken, will = presence to """{"userId":"${a.user.id}","online":false}""")
            alice.paho.publish(presence, """{"userId":"${a.user.id}","online":true}""".toByteArray(), 1, true)
            val bob = connect(port, b.user.id, b.accessToken)
            bob.paho.subscribe("couple/$cid/#", 1)
            val retained = assertNotNull(bob.next(), "retained presence not delivered")
            assertEquals(presence, retained.topic)
            // Note: Moquette 0.17 delivers stored retained messages at QoS1 without the RETAIN flag, so the flag is
            // not asserted; receiving it on a fresh subscription is what proves it was retained.
            assertTrue(retained.payload.contains("\"online\":true"))

            // Foreign couple topic: SUBACK failure (0x80) and publishes there never reach anybody.
            val granted = bob.paho.subscribeWithResponse("couple/OTHER/#", 1).grantedQos
            assertEquals(128, granted[0])
            assertFailsWith<MqttException> { bob.paho.subscribe("couple/+/presence", 1) }
            // QoS0: Moquette drops a denied QoS1 publish without PUBACK (a blocking client would wait forever).
            alice.paho.publish("couple/OTHER/presence", "x".toByteArray(), 0, false)
            // Server-push topics are read-only for clients: a forged chat event must never reach the partner.
            alice.paho.publish("couple/$cid/chat", "forged".toByteArray(), 0, false)
            alice.paho.publish(presence, "hello".toByteArray(), 1, false)
            assertEquals(presence, bob.next()!!.topic) // only the allowed presence arrives

            // Abrupt disconnect (no DISCONNECT packet): the will reaches the partner ...
            val raw = MqttAsyncClient("ws://127.0.0.1:$port/mqtt", "a-${a.user.id}-tablet", MemoryPersistence())
            raw.connect(options(a.user.id, a.accessToken, will = presence to """{"userId":"${a.user.id}","online":false,"via":"will"}"""))
                .waitForCompletion(10_000)
            raw.disconnectForcibly(0, 1_000, false)
            val will = assertNotNull(bob.next(), "LWT not delivered")
            assertEquals(presence, will.topic)
            assertTrue(will.payload.contains("\"via\":\"will\""))
            // ... and becomes the retained presence for anyone subscribing afterwards.
            val late = connect(port, b.user.id, b.accessToken, clientId = "a-${b.user.id}-late")
            late.paho.subscribe(presence, 1)
            var seen: Received? = null
            repeat(10) {
                val m = late.next(2_000) ?: return@repeat
                seen = m
                if (m.payload.contains("\"via\":\"will\"")) return@repeat
            }
            assertNotNull(seen)
            assertTrue(seen!!.payload.contains("\"via\":\"will\""), "retained will not stored: ${seen?.payload}")

            listOf(alice, bob, late).forEach { it.close() }
        }
    }

    @Test
    fun willOnForeignTopicIsNotDelivered() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val a = http.register("alice"); val b = http.register("bob")
            val c = http.register("carol"); val d = http.register("dave")
            http.pair(a, b)
            val other = http.pair(c, d)
            val dave = connect(port, d.user.id, d.accessToken)
            dave.paho.subscribe("couple/$other/#", 1)
            val raw = MqttAsyncClient("ws://127.0.0.1:$port/mqtt", "a-${a.user.id}-evil", MemoryPersistence())
            runCatching {
                raw.connect(options(a.user.id, a.accessToken, will = "couple/$other/presence" to "forged")).waitForCompletion(10_000)
                Thread.sleep(500) // let the will guard run
                raw.disconnectForcibly(0, 1_000, false)
            }
            assertNull(dave.next(2_000), "forged will reached a foreign couple")
            dave.close()
        }
    }

    @Test
    fun chatMessagesArePushedAfterCommit() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val a = http.register("alice"); val b = http.register("bob")
            val cid = http.pair(a, b)
            val bob = connect(port, b.user.id, b.accessToken)
            bob.paho.subscribeWithResponse("couple/$cid/chat", 1)

            val id = com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString()
            val sent = http.postJson(
                "/api/v1/chat/messages",
                obj("id" to id, "text" to "hello", "clientTs" to System.currentTimeMillis()),
                a.accessToken,
            )
            assertEquals(200, sent.status.value, sent.bodyAsText())

            val pushed = assertNotNull(bob.next(5_000), "chat event not pushed")
            assertEquals("couple/$cid/chat", pushed.topic)
            assertTrue(pushed.payload.contains("\"kind\":\"message\""), pushed.payload)
            assertTrue(pushed.payload.contains("\"text\":\"hello\""), pushed.payload)
            bob.close()
        }
    }

    @Test
    fun persistentSessionQueuesQos1WhileOffline() {
        val port = freePort()
        val dir = java.nio.file.Files.createTempDirectory("mqtt-store").toString()
        appTest(embedded(port, dataDir = dir)) { http ->
            startApplication()
            val a = http.register("alice"); val b = http.register("bob")
            val cid = http.pair(a, b)
            val topic = "couple/$cid/presence"

            val bob = connect(port, b.user.id, b.accessToken, clean = false)
            bob.paho.subscribe(topic, 1)
            bob.paho.disconnect(1_000); bob.paho.close()

            val alice = connect(port, a.user.id, a.accessToken)
            repeat(3) { i -> alice.paho.publish(topic, "m$i".toByteArray(), 1, false) }

            val back = connect(port, b.user.id, b.accessToken, clean = false)
            val got = (0 until 3).mapNotNull { back.next()?.payload }
            assertEquals(listOf("m0", "m1", "m2"), got)
            listOf(alice, back).forEach { it.close() }
        }
    }

    @Test
    fun internalHooksDisabledAndUpdaterStatusLoopbackOnly() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            val a = http.register("alice")
            val auth = http.post("/api/v1/internal/mqtt/auth") {
                contentType(ContentType.Application.Json)
                header("X-Internal-Token", "hook-secret")
                setBody(obj("username" to a.user.id, "password" to a.accessToken))
            }
            assertEquals(404, auth.status.value)
            suspend fun status(cf: Boolean) = http.post("/api/v1/internal/updater/status") {
                contentType(ContentType.Application.Json)
                header("X-Internal-Token", "hook-secret")
                if (cf) header("CF-Connecting-IP", "1.2.3.4")
                setBody(obj("current" to obj("server" to "0.2.0")))
            }
            assertEquals(204, status(cf = false).status.value) // test engine peer is loopback
            assertEquals(404, status(cf = true).status.value)
        }
    }
}
