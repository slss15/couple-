package com.qimingnan.couple.settings

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.NotifySettings
import com.qimingnan.couple.data.net.NotifyUid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置 → 消息提醒：UID 校验 + 演示后端 GET / PUT /me/notify（contract "Chat → WxPusher reminders"）。 */
class NotifySettingsTest {
    private val now = 1_761_350_400_000L

    @After fun reset() = DemoBackend.resetNotify()

    private fun get(): NotifySettings {
        val (code, body) = DemoBackend.respond("GET", "/api/v1/me/notify", now)
        assertEquals(200, code)
        return CoupleJson.decodeFromString(NotifySettings.serializer(), body)
    }

    private fun put(s: NotifySettings): Pair<Int, String> =
        DemoBackend.respond("PUT", "/api/v1/me/notify", now, body = CoupleJson.encodeToString(NotifySettings.serializer(), s))

    @Test fun `uid validation matches the backend`() {
        assertTrue(NotifyUid.isValid(""))
        assertTrue(NotifyUid.isValid("   "))
        assertTrue(NotifyUid.isValid(" UID_abc123 "))
        assertFalse(NotifyUid.isValid("abc"))
        assertFalse(NotifyUid.isValid("UID_"))
        assertFalse(NotifyUid.isValid("UID_有中文"))
        assertNull(NotifyUid.normalize("  "))
        assertEquals("UID_x", NotifyUid.normalize(" UID_x "))
    }

    @Test fun `demo backend round trips notify settings`() {
        assertEquals(NotifySettings(null, false), get())
        val (code, body) = put(NotifySettings("UID_demo1", true))
        assertEquals(200, code)
        assertEquals(NotifySettings("UID_demo1", true), CoupleJson.decodeFromString(NotifySettings.serializer(), body))
        assertEquals(NotifySettings("UID_demo1", true), get())

        val bad = put(NotifySettings("nope", false))
        assertEquals(400, bad.first)
        assertTrue(bad.second.contains("VALIDATION_ERROR"))
        assertEquals(NotifySettings("UID_demo1", true), get()) // unchanged

        assertEquals(200, put(NotifySettings("", false)).first)
        assertEquals(NotifySettings(null, false), get())
    }
}
