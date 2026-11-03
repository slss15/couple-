package com.qimingnan.couple.chat

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.chat.ChatExportFormat
import com.qimingnan.couple.data.chat.ChatExportText
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.ChatMessage
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 聊天记录导出：文本格式规则、文件名、错误提示，以及演示后端的 GET chat/export。 */
class ChatExportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = 1_759_291_200_000L // 2025-10-01 12:00 +08:00

    private fun m(type: String, text: String = "", sender: String = "me", recalled: Long? = null, mediaId: String? = null) = ChatMessage(
        id = "01X", coupleId = "c", senderId = sender, type = type, text = text, replyTo = null,
        clientTs = 0, createdAt = at, recalledAt = recalled, mediaId = mediaId,
    )

    @After fun restore() {
        DemoBackend.paired = true
        DemoBackend.resetChat()
    }

    @Test fun `txt lines use 我 TA and placeholders`() {
        assertEquals("2025-10-01 12:00 我: 早安", ChatExportText.txtLine(m("text", "早安"), "me", zone))
        assertEquals("2025-10-01 12:00 TA: [图片]", ChatExportText.txtLine(m("image", sender = "ta"), "me", zone))
        assertEquals("2025-10-01 12:00 我: [表情]", ChatExportText.txtLine(m("sticker"), "me", zone))
        assertEquals("2025-10-01 12:00 TA: [已撤回]", ChatExportText.txtLine(m("text", sender = "ta", recalled = 1), "me", zone))
        assertEquals("2025-10-01 12:00 我: 一\n\t二", ChatExportText.txtLine(m("text", "一\r\n二"), "me", zone))
    }

    @Test fun `json item carries media urls`() {
        val img = ChatExportText.jsonItem(m("image", mediaId = "K1"), "me")
        assertEquals("/api/v1/media/K1", img["mediaUrl"]!!.jsonPrimitive.content)
        assertEquals("/api/v1/media/K1/thumb", img["thumbUrl"]!!.jsonPrimitive.content)
        assertTrue(img["mine"]!!.jsonPrimitive.boolean)
        val sticker = ChatExportText.jsonItem(m("sticker", sender = "ta", mediaId = "K2"), "me")
        assertEquals("/api/v1/media/K2", sticker["mediaUrl"]!!.jsonPrimitive.content)
        assertEquals("null", sticker["thumbUrl"].toString())
    }

    @Test fun `file name and error messages`() {
        assertEquals("couple-chat-20251001.txt", ChatExportText.fileName(ChatExportFormat.TXT, at, zone))
        assertEquals("couple-chat-20251001.json", ChatExportText.fileName(ChatExportFormat.JSON, at, zone))
        assertEquals("导出太频繁，请 42 秒后再试",
            ChatExportText.errorMessage(429, """{"code":"RATE_LIMITED","message":"x"}""", "42"))
        assertEquals("还没有配对，没有聊天记录可导出",
            ChatExportText.errorMessage(403, """{"code":"NOT_PAIRED","message":"x"}""", null))
    }

    @Test fun `demo backend exports oldest first and rate limits`() {
        val id = "01TESTEXPRT000000000000000"
        val sent = DemoBackend.respond("POST", "/api/v1/chat/messages", at, body = """{"id":"$id","text":"你好\n呀","clientTs":$at}""")
        assertEquals(200, sent.first)

        val txt = DemoBackend.export("txt", at, "Asia/Shanghai")
        assertEquals(200, txt.code)
        assertTrue(txt.contentType.startsWith("text/plain"))
        val lines = txt.body.trimEnd('\n').split("\n")
        assertTrue(lines.first().contains(" TA: ")) // 种子消息来自 TA，且最早
        val mine = lines.indexOf("2025-10-01 12:00 我: 你好")
        assertTrue(mine > 0)
        assertEquals("\t呀", lines[mine + 1])

        val limited = DemoBackend.export("json", at + 30_000, null)
        assertEquals(429, limited.code)
        assertEquals(30L, limited.retryAfter)

        val json = DemoBackend.export("json", at + 60_000, null)
        assertEquals(200, json.code)
        val msgs = CoupleJson.parseToJsonElement(json.body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
        val ids = msgs.map { it["id"]!!.jsonPrimitive.content }
        assertTrue(ids.indexOf(id) > 0) // 种子消息在前
        assertTrue(msgs[ids.indexOf(id)]["mine"]!!.jsonPrimitive.boolean)
        assertEquals(400, DemoBackend.export("csv", at + 200_000, null).code)

        DemoBackend.paired = false
        assertEquals(403, DemoBackend.export("txt", at + 300_000, null).code)
    }
}
