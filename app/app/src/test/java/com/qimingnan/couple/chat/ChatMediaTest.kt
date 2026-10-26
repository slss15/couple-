package com.qimingnan.couple.chat

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.db.ChatMessageEntity
import com.qimingnan.couple.data.db.chatPreview
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.ChatEvent
import com.qimingnan.couple.data.net.ChatMessage
import com.qimingnan.couple.data.net.SendMessageRequest
import com.qimingnan.couple.feed.TestImages
import com.qimingnan.couple.ui.chat.bubbleSize
import com.qimingnan.couple.ui.chat.mediaModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聊天图片 / 表情包：演示后端、模型序列化、气泡尺寸与地址选择。 */
class ChatMediaTest {
    private val now = 1_761_350_400_000L

    @After fun reset() { DemoBackend.resetChat(); DemoBackend.resetFeed() }

    private fun uploadDemo(bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(MultipartBody.Part.createFormData("file", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))).build()
        val buf = okio.Buffer(); body.writeTo(buf)
        val (code, json) = DemoBackend.handleMediaUpload(buf.readByteArray().toResponseBody(body.contentType()), now)
        assertEquals(json, 200, code)
        return CoupleJson.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonObject)["storageKey"].toString().trim('"') }
    }

    private fun send(req: SendMessageRequest) =
        DemoBackend.respond("POST", "/api/v1/chat/messages", now, body = CoupleJson.encodeToString(SendMessageRequest.serializer(), req))

    @Test fun `demo image message carries media and recall clears it`() {
        val key = uploadDemo(TestImages.png(300, 150))
        val id = "01HCHATMAGE000000000000000"
        val (code, json) = send(SendMessageRequest(id = id, text = "", clientTs = now, type = "image", mediaId = key, width = 300, height = 150))
        assertEquals(json, 200, code)
        val m = CoupleJson.decodeFromString(ChatMessage.serializer(), json)
        assertEquals("image", m.type); assertEquals(key, m.mediaId); assertEquals(300, m.width); assertEquals(150, m.height)

        assertEquals(400, send(SendMessageRequest(id = "01HCHATMAGE000000000000001", text = "", clientTs = now, type = "image")).first)
        assertEquals(400, send(SendMessageRequest(id = "01HCHATMAGE000000000000002", text = "", clientTs = now, type = "image", mediaId = "nope")).first)

        val recalled = DemoBackend.respond("POST", "/api/v1/chat/messages/$id/recall", now + 1_000)
        assertNull(CoupleJson.decodeFromString(ChatMessage.serializer(), recalled.second).mediaId)
    }

    @Test fun `demo sticker needs the interceptor-downloaded media`() {
        // 拦截器下载失败（离线）时 body 里没有 mediaId → 502，App 侧显示发送失败可重试
        val noMedia = send(SendMessageRequest(id = "01HSTCKER00000000000000000", text = "", clientTs = now, type = "sticker", stickerUrl = "https://x.test/a.gif"))
        assertEquals(502, noMedia.first)
        val m = DemoBackend.storeMediaBytes(TestImages.png(64, 64), now)!!
        assertNull(DemoBackend.storeMediaBytes("<html>".toByteArray(), now))
        val ok = send(SendMessageRequest(id = "01HSTCKER00000000000000001", text = "", clientTs = now, type = "sticker", stickerUrl = "https://x.test/a.gif", mediaId = m.key))
        assertEquals(ok.second, 200, ok.first)
        assertEquals("sticker", CoupleJson.decodeFromString(ChatMessage.serializer(), ok.second).type)
    }

    @Test fun `oiapi parsing and built-in samples`() {
        val list = DemoBackend.parseOiapi("""{"code":1,"data":[{"url":"https://a.test/1.gif","width":240,"height":92,"type":"gif"},{"url":"ftp://bad"},{"x":1}]}""")
        assertEquals(1, list.size); assertEquals(92, list[0].height)
        assertTrue(DemoBackend.parseOiapi("not json").isEmpty())
        assertTrue(DemoBackend.SAMPLE_STICKERS.size >= 3 && DemoBackend.SAMPLE_STICKERS.all { it.url.startsWith("https://") })
    }

    @Test fun `request omits media fields for text and event round trip keeps media`() {
        val text = CoupleJson.encodeToString(SendMessageRequest.serializer(), SendMessageRequest("01A", "hi", null, 1))
        assertFalse(text.contains("mediaId")); assertFalse(text.contains("stickerUrl"))
        val payload = """{"kind":"message","message":{"id":"01B","senderId":"u","type":"sticker","text":"","clientTs":1,"createdAt":2,"mediaId":"K","width":10,"height":20}}"""
        val e = ChatEvent.parse(payload) as ChatEvent.Message
        val row = e.message.toEntity()
        assertEquals("sticker", row.type); assertEquals("K", row.mediaId); assertEquals(20, row.height)
        assertEquals("[表情]", chatPreview(row.type, row.text))
        assertEquals("[图片]", chatPreview("image", ""))
        assertEquals("hi", chatPreview("text", "hi"))
    }

    @Test fun `bubble size keeps aspect ratio within bounds`() {
        assertEquals(200 to 100, bubbleSize(400, 200, sticker = false))
        assertEquals(100 to 200, bubbleSize(200, 400, sticker = false))
        assertEquals(200 to 48, bubbleSize(4000, 100, sticker = false)) // 极端宽图有最小高度
        assertEquals(120 to 120, bubbleSize(null, null, sticker = true))
        assertEquals(120 to 46.coerceAtLeast(48), bubbleSize(240, 92, sticker = true))
    }

    @Test fun `bubble picks server media then local file then sticker url`() {
        val base = ChatMessageEntity("1", "me", "", null, 0, null, 0, null, "PENDING", type = "image", localUri = "file:///x.jpg")
        val url = { k: String, thumb: Boolean -> "https://s/media/$k" + if (thumb) "/thumb" else "" }
        assertEquals("file:///x.jpg", mediaModel(base, true, url))
        assertEquals("https://s/media/K/thumb", mediaModel(base.copy(mediaId = "K"), true, url))
        assertEquals("https://s/media/K", mediaModel(base.copy(mediaId = "K"), false, url))
        val sticker = base.copy(type = "sticker", localUri = null, stickerUrl = "https://a.test/1.gif")
        assertEquals("https://a.test/1.gif", mediaModel(sticker, true, url))
        assertEquals("https://s/media/S", mediaModel(sticker.copy(mediaId = "S"), true, url)) // 表情包始终用原图（GIF 动画）
    }
}
