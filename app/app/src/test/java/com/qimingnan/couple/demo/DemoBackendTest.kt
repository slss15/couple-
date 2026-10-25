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
}
