package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.chat.ChatMessageView
import com.qimingnan.couple.backend.chat.StickerHttp
import com.qimingnan.couple.backend.chat.StickerService
import com.qimingnan.couple.backend.chat.StickerView
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chat images + stickers (contract "Chat": type image | sticker, GET /chat/stickers/search). */
class ChatMediaTest {
    private val clock = TestClock(1_761_350_400_000L)

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private var seq = 0
    private fun ulid(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var v = System.nanoTime() + (++seq) * 7919L
        val sb = StringBuilder("01")
        while (sb.length < 26) { sb.append(alphabet[((v % 32).toInt() + 32) % 32]); v /= 32 }
        return sb.toString()
    }

    /** Fake upstream: search providers + a sticker CDN; records every URL requested. */
    private class FakeNet(val sticker: ByteArray) : StickerHttp {
        val calls = CopyOnWriteArrayList<String>()
        var oiapiDown = false
        override fun get(url: String, maxBytes: Int): StickerHttp.Resp {
            calls += url
            fun json(s: String) = StickerHttp.Resp(200, mapOf("content-type" to "application/json"), s.toByteArray())
            return when {
                url.startsWith("https://oiapi.net/") -> if (oiapiDown) StickerHttp.Resp(500, emptyMap(), ByteArray(0)) else json(
                    """{"code":1,"data":[{"url":"https://img.stickers.test/a.png","width":24,"height":24,"type":"png"},
                       {"url":"https://img.stickers.test/redirect","width":24,"height":24},
                       {"url":"https://img.stickers.test/big.png"},{"url":"https://img.stickers.test/html"},
                       {"url":"https://evil.internal.test/x.png"},{"url":"javascript:alert(1)"}]}""",
                )
                url.startsWith("https://cn.apihz.cn/") -> json("""{"code":200,"res":["https://res.apihz.test/1.jpg","https://res.apihz.test/2.jpg"]}""")
                url == "https://img.stickers.test/a.png" -> StickerHttp.Resp(200, mapOf("content-type" to "image/png"), sticker)
                url == "https://img.stickers.test/redirect" -> StickerHttp.Resp(302, mapOf("location" to "/a.png"), ByteArray(0))
                url == "https://img.stickers.test/big.png" -> StickerHttp.Resp(200, mapOf("content-type" to "image/png"), ByteArray(maxBytes + 1))
                url == "https://img.stickers.test/html" -> StickerHttp.Resp(200, mapOf("content-type" to "text/html"), "<html>".toByteArray())
                else -> StickerHttp.Resp(404, emptyMap(), ByteArray(0))
            }
        }
    }

    private val resolver: (String) -> List<InetAddress> = { host ->
        if (host == "evil.internal.test") listOf(InetAddress.getByName("10.0.0.5")) else listOf(InetAddress.getByName("93.184.216.34"))
    }

