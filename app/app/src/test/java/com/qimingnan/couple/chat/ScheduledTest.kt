package com.qimingnan.couple.chat

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.ChatPage
import com.qimingnan.couple.data.net.CreateScheduledRequest
import com.qimingnan.couple.data.net.ScheduleCalc
import com.qimingnan.couple.data.net.ScheduleRule
import com.qimingnan.couple.data.net.ScheduledList
import com.qimingnan.couple.data.net.ScheduledMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduledTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun fmt(v: Long?) = v?.let { ScheduleCalc.format(it, zone) }

    // 远在未来，演示后端的真实时钟 ticker 永远不会碰到这些规则
    private val now = ms(2031, 3, 10, 12, 0)

    @After fun restore() {
        DemoBackend.paired = true
        DemoBackend.resetChat()
    }

    @Test fun `daily next is later today or tomorrow`() {
        val rule = ScheduleRule(ScheduleRule.DAILY, time = "08:00")
        assertEquals("2031-03-11 08:00", fmt(ScheduleCalc.next(rule, zone, now)))
        assertEquals("2031-03-10 20:30", fmt(ScheduleCalc.next(ScheduleRule(ScheduleRule.DAILY, time = "20:30"), zone, now)))
        // 严格晚于：正好在触发时刻时取下一天
        assertEquals("2031-03-11 12:00", fmt(ScheduleCalc.next(ScheduleRule(ScheduleRule.DAILY, time = "12:00"), zone, now)))
    }

    @Test fun `weekly next lands on the right weekday`() {
        // 2031-03-10 是周一
        val wed = ScheduleRule(ScheduleRule.WEEKLY, time = "09:00", dayOfWeek = 3)
        assertEquals("2031-03-12 09:00", fmt(ScheduleCalc.next(wed, zone, now)))
        val mondayMorning = ScheduleRule(ScheduleRule.WEEKLY, time = "09:00", dayOfWeek = 1)
        assertEquals("2031-03-17 09:00", fmt(ScheduleCalc.next(mondayMorning, zone, now)))
        assertEquals("每周三 09:00", ScheduleCalc.describe(wed, zone))
    }

    @Test fun `yearly next and Feb 29 falls back to Feb 28`() {
        val rule = ScheduleRule(ScheduleRule.YEARLY, time = "00:00", month = 5, day = 20)
        assertEquals("2031-05-20 00:00", fmt(ScheduleCalc.next(rule, zone, now)))
        assertEquals("2032-05-20 00:00", fmt(ScheduleCalc.next(rule, zone, ms(2031, 5, 20, 0, 0))))
        val leap = ScheduleRule(ScheduleRule.YEARLY, time = "10:00", month = 2, day = 29)
        assertEquals("2032-02-29 10:00", fmt(ScheduleCalc.next(leap, zone, now)))
        assertEquals("2033-02-28 10:00", fmt(ScheduleCalc.next(leap, zone, ms(2032, 3, 1, 0, 0))))
    }

    @Test fun `once and validation`() {
        assertNull(ScheduleCalc.next(ScheduleRule(ScheduleRule.ONCE, at = now - 1), zone, now))
        assertEquals(now + 60_000, ScheduleCalc.next(ScheduleRule(ScheduleRule.ONCE, at = now + 60_000), zone, now))
        assertNotNull(ScheduleCalc.problem(ScheduleRule(ScheduleRule.DAILY, time = "24:00")))
        assertNotNull(ScheduleCalc.problem(ScheduleRule(ScheduleRule.YEARLY, time = "08:00", month = 2, day = 30)))
        assertNull(ScheduleCalc.problem(ScheduleRule(ScheduleRule.YEARLY, time = "08:00", month = 2, day = 29)))
        val pick = ScheduleRule.fromPick(ScheduleRule.WEEKLY, ZonedDateTime.of(2031, 3, 13, 7, 5, 0, 0, zone))
        assertEquals(ScheduleRule(ScheduleRule.WEEKLY, time = "07:05", dayOfWeek = 4), pick)
    }

    private fun create(rule: ScheduleRule, text: String = "早安", at: Long = now): Pair<Int, String> =
        DemoBackend.respond(
            "POST", "/api/v1/chat/scheduled", at,
            body = CoupleJson.encodeToString(CreateScheduledRequest.serializer(), CreateScheduledRequest(text = text, rule = rule, tz = "Asia/Shanghai")),
        )

    private fun list(): List<ScheduledMessage> =
        CoupleJson.decodeFromString(ScheduledList.serializer(), DemoBackend.respond("GET", "/api/v1/chat/scheduled", now).second).items

    private fun chatTexts(): List<String> =
        CoupleJson.decodeFromString(ChatPage.serializer(), DemoBackend.respond("GET", "/api/v1/chat/messages", now).second).messages.map { it.text }

    @Test fun `demo create list tick`() {
        val (c1, b1) = create(ScheduleRule(ScheduleRule.ONCE, at = now + 60_000), "单次", now)
        assertEquals(200, c1)
        val once = CoupleJson.decodeFromString(ScheduledMessage.serializer(), b1)
        assertEquals(now + 60_000, once.nextFireAt)
        val (c2, b2) = create(ScheduleRule(ScheduleRule.DAILY, time = "12:01"), "每天", now + 1)
        assertEquals(200, c2)
        val daily = CoupleJson.decodeFromString(ScheduledMessage.serializer(), b2)
        assertEquals(listOf(daily.id, once.id), list().map { it.id }) // newest first

        assertEquals(0, DemoBackend.tickScheduled(now + 30_000))
        assertEquals(2, DemoBackend.tickScheduled(now + 60_000))
        val texts = chatTexts()
        assertTrue(texts.containsAll(listOf("单次", "每天")))
        val after = list().associateBy { it.id }
        assertNull(after.getValue(once.id).nextFireAt)
        assertEquals("2031-03-11 12:01", fmt(after.getValue(daily.id).nextFireAt))
        assertEquals(0, DemoBackend.tickScheduled(now + 120_000))

        // 已发送的单次规则不能直接恢复（要改时间才能复用）
        val patch = DemoBackend.respond("PATCH", "/api/v1/chat/scheduled/${once.id}", now, body = """{"paused":false}""")
        assertEquals(409, patch.first)
        assertTrue(patch.second.contains("SCHEDULE_FINISHED"))
    }

    @Test fun `demo past once rejected, pause skips, delete removes`() {
        val past = create(ScheduleRule(ScheduleRule.ONCE, at = now - 1))
        assertEquals(400, past.first)
        assertTrue(past.second.contains("SCHEDULE_IN_PAST"))

        val d = CoupleJson.decodeFromString(ScheduledMessage.serializer(), create(ScheduleRule(ScheduleRule.DAILY, time = "12:01")).second)
        val paused = DemoBackend.respond("PATCH", "/api/v1/chat/scheduled/${d.id}", now, body = """{"paused":true}""")
        assertEquals(200, paused.first)
        assertTrue(CoupleJson.decodeFromString(ScheduledMessage.serializer(), paused.second).paused)
        assertEquals(0, DemoBackend.tickScheduled(now + 120_000))
        assertFalse(chatTexts().contains("早安"))

        assertEquals(204, DemoBackend.respond("DELETE", "/api/v1/chat/scheduled/${d.id}", now).first)
        assertTrue(list().isEmpty())
        assertEquals(404, DemoBackend.respond("DELETE", "/api/v1/chat/scheduled/${d.id}", now).first)
    }
}
