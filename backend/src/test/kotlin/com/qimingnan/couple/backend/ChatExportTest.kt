package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.chat.ChatExportService
import com.qimingnan.couple.backend.chat.ChatMessageView
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GET /chat/export (contract "Chat → Export"): order, both formats, media URLs, members only, 1/min. */
class ChatExportTest {

    private val clock = TestClock(1_759_291_200_000L) // 2025-10-01 12:00 Asia/Shanghai

    private var seq = 0
    private fun ulid(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var v = System.nanoTime() + (++seq) * 7919L
        val sb = StringBuilder("01")
        while (sb.length < 26) { sb.append(alphabet[((v % 32).toInt() + 32) % 32]); v /= 32 }
        return sb.toString()
    }

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private suspend fun HttpClient.pairTwo(): Triple<TokenPair, TokenPair, String> {
        val alice = register("alice")
        val bob = register("bob")
        val code = postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        val cid = postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken).json()["id"]!!.jsonPrimitive.content
        return Triple(alice, bob, cid)
    }

    private suspend fun HttpClient.text(token: String, text: String): String {
        val id = ulid()
        val r = postJson("/api/v1/chat/messages", obj("id" to id, "text" to text, "clientTs" to clock.now()), token)
        assertEquals(200, r.status.value, r.bodyAsText())
        clock.advance(60_000)
        return id
    }

    private suspend fun HttpClient.export(token: String, query: String): HttpResponse =
        get("/api/v1/chat/export$query") { bearerAuth(token) }

    @Test
    fun exportsBothFormatsOldestFirstWithMediaAndRateLimit() = appTest(testConfig(clock = clock)) { client ->
        val (alice, bob, _) = client.pairTwo()
        client.text(alice.accessToken, "早安")
        client.text(bob.accessToken, "第一行\n第二行")
        val key = client.post("/api/v1/media") {
            bearerAuth(alice.accessToken)
            setBody(MultiPartFormDataContent(formData {
                append("file", png(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"a.png\"")
                    append(HttpHeaders.ContentType, "image/png")
                })
            }))
        }.json()["storageKey"]!!.jsonPrimitive.content
        val imgId = ulid()
        assertEquals(200, client.postJson("/api/v1/chat/messages", obj("id" to imgId, "type" to "image", "mediaId" to key, "clientTs" to 1), alice.accessToken).status.value)
        clock.advance(60_000)
        val recalled = client.text(bob.accessToken, "说错了")
        clock.advance(-60_000) // recall within the window
        assertEquals(200, client.postJson("/api/v1/chat/messages/$recalled/recall", null, bob.accessToken).status.value)
        clock.advance(60_000)

        // txt from Bob's side: 我 = Bob, TA = Alice, oldest first, placeholders, continuation line indented.
        val txt = client.export(bob.accessToken, "?format=txt")
        assertEquals(200, txt.status.value, txt.bodyAsText())
        assertTrue(txt.headers[HttpHeaders.ContentType]!!.startsWith("text/plain"))
        assertTrue(txt.headers[HttpHeaders.ContentDisposition]!!.contains("couple-chat-"))
        assertEquals(
            listOf(
                "2025-10-01 12:00 TA: 早安",
                "2025-10-01 12:01 我: 第一行",
                "\t第二行",
                "2025-10-01 12:02 TA: [图片]",
                "2025-10-01 12:03 我: [已撤回]",
            ),
            txt.bodyAsText().trimEnd('\n').split("\n"),
        )

        // Second export within a minute (any format) → 429 with Retry-After; the partner has their own slot.
        val limited = client.export(bob.accessToken, "?format=json")
        assertEquals(429, limited.status.value)
        assertEquals("RATE_LIMITED", limited.errorCode())
        assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)

        val json = client.export(alice.accessToken, "?format=json")
        assertEquals(200, json.status.value, json.bodyAsText())
        val root = AppJson.parseToJsonElement(json.bodyAsText()).jsonObject
        val msgs = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("早安", "第一行\n第二行", "", ""), msgs.map { it["text"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, false, true, false), msgs.map { it["mine"]!!.jsonPrimitive.boolean })
        val img = msgs[2]
        assertEquals(imgId, img["id"]!!.jsonPrimitive.content)
        assertEquals("image", img["type"]!!.jsonPrimitive.content)
        assertEquals("/api/v1/media/$key", img["mediaUrl"]!!.jsonPrimitive.content)
        assertEquals("/api/v1/media/$key/thumb", img["thumbUrl"]!!.jsonPrimitive.content)
        // The exported media URL is readable by the partner.
        assertEquals(200, client.get("/api/v1/media/$key") { bearerAuth(bob.accessToken) }.status.value)

        // After a minute Bob may export again.
        clock.advance(60_000)
        assertEquals(200, client.export(bob.accessToken, "?format=json").status.value)
    }

    @Test
    fun validationAndMembersOnly() = appTest(testConfig(clock = clock)) { client ->
        val solo = client.register("solo")
        assertEquals("NOT_PAIRED", client.export(solo.accessToken, "?format=txt").errorCode())
        val (alice, _, _) = client.pairTwo()
        val bad = client.export(alice.accessToken, "?format=csv")
        assertEquals(400, bad.status.value)
        assertEquals("VALIDATION_ERROR", bad.errorCode())
        assertEquals(400, client.export(alice.accessToken, "?format=txt&tz=Mars/Base").status.value)
        // Rejected requests do not use up the slot; an empty history is a valid export.
        val empty = client.export(alice.accessToken, "?format=json")
        assertEquals(200, empty.status.value)
        assertEquals(0, AppJson.parseToJsonElement(empty.bodyAsText()).jsonObject["messages"]!!.jsonArray.size)
        assertEquals(401, client.get("/api/v1/chat/export?format=json").status.value)
    }

    @Test
    fun txtLineFormatting() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun m(type: String, text: String = "", sender: String = "A", recalled: Long? = null) = ChatMessageView(
            id = "X", coupleId = "C", senderId = sender, type = type, text = text, replyTo = null, clientTs = 0,
            createdAt = 1_759_291_200_000L, recalledAt = recalled,
        )
        assertEquals("2025-10-01 12:00 我: hi", ChatExportService.txtLine(m("text", "hi"), "A", zone))
        assertEquals("2025-10-01 12:00 TA: [表情]", ChatExportService.txtLine(m("sticker", sender = "B"), "A", zone))
        assertEquals("2025-10-01 12:00 我: [图片]", ChatExportService.txtLine(m("image"), "A", zone))
        assertEquals("2025-10-01 12:00 我: [已撤回]", ChatExportService.txtLine(m("text", recalled = 1), "A", zone))
        assertEquals("2025-10-01 04:00 我: a\n\tb", ChatExportService.txtLine(m("text", "a\r\nb"), "A", ZoneId.of("UTC")))
    }
}