    private suspend fun HttpClient.pairTwo(): Pair<TokenPair, TokenPair> {
        val alice = register("alice")
        val bob = register("bob")
        val code = postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken)
        return alice to bob
    }

    private suspend fun HttpClient.upload(token: String, bytes: ByteArray): String = post("/api/v1/media") {
        bearerAuth(token)
        setBody(MultiPartFormDataContent(formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"a.png\"")
                append(HttpHeaders.ContentType, "image/png")
            })
        }))
    }.json()["storageKey"]!!.jsonPrimitive.content

    private suspend fun msg(r: HttpResponse): ChatMessageView {
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ChatMessageView.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.search(token: String, q: String): List<StickerView> {
        val r = get("/api/v1/chat/stickers/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&page=1") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ListSerializer(StickerView.serializer()), r.bodyAsText())
    }

    @Test
    fun imageMessageCarriesMediaAndIsOneShot() = appTest(testConfig(clock)) { client ->
        val (alice, bob) = client.pairTwo()
        val key = client.upload(alice.accessToken, png(40, 30))
        val id = ulid()
        val m = msg(client.postJson("/api/v1/chat/messages", obj("id" to id, "type" to "image", "mediaId" to key, "width" to 999, "height" to 1, "clientTs" to 1), alice.accessToken))
        assertEquals("image", m.type)
        assertEquals(key, m.mediaId)
        assertEquals(40, m.width); assertEquals(30, m.height) // server dimensions win
        assertEquals("", m.text)

        // partner can read the media; idempotent replay; media can't be reused by another message or post
        assertEquals(200, client.get("/api/v1/media/$key") { bearerAuth(bob.accessToken) }.status.value)
        assertEquals(m, msg(client.postJson("/api/v1/chat/messages", obj("id" to id, "type" to "image", "mediaId" to key), alice.accessToken)))
        val reuse = client.postJson("/api/v1/chat/messages", obj("id" to ulid(), "type" to "image", "mediaId" to key), alice.accessToken)
        assertEquals(409, reuse.status.value)
        val post = client.postJson("/api/v1/feed/posts", obj("id" to ulid(), "text" to "x", "mediaKeys" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(key)))), alice.accessToken)
        assertEquals(409, post.status.value)

        // validation
        assertEquals(400, client.postJson("/api/v1/chat/messages", obj("id" to ulid(), "type" to "image"), alice.accessToken).status.value)
        assertEquals(400, client.postJson("/api/v1/chat/messages", obj("id" to ulid(), "type" to "image", "mediaId" to "NOPE"), alice.accessToken).status.value)
        assertEquals(400, client.postJson("/api/v1/chat/messages", obj("id" to ulid(), "type" to "voice", "text" to "x"), alice.accessToken).status.value)

        // recall drops the media
        val recalled = msg(client.postJson("/api/v1/chat/messages/$id/recall", null, alice.accessToken))
        assertNull(recalled.mediaId)
        assertEquals(404, client.get("/api/v1/media/$key") { bearerAuth(bob.accessToken) }.status.value)
    }

    @Test
    fun stickerSearchProxiesAndFallsBack() {
        val net = FakeNet(png(24, 24))
        appTest(testConfig(clock).copy(stickerHttp = net, hostResolver = resolver)) { client ->
            val admin = client.register("admin")
            val (alice, _) = client.pairTwo()
            val r = client.search(alice.accessToken, "开心")
            assertEquals(24, r.first().width)
            assertTrue(r.none { it.url.startsWith("javascript") }, "non-web urls are dropped")
            assertTrue(net.calls.first().contains("keyword=%E5%BC%80%E5%BF%83"))

            // primary down and no fallback creds → empty list
            net.oiapiDown = true
            assertEquals(emptyList(), client.search(alice.accessToken, "哈"))

            // admin sets the apihz creds (write-only secret, masked on read) → fallback used
            val put = client.put("/api/v1/admin/config") {
                bearerAuth(admin.accessToken); contentType(ContentType.Application.Json)
                setBody(obj("stickers" to obj("apihz" to obj("id" to "10001", "apiKey" to "super-secret-key-1234"))))
            }
            assertEquals(200, put.status.value, put.bodyAsText())
            val view = client.get("/api/v1/admin/config") { bearerAuth(admin.accessToken) }.bodyAsText()
            assertFalse(view.contains("super-secret-key-1234"))
            assertEquals("****1234", AppJson.parseToJsonElement(view).jsonObject["stickers"]!!.jsonObject["apihz"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
            val fb = client.search(alice.accessToken, "哈")
            assertEquals(listOf("https://res.apihz.test/1.jpg", "https://res.apihz.test/2.jpg"), fb.map { it.url })
            assertTrue(net.calls.last().contains("key=super-secret-key-1234") && net.calls.last().contains("words=%E5%93%88"))

            assertEquals(400, client.get("/api/v1/chat/stickers/search?q=") { bearerAuth(alice.accessToken) }.status.value)
            val solo = client.register("solo")
            assertEquals(403, client.get("/api/v1/chat/stickers/search?q=x") { bearerAuth(solo.accessToken) }.status.value)
        }
    }

    @Test
    fun stickerMessageDownloadsUnderGuardRails() {
        val sticker = png(24, 24)
        val net = FakeNet(sticker)
        appTest(testConfig(clock).copy(stickerHttp = net, hostResolver = resolver)) { client ->
            val (alice, bob) = client.pairTwo()
            suspend fun send(url: String) = client.postJson("/api/v1/chat/messages", obj("id" to ulid(), "type" to "sticker", "stickerUrl" to url), alice.accessToken)

            // not yet seen in any search result → rejected without any download
            assertEquals("STICKER_REJECTED", send("https://img.stickers.test/a.png").errorCode())
            assertTrue(net.calls.none { it.startsWith("https://img.") })

            client.search(alice.accessToken, "开心")
            val m = msg(send("https://img.stickers.test/a.png"))
            assertEquals("sticker", m.type)
            assertEquals(24, m.width)
            val bytes = client.get("/api/v1/media/${m.mediaId}") { bearerAuth(bob.accessToken) }.readRawBytes()
            assertEquals(24, ImageIO.read(bytes.inputStream()).width)

            assertEquals(200, send("https://img.stickers.test/redirect").status.value, "relative redirect on the same host is followed")
            assertEquals("STICKER_REJECTED", send("https://img.stickers.test/big.png").errorCode())
            assertEquals("STICKER_REJECTED", send("https://img.stickers.test/html").errorCode())
            assertEquals("STICKER_REJECTED", send("https://evil.internal.test/x.png").errorCode(), "private address")
            assertEquals("STICKER_REJECTED", send("https://other.test/a.png").errorCode())
            assertEquals(400, send("file:///etc/passwd").status.value)
        }
    }

    @Test
    fun privateAddressAndRedirectLimits() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "::ffff:10.0.0.1")
            .forEach { assertTrue(StickerService.isPrivate(InetAddress.getByName(it)), it) }
        listOf("93.184.216.34", "2606:2800:220:1::1").forEach { assertFalse(StickerService.isPrivate(InetAddress.getByName(it)), it) }

        // endless redirect chain stops after 3 hops
        var hops = 0
        val loop = StickerHttp { url, _ ->
            when {
                url.startsWith("https://oiapi.net/") -> StickerHttp.Resp(200, emptyMap(), """{"data":[{"url":"https://r.loop.test/0"}]}""".toByteArray())
                else -> { hops++; StickerHttp.Resp(302, mapOf("location" to "/${hops}"), ByteArray(0)) }
            }
        }
        val svc = StickerService(loop, { listOf(InetAddress.getByName("93.184.216.34")) }) { null }
        kotlinx.coroutines.runBlocking { svc.search("x", 1) }
        val e = runCatching { svc.fetch("https://r.loop.test/0") }.exceptionOrNull() as com.qimingnan.couple.backend.common.ApiException
        assertEquals("STICKER_REJECTED", e.code)
        assertEquals(4, hops) // the original request + 3 redirects
    }
}
