package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.chat.ChatMessageView
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.config.MqttBrokerMode
import com.qimingnan.couple.backend.notify.ChatReminderService
import com.qimingnan.couple.backend.notify.ChatReminderSettings
import com.qimingnan.couple.backend.notify.PresenceTracker
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WxPusher 消息提醒（contract "Chat → WxPusher reminders"）。 */
class ChatReminderTest {

    private val clock = TestClock(1_761_350_400_000L)
    private val sent: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private var seq = 0

    private fun ulid(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var v = 1_700_000_000_000L + (++seq) * 104_729L
        val sb = StringBuilder("01")
        while (sb.length < 26) { sb.append(alphabet[(v % 32).toInt()]); v /= 32 }
        return sb.toString()
    }

    private fun embedded(port: Int) = testConfig(clock = clock, brokerMode = MqttBrokerMode.EMBEDDED, mqttWsPort = port)
        .copy(wxPusherSend = { _, body -> sent += AppJson.parseToJsonElement(body).jsonObject; true }, accessTokenTtlMs = 86_400_000L)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Setup(val admin: String, val alice: TokenPair, val bob: TokenPair)

    private suspend fun HttpClient.setup(appToken: String? = "wx-app-token-123456", minutes: Int? = null): Setup {
        val admin = register("rem-admin").accessToken
        if (appToken != null) {
            val wx = if (minutes == null) obj("appToken" to appToken) else obj("appToken" to appToken, "chatOfflineMinutes" to minutes)
            val r = put("/api/v1/admin/config") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(obj("notify" to obj("wxpusher" to wx))) }
            assertEquals(200, r.status.value, r.bodyAsText())
        }
        val alice = register("alice")
        val bob = register("bob")
        val code = postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        assertEquals(200, postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken).status.value)
        return Setup(admin, alice, bob)
    }

    private suspend fun HttpClient.putNotify(token: String, uid: String?, preview: Boolean): HttpResponse = put("/api/v1/me/notify") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(obj("wxpusherUid" to uid, "preview" to preview))
    }

    private suspend fun HttpClient.say(token: String, text: String) {
        val r = postJson("/api/v1/chat/messages", obj("id" to ulid(), "text" to text, "clientTs" to clock.now()), token)
        assertEquals(200, r.status.value, r.bodyAsText())
    }

    /** Reminders run off the request path: wait for [n] sends (or give up and let the assert fail). */
    private fun awaitSent(n: Int, timeoutMs: Long = 5_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (sent.size < n && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(150) // and make sure nothing extra follows
    }

    private fun connect(port: Int, user: TokenPair): MqttClient {
        val c = MqttClient("ws://127.0.0.1:$port/mqtt", "a-${user.user.id}-dev", MemoryPersistence())
        c.connect(MqttConnectOptions().apply {
            userName = user.user.id; password = user.accessToken.toCharArray(); isCleanSession = true
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        })
        return c
    }

    @Test
    fun offlineRecipientGetsOneReminderPerTenMinutesAndPreviewWhenOptedIn() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val s = http.setup()
            val uid = "UID_bob123"
            assertEquals(200, http.putNotify(s.bob.accessToken, uid, preview = false).status.value)
            clock.advance(6 * 60_000L) // nobody has been online since the server started 6 min ago

            http.say(s.alice.accessToken, "在吗")
            awaitSent(1)
            assertEquals(1, sent.size)
            assertEquals("TA 发来一条消息", sent[0]["content"]!!.jsonPrimitive.content)
            assertEquals(listOf(uid), sent[0]["uids"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("wx-app-token-123456", sent[0]["appToken"]!!.jsonPrimitive.content)

            // Rate limit: one per recipient per 10 min.
            clock.advance(9 * 60_000L)
            http.say(s.alice.accessToken, "人呢")
            awaitSent(2, 400)
            assertEquals(1, sent.size)

            // Alice (no UID) never gets anything for Bob's messages.
            http.say(s.bob.accessToken, "我在")
            awaitSent(2, 400)
            assertEquals(1, sent.size)

            // Preview opt-in: the actual text.
            clock.advance(61_000L)
            assertEquals(200, http.putNotify(s.bob.accessToken, uid, preview = true).status.value)
            http.say(s.alice.accessToken, "今晚  一起吃饭？")
            awaitSent(2)
            assertEquals(2, sent.size)
            assertEquals("TA：今晚 一起吃饭？", sent[1]["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun onlineOrRecentlyOfflineRecipientIsNotReminded() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val s = http.setup(minutes = 3)
            assertEquals(200, http.putNotify(s.bob.accessToken, "UID_bob", preview = false).status.value)
            clock.advance(10 * 60_000L)

            val bob = connect(port, s.bob)
            Thread.sleep(300) // connect interceptor is asynchronous
            http.say(s.alice.accessToken, "online?")
            awaitSent(1, 500)
            assertEquals(0, sent.size, "online recipient must not be reminded")

            bob.disconnect(1_000); bob.close()
            Thread.sleep(300)
            clock.advance(2 * 60_000L) // offline 2 min < 3 min (admin chatOfflineMinutes)
            http.say(s.alice.accessToken, "just left")
            awaitSent(1, 500)
            assertEquals(0, sent.size)

            clock.advance(2 * 60_000L) // offline 4 min ≥ 3
            http.say(s.alice.accessToken, "now?")
            awaitSent(1)
            assertEquals(1, sent.size)
        }
    }

    @Test
    fun meNotifyValidatesAndNoAppTokenMeansNoPush() {
        val port = freePort()
        appTest(embedded(port)) { http ->
            startApplication()
            val s = http.setup(appToken = null)
            val initial = http.get("/api/v1/me/notify") { bearerAuth(s.bob.accessToken) }.json()
            assertTrue(initial["wxpusherUid"] == null || initial["wxpusherUid"].toString() == "null")
            assertEquals("false", initial["preview"]!!.jsonPrimitive.content)

            val bad = http.putNotify(s.bob.accessToken, "not-a-uid", preview = true)
            assertEquals(400, bad.status.value)
            assertEquals("VALIDATION_ERROR", bad.errorCode())

            val ok = http.putNotify(s.bob.accessToken, "  UID_ok_1  ", preview = true).json()
            assertEquals("UID_ok_1", ok["wxpusherUid"]!!.jsonPrimitive.content)
            val back = http.get("/api/v1/me/notify") { bearerAuth(s.bob.accessToken) }.json()
            assertEquals("UID_ok_1", back["wxpusherUid"]!!.jsonPrimitive.content)
            assertEquals("true", back["preview"]!!.jsonPrimitive.content)

            clock.advance(30 * 60_000L)
            http.say(s.alice.accessToken, "no app token configured")
            awaitSent(1, 500)
            assertEquals(0, sent.size)

            // Clearing the UID with "".
            val cleared = http.putNotify(s.bob.accessToken, "", preview = false).json()
            assertEquals("null", cleared["wxpusherUid"].toString())
            assertEquals(401, http.get("/api/v1/me/notify").status.value)
        }
    }

    @Test
    fun externalBrokerHasNoPresenceSoNoReminders() = appTest(
        testConfig(clock = clock).copy(wxPusherSend = { _, body -> sent += AppJson.parseToJsonElement(body).jsonObject; true }, accessTokenTtlMs = 86_400_000L),
    ) { http ->
        val s = http.setup()
        assertEquals(200, http.putNotify(s.bob.accessToken, "UID_bob", preview = false).status.value)
        clock.advance(60 * 60_000L)
        http.say(s.alice.accessToken, "hello")
        awaitSent(1, 500)
        assertEquals(0, sent.size)
    }

    @Test
    fun presenceTrackerAndSettings() {
        val t = TestClock(1_000_000L)
        val p = PresenceTracker(t::now)
        assertEquals(1_000_000L, p.offlineSince("u1")) // never seen: offline since start
        t.advance(10)
        p.connected("u1", "phone"); p.connected("u1", "tablet")
        assertNull(p.offlineSince("u1"))
        t.advance(10)
        p.disconnected("u1", "phone")
        assertNull(p.offlineSince("u1")) // tablet still connected
        t.advance(10)
        p.disconnected("u1", "tablet")
        assertEquals(1_000_030L, p.offlineSince("u1"))
        assertFalse(p.isOnline("u1"))

        assertEquals(ChatReminderSettings("", 5), ChatReminderSettings.fromConfig(null))
        assertEquals(ChatReminderSettings("t", 30), ChatReminderSettings.fromConfig(obj("wxpusher" to obj("appToken" to "t", "chatOfflineMinutes" to 30))))
        assertEquals(1, ChatReminderSettings.fromConfig(obj("wxpusher" to obj("chatOfflineMinutes" to 0))).offlineMinutes)
        assertEquals(1440, ChatReminderSettings.fromConfig(obj("wxpusher" to obj("chatOfflineMinutes" to 99999))).offlineMinutes)

        fun v(type: String, text: String) = ChatMessageView("m", "c", "s", type, text, null, 0, 0, null)
        assertEquals("[图片]", ChatReminderService.previewText(v("image", "")))
        assertEquals("[表情]", ChatReminderService.previewText(v("sticker", "")))
        assertEquals("a".repeat(100) + "…", ChatReminderService.previewText(v("text", "a".repeat(150))))
    }
}
