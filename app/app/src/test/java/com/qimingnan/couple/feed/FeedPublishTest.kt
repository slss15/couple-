package com.qimingnan.couple.feed

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.core.ImageSize
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.feed.FeedRepository
import com.qimingnan.couple.data.feed.FeedRepository.SendOutcome
import com.qimingnan.couple.data.net.FeedPage
import com.qimingnan.couple.data.net.MediaView
import com.qimingnan.couple.data.net.PostView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 动态发布链路：演示后端 multipart 上传 → POST 帖子 → 列表 / 媒体可读；以及 outbox 的失败分类。 */
class FeedPublishTest {
    private val now = 1_761_350_400_000L

    @After fun reset() { DemoBackend.resetFeed() }

    /** 与 FeedRepository.sendPending 完全相同的 multipart 构造，经拦截器同样的方式转成 ResponseBody。 */
    private fun upload(bytes: ByteArray, field: String = "file"): Pair<Int, String> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addPart(MultipartBody.Part.createFormData(field, "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType())))
            .build()
        val buf = okio.Buffer(); body.writeTo(buf)
        return DemoBackend.handleMediaUpload(buf.readByteArray().toResponseBody(body.contentType()), now)
    }

    @Test fun `image header sizes`() {
        assertEquals(ImageSize.Info("image/png", 640, 480), ImageSize.read(TestImages.png(640, 480)))
        assertEquals(ImageSize.Info("image/jpeg", 1024, 768), ImageSize.read(TestImages.jpeg(1024, 768)))
        assertNull(ImageSize.read("hello world, not an image".toByteArray()))
    }

    @Test fun `demo multipart upload then post with media shows in list and media is readable`() {
        val (code, json) = upload(TestImages.jpeg(800, 600))
        assertEquals(json, 200, code)
        val media = CoupleJson.decodeFromString(MediaView.serializer(), json)
        assertEquals(800, media.width); assertEquals(600, media.height); assertEquals("image/jpeg", media.mimeType)
        assertNotNull(DemoBackend.media(media.storageKey))

        val id = "01TESTPUBLISH0000000000000"
        val created = DemoBackend.respond("POST", "/api/v1/feed/posts", now, body = """{"id":"$id","text":"带图","mediaKeys":["${media.storageKey}"]}""")
        assertEquals(created.second, 200, created.first)
        val post = CoupleJson.decodeFromString(PostView.serializer(), created.second)
        assertEquals(listOf(media.storageKey), post.media.map { it.storageKey })

        val page = CoupleJson.decodeFromString(FeedPage.serializer(), DemoBackend.respond("GET", "/api/v1/feed/posts", now + 1).second)
        assertEquals(id, page.posts.first { it.id == id }.id)
        assertEquals(1, page.posts.first { it.id == id }.media.size)
    }

    @Test fun `demo upload rejects non images and missing file field and unknown keys`() {
        assertEquals(400, upload("not an image".toByteArray()).first)
        assertEquals(400, upload(TestImages.png(10, 10), field = "other").first)
        val r = DemoBackend.respond("POST", "/api/v1/feed/posts", now, body = """{"id":"01TESTBADKEY00000000000000","text":"x","mediaKeys":["nope"]}""")
        assertEquals(400, r.first)
    }

    @Test fun `explicit request body wins over the shared demoBody under concurrency`() {
        // 回归：以前所有 POST 共用一个全局 body，并发的已读/打字请求会覆盖帖子 body → 400 → 发送失败
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(40)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(40) { i ->
            pool.execute {
                val r = if (i % 2 == 0) {
                    DemoBackend.respond("POST", "/api/v1/feed/posts", now, body = """{"id":"01TESTCONC${i.toString().padStart(16, '0')}","text":"p$i"}""")
                } else {
                    DemoBackend.respond("POST", "/api/v1/chat/read", now, body = """{"upToId":"x"}""")
                }
                if (i % 2 == 0) codes += r.first
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(20, codes.size)
        assertTrue(codes.toString(), codes.all { it == 200 })
    }

    @Test fun `send errors are classified so 4xx fails once and 5xx or io retries`() {
        fun http(code: Int) = retrofit2.HttpException(retrofit2.Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))
        assertEquals(SendOutcome.FAILED, FeedRepository.classifySendError(http(400)))
        assertEquals(SendOutcome.FAILED, FeedRepository.classifySendError(http(409)))
        assertEquals(SendOutcome.RETRY, FeedRepository.classifySendError(http(429)))
        assertEquals(SendOutcome.RETRY, FeedRepository.classifySendError(http(503)))
        assertEquals(SendOutcome.RETRY, FeedRepository.classifySendError(java.io.IOException("offline")))
        assertEquals(SendOutcome.FAILED, FeedRepository.classifySendError(kotlinx.serialization.SerializationException("bad")))
    }

    @Test fun `outbox persists compressed images and skips unreadable ones`() {
        val dir = java.nio.file.Files.createTempDirectory("outbox").toFile()
        val files = com.qimingnan.couple.data.feed.OutboxFiles.persistTo(dir, "P1", listOf("a", "broken", "c")) {
            if (it == "broken") null else TestImages.jpeg(10, 10)
        }
        assertEquals(listOf("P1-0.jpg", "P1-2.jpg"), files.map { it.name })
        assertTrue(files.all { ImageSize.read(it.readBytes()) != null })
    }
}
