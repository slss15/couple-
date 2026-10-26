package com.qimingnan.couple.demo

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.CommentView
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.FeedPage
import com.qimingnan.couple.data.net.ImportantDay
import com.qimingnan.couple.data.net.PostView
import com.qimingnan.couple.data.net.TaSummary
import com.qimingnan.couple.data.net.TokenPair
import com.qimingnan.couple.data.update.UpdateLogic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoBackendTest {
    private val now = 1_761_350_400_000L

    @After fun restore() { DemoBackend.paired = true }

    @Test fun `login returns demo user paired by default`() {
        val (code, body) = DemoBackend.respond("POST", "/api/v1/auth/login", now)
        assertEquals(200, code)
        val pair = CoupleJson.decodeFromString(TokenPair.serializer(), body)
        assertEquals("demo", pair.user.username)
        assertEquals(DemoBackend.COUPLE_ID, pair.user.coupleId)
        assertTrue(pair.accessExpiresAt > now)
    }

    @Test fun `unpair then join round trip`() {
        assertEquals(200, DemoBackend.respond("GET", "/api/v1/couple", now).first)
        assertEquals(204, DemoBackend.respond("POST", "/api/v1/couple/unpair/confirm", now).first)
        val (code, body) = DemoBackend.respond("GET", "/api/v1/couple", now)
        assertEquals(404, code)
        assertTrue(body.contains("NOT_PAIRED"))
        val join = DemoBackend.respond("POST", "/api/v1/pair/join", now)
        assertEquals(200, join.first)
        assertEquals(2, CoupleJson.decodeFromString(Couple.serializer(), join.second).members.size)
    }

    @Test fun `update check says nothing new and unknown paths 404`() {
        val (code, body) = DemoBackend.respond("GET", "/api/v1/app/update", now)
        assertEquals(200, code)
        assertNull(UpdateLogic.parseResponse(body).latest)
        assertEquals(404, DemoBackend.respond("GET", "/api/v1/nope", now).first)
    }

    @Test fun `demo host detection`() {
        assertTrue(DemoBackend.isDemo(DemoBackend.BASE_URL))
        assertTrue(DemoBackend.isDemoUrl(DemoBackend.MQTT_URL))
        assertFalse(DemoBackend.isDemo("https://couple.example.com"))
        assertFalse(DemoBackend.isDemo(null))
    }

    @Test fun `debug OTA update json gets a same-origin proxied download url`() {
        val src = """{"latest":{"versionName":"0.1.0-debug.7","versionCode":1007,"sha256":"${"a".repeat(64)}","size":10,""" +
            """"signingCertSha256":"${"b".repeat(64)}","downloadUrl":"https://github.com/slss15/couple-/releases/download/app-debug-edge/couple-debug-1007.apk"},"force":false}"""
        val info = UpdateLogic.parseResponse(DemoBackend.rewriteUpdate(src)).latest!!
        assertEquals(1007, info.versionCode)
        assertTrue(info.downloadUrl.startsWith("/api/v1/app/demo-apk?u=https%3A%2F%2Fgithub.com%2Fslss15%2Fcouple-%2F"))
    }

    @Test fun `foreign download urls are dropped`() {
        val src = """{"latest":{"versionName":"x","versionCode":2,"sha256":"","size":1,"signingCertSha256":"","downloadUrl":"https://evil.example/a.apk"}}"""
        assertNull(UpdateLogic.parseResponse(DemoBackend.rewriteUpdate(src)).latest)
    }

    @Test fun `feed lists seeded posts and create is idempotent`() {
        val list = DemoBackend.respond("GET", "/api/v1/feed/posts", now)
        assertEquals(200, list.first)
        val page = CoupleJson.decodeFromString(FeedPage.serializer(), list.second)
        assertTrue(page.posts.isNotEmpty())
        assertTrue(page.posts.all { it.authorId == DemoBackend.partner.id })

        val id = "01TESTPOST0000000000000000"
        DemoBackend.demoBody = """{"id":"$id","text":"第一条"}"""
        val create = DemoBackend.respond("POST", "/api/v1/feed/posts", now)
        assertEquals(200, create.first)
        val created = CoupleJson.decodeFromString(PostView.serializer(), create.second)
        assertEquals(id, created.id)
        assertEquals(0, created.likes.count)

        DemoBackend.demoBody = """{"id":"$id","text":"第一条"}"""
        val again = DemoBackend.respond("POST", "/api/v1/feed/posts", now)
        assertEquals(create.second, again.second) // 同 id 幂等：返回同一帖子

        assertEquals(204, DemoBackend.respond("DELETE", "/api/v1/feed/posts/$id", now).first)
        assertEquals(404, DemoBackend.respond("DELETE", "/api/v1/feed/posts/$id", now).first)
    }

    @Test fun `feed like and comments round trip`() {
        val id = "01TESTLIKE00000000000000000"
        DemoBackend.demoBody = """{"id":"$id","text":"hi"}"""
        assertEquals(200, DemoBackend.respond("POST", "/api/v1/feed/posts", now).first)

        assertEquals("""{"count":1}""", DemoBackend.respond("POST", "/api/v1/feed/posts/$id/like", now).second)
        assertEquals("""{"count":0}""", DemoBackend.respond("DELETE", "/api/v1/feed/posts/$id/like", now).second)

        val cid = "01TESTCMT00000000000000000"
        DemoBackend.demoBody = """{"id":"$cid","text":"好看"}"""
        val c = DemoBackend.respond("POST", "/api/v1/feed/posts/$id/comments", now)
        assertEquals(200, c.first)
        assertEquals("好看", CoupleJson.decodeFromString(CommentView.serializer(), c.second).text)
        // 幂等：同评论 id 返回同一条
        DemoBackend.demoBody = """{"id":"$cid","text":"好看"}"""
        assertEquals(c.second, DemoBackend.respond("POST", "/api/v1/feed/posts/$id/comments", now).second)
        // 空文本 400
        DemoBackend.demoBody = """{"id":"${cid}x","text":"  "}"""
        assertEquals(400, DemoBackend.respond("POST", "/api/v1/feed/posts/$id/comments", now).first)
        assertEquals(204, DemoBackend.respond("DELETE", "/api/v1/feed/comments/$cid", now).first)
        assertEquals(204, DemoBackend.respond("DELETE", "/api/v1/feed/posts/$id", now).first)
    }

    @Test fun `post requires text or media`() {
        DemoBackend.demoBody = """{"id":"01TESTEMPTY000000000000000","text":"  "}"""
        assertEquals(400, DemoBackend.respond("POST", "/api/v1/feed/posts", now).first)
    }

    @Test fun `ta summary and days round trip`() {
        val s = DemoBackend.respond("GET", "/api/v1/ta/summary", now)
        assertEquals(200, s.first)
        val summary = CoupleJson.decodeFromString(TaSummary.serializer(), s.second)
        assertEquals("TA（演示）", summary.partner.displayName)
        assertEquals("06-20", summary.partner.birthday)
        assertTrue(summary.days.isNotEmpty())

        DemoBackend.demoBody = """{"name":"第一次见面","month":3,"day":14}"""
        val add = DemoBackend.respond("POST", "/api/v1/ta/days", now)
        assertEquals(200, add.first)
        val day = CoupleJson.decodeFromString(ImportantDay.serializer(), add.second)
        assertEquals(3, day.month)

        DemoBackend.demoBody = """{"bio":"新的签名"}"""
        assertEquals(204, DemoBackend.respond("PUT", "/api/v1/ta/me", now).first)
        val s2 = CoupleJson.decodeFromString(TaSummary.serializer(), DemoBackend.respond("GET", "/api/v1/ta/summary", now).second)
        assertEquals("新的签名", s2.me.bio)

        assertEquals(204, DemoBackend.respond("DELETE", "/api/v1/ta/days/${day.id}", now).first)
        // 无效月份 400
        DemoBackend.demoBody = """{"name":"x","month":13,"day":1}"""
        assertEquals(400, DemoBackend.respond("POST", "/api/v1/ta/days", now).first)
    }
}
