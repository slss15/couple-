package com.qimingnan.couple.demo

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.Couple
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
}
