package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.chat.ChatPageView
import com.qimingnan.couple.backend.chat.ScheduleMath
import com.qimingnan.couple.backend.chat.ScheduleRuleDto
import com.qimingnan.couple.backend.chat.ScheduledListView
import com.qimingnan.couple.backend.chat.ScheduledMessageView
import com.qimingnan.couple.backend.chat.ScheduledService
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.ApiException
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 定时消息（contract "Chat → Scheduled messages"）：规则计算、CRUD 权限、tick 幂等 / 补发一次 / 解绑清理。 */
class ScheduledTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = sh) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // 2025-10-25 08:00 Asia/Shanghai（周六）
    private val clock = TestClock(ms(2025, 10, 25, 8, 0))

    @Test
    fun ruleMath() {
        val now = ms(2025, 10, 25, 8, 0)
        assertEquals(ms(2025, 10, 25, 9, 30), ScheduleMath.next(ScheduleRuleDto("DAILY", time = "09:30"), sh, now))
        assertEquals(ms(2025, 10, 26, 7, 0), ScheduleMath.next(ScheduleRuleDto("DAILY", time = "07:00"), sh, now))
        assertEquals(ms(2025, 10, 26, 8, 0), ScheduleMath.next(ScheduleRuleDto("DAILY", time = "08:00"), sh, now), "strictly after")
        // 周一 → 10-27；周六同一时刻已过 → 下周六
        assertEquals(ms(2025, 10, 27, 8, 0), ScheduleMath.next(ScheduleRuleDto("WEEKLY", time = "08:00", dayOfWeek = 1), sh, now))
        assertEquals(ms(2025, 11, 1, 8, 0), ScheduleMath.next(ScheduleRuleDto("WEEKLY", time = "08:00", dayOfWeek = 6), sh, now))
        // 2 月 29 日：平年落到 2 月 28 日，闰年回到 29 日
        val feb29 = ScheduleRuleDto("YEARLY", time = "00:00", month = 2, day = 29)
        assertEquals(ms(2026, 2, 28, 0, 0), ScheduleMath.next(feb29, sh, now))
        assertEquals(ms(2028, 2, 29, 0, 0), ScheduleMath.next(feb29, sh, ms(2027, 3, 1, 0, 0)))
        assertEquals(ms(2026, 5, 20, 13, 14), ScheduleMath.next(ScheduleRuleDto("YEARLY", time = "13:14", month = 5, day = 20), sh, now))
        assertNull(ScheduleMath.next(ScheduleRuleDto("ONCE", at = now - 1), sh, now))
        // 时区按规则自己的 tz：纽约 DST 缺口 02:30 顺延到 03:30
        val ny = ZoneId.of("America/New_York")
        assertEquals(ms(2026, 3, 8, 3, 30, ny), ScheduleMath.next(ScheduleRuleDto("DAILY", time = "02:30"), ny, ms(2026, 3, 8, 0, 0, ny)))
        // 校验
        assertFailsWith<ApiException> { ScheduleMath.validate(ScheduleRuleDto("DAILY", time = "24:00")) }
        assertFailsWith<ApiException> { ScheduleMath.validate(ScheduleRuleDto("WEEKLY", time = "08:00", dayOfWeek = 8)) }
        assertFailsWith<ApiException> { ScheduleMath.validate(ScheduleRuleDto("YEARLY", time = "08:00", month = 4, day = 31)) }
        assertFailsWith<ApiException> { ScheduleMath.validate(ScheduleRuleDto("HOURLY", time = "08:00")) }
        assertEquals("DAILY", ScheduleMath.validate(ScheduleRuleDto("daily", time = "08:00", dayOfWeek = 3)).kind)
        assertNull(ScheduleMath.validate(ScheduleRuleDto("daily", time = "08:00", dayOfWeek = 3)).dayOfWeek)
    }

    private fun scheduledTest(block: suspend (HttpClient, ScheduledService) -> Unit) = testApplication {
        var svc: ScheduledService? = null
        application {
            module(testConfig(clock = clock))
            svc = attributes[ScheduledServiceKey]
        }
        val client = createClient { install(ContentNegotiation) { json(AppJson) } }
        client.get("/api/v1/health") // 启动应用
        block(client, svc!!)
    }

    private suspend fun HttpClient.pairTwo(a: String = "alice", b: String = "bob"): Triple<TokenPair, TokenPair, String> {
        val alice = register(a)
        val bob = register(b)
        val code = postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        val cid = postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken).json()["id"]!!.jsonPrimitive.content
        return Triple(alice, bob, cid)
    }

    /** 时钟跳了几天后 access token 过期，重新登录拿新 token。 */
    private suspend fun HttpClient.login(username: String): String =
        postJson("/api/v1/auth/login", obj("username" to username, "password" to "password-123", "deviceId" to "dev-$username", "deviceName" to "Pixel"))
            .json()["accessToken"]!!.jsonPrimitive.content

    private suspend fun HttpClient.create(token: String, body: JsonObject) = postJson("/api/v1/chat/scheduled", body, token)

    private fun daily(time: String) = obj("kind" to "DAILY", "time" to time)

    private suspend fun view(r: HttpResponse): ScheduledMessageView {
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ScheduledMessageView.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.list(token: String): ScheduledListView =
        AppJson.decodeFromString(ScheduledListView.serializer(), get("/api/v1/chat/scheduled") { bearerAuth(token) }.bodyAsText())

    private suspend fun HttpClient.patchRule(token: String, id: String, body: JsonObject) = patch("/api/v1/chat/scheduled/$id") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body.toString())
    }

    private suspend fun HttpClient.messages(token: String): ChatPageView =
        AppJson.decodeFromString(ChatPageView.serializer(), get("/api/v1/chat/messages?limit=200") { bearerAuth(token) }.bodyAsText())

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private suspend fun HttpClient.upload(token: String): String = post("/api/v1/media") {
        bearerAuth(token)
        setBody(MultiPartFormDataContent(formData {
            append("file", png(), Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"a.png\"")
                append(HttpHeaders.ContentType, "image/png")
            })
        }))
    }.json()["storageKey"]!!.jsonPrimitive.content

    @Test
    fun crudIsSenderOnlyAndValidated() = scheduledTest { client, _ ->
        val (alice, bob, _) = client.pairTwo()
        val r = view(client.create(alice.accessToken, obj("text" to " 早安 ", "rule" to daily("09:00"))))
        assertEquals("早安", r.text)
        assertEquals("Asia/Shanghai", r.tz)
        assertEquals(ms(2025, 10, 25, 9, 0), r.nextFireAt)

        // 对方看不到、改不了、删不了
        assertTrue(client.list(bob.accessToken).items.isEmpty())
        assertEquals(1, client.list(alice.accessToken).items.size)
        assertEquals(404, client.patchRule(bob.accessToken, r.id, obj("paused" to true)).status.value)
        assertEquals(404, client.delete("/api/v1/chat/scheduled/${r.id}") { bearerAuth(bob.accessToken) }.status.value)

        // 校验
        assertEquals("SCHEDULE_IN_PAST", client.create(alice.accessToken, obj("text" to "x", "rule" to obj("kind" to "ONCE", "at" to clock.now() - 1))).errorCode())
        assertEquals("VALIDATION_ERROR", client.create(alice.accessToken, obj("text" to "  ", "rule" to daily("09:00"))).errorCode())
        assertEquals("VALIDATION_ERROR", client.create(alice.accessToken, obj("text" to "x", "rule" to daily("9:00"))).errorCode())
        assertEquals("VALIDATION_ERROR", client.create(alice.accessToken, obj("text" to "x", "rule" to daily("09:00"), "tz" to "Mars/Base")).errorCode())
        assertEquals("VALIDATION_ERROR", client.create(alice.accessToken, obj("type" to "image", "rule" to daily("09:00"))).errorCode())
        val solo = client.register("carol")
        assertEquals("NOT_PAIRED", client.create(solo.accessToken, obj("text" to "x", "rule" to daily("09:00"))).errorCode())

        // 编辑：改文字、改规则（重算 nextFireAt）、暂停 / 恢复
        val edited = view(client.patchRule(alice.accessToken, r.id, obj("text" to "早上好", "rule" to obj("kind" to "WEEKLY", "time" to "07:30", "dayOfWeek" to 1))))
        assertEquals("早上好", edited.text)
        assertEquals(ms(2025, 10, 27, 7, 30), edited.nextFireAt)
        assertTrue(view(client.patchRule(alice.accessToken, r.id, obj("paused" to true))).paused)
        val resumed = view(client.patchRule(alice.accessToken, r.id, obj("paused" to false)))
        assertEquals(false, resumed.paused)
        assertEquals(ms(2025, 10, 27, 7, 30), resumed.nextFireAt)

        assertEquals(204, client.delete("/api/v1/chat/scheduled/${r.id}") { bearerAuth(alice.accessToken) }.status.value)
        assertTrue(client.list(alice.accessToken).items.isEmpty())
    }

    @Test
    fun onceFiresExactlyOnceEvenWithConcurrentTicks() = scheduledTest { client, svc ->
        val (alice, bob, _) = client.pairTwo()
        val r = view(client.create(alice.accessToken, obj("text" to "生日快乐", "rule" to obj("kind" to "ONCE", "at" to clock.now() + 60_000))))
        assertEquals(0, svc.tick(), "not due yet")
        clock.advance(60_000)
        val sent = coroutineScope { (1..4).map { async { svc.tick() } }.awaitAll() }.sum()
        assertEquals(1, sent)
        assertEquals(0, svc.tick())

        val msgs = client.messages(bob.accessToken).messages
        assertEquals(1, msgs.size)
        assertEquals("生日快乐", msgs[0].text)
        assertEquals(alice.user.id, msgs[0].senderId)
        assertEquals(clock.now(), msgs[0].createdAt)

        val finished = client.list(alice.accessToken).items.single()
        assertNull(finished.nextFireAt)
        assertEquals(clock.now(), finished.lastFiredAt)
        // 已发送的单次规则不能直接“恢复”，但可以改时间复用
        assertEquals("SCHEDULE_FINISHED", client.patchRule(alice.accessToken, r.id, obj("paused" to false)).errorCode())
        val reused = view(client.patchRule(alice.accessToken, r.id, obj("rule" to obj("kind" to "ONCE", "at" to clock.now() + 1_000))))
        assertNotNull(reused.nextFireAt)
    }

    @Test
    fun missedRepeatsAfterDowntimeSendOnceAndPausedRulesWait() = scheduledTest { client, svc ->
        val (alice, bob, _) = client.pairTwo()
        view(client.create(alice.accessToken, obj("text" to "每天", "rule" to daily("09:00"))))
        val paused = view(client.create(alice.accessToken, obj("text" to "暂停的", "rule" to daily("09:00"))))
        view(client.patchRule(alice.accessToken, paused.id, obj("paused" to true)))

        // 停机 3 天：只补发一次，下一次排到“现在”之后
        clock.advance(3 * 86_400_000L)
        assertEquals(1, svc.tick())
        assertEquals(0, svc.tick())
        val aliceT = client.login("alice")
        val bobT = client.login("bob")
        val rule = client.list(aliceT).items.first { it.text == "每天" }
        assertEquals(ms(2025, 10, 28, 9, 0), rule.nextFireAt)
        assertEquals(listOf("每天"), client.messages(bobT).messages.map { it.text })

        // 恢复后从现在往后排，不补发暂停期间的
        val resumed = view(client.patchRule(aliceT, paused.id, obj("paused" to false)))
        assertEquals(ms(2025, 10, 28, 9, 0), resumed.nextFireAt)
        clock.advance(86_400_000L)
        assertEquals(2, svc.tick())
        assertEquals(3, client.messages(client.login("bob")).messages.size)
    }

    @Test
    fun repeatingImageCopiesMediaPerFireAndUnpairDeletesRules() = scheduledTest { client, svc ->
        val (alice, bob, _) = client.pairTwo()
        val key = client.upload(alice.accessToken)
        val r = view(client.create(alice.accessToken, obj("type" to "image", "mediaId" to key, "rule" to daily("09:00"))))
        assertEquals(key, r.mediaId)
        assertEquals(20, r.width)
        // 规则占用了这张图，普通消息不能再用
        val reuse = client.postJson("/api/v1/chat/messages", obj("id" to "01JSCHED0000000000000000AA", "type" to "image", "mediaId" to key), alice.accessToken)
        assertEquals("MEDIA_ALREADY_USED", reuse.errorCode())

        clock.advance(3_600_000L)
        assertEquals(1, svc.tick())
        clock.advance(86_400_000L)
        assertEquals(1, svc.tick())
        val aliceT = client.login("alice")
        val bobT = client.login("bob")
        val imgs = client.messages(bobT).messages
        assertEquals(2, imgs.size)
        assertTrue(imgs.all { it.type == "image" && it.mediaId != null && it.mediaId != key })
        assertEquals(2, imgs.map { it.mediaId }.toSet().size, "each fire owns its own copy")
        assertEquals(200, client.get("/api/v1/media/${imgs[0].mediaId}") { bearerAuth(bobT) }.status.value)

        // 解绑：该 couple 的规则全部删除，之后 tick 不再发送
        view(client.create(bobT, obj("text" to "晚安", "rule" to daily("22:00"))))
        val req = client.postJson("/api/v1/couple/unpair/request", token = aliceT)
        val requestId = req.json()["requestId"]!!.jsonPrimitive.content
        assertEquals(204, client.postJson("/api/v1/couple/unpair/confirm", obj("requestId" to requestId), bobT).status.value)
        assertTrue(client.list(aliceT).items.isEmpty())
        assertTrue(client.list(bobT).items.isEmpty())
        clock.advance(2 * 86_400_000L)
        assertEquals(0, svc.tick())
    }
}
