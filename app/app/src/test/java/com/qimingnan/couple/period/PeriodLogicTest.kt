package com.qimingnan.couple.period

import com.qimingnan.couple.data.period.PeriodLog
import com.qimingnan.couple.data.period.PeriodLogic
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 经期预测数学（与 LunarCalendarTest 同风格：固定日期，不依赖当前时钟）。 */
class PeriodLogicTest {
    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test fun `no history falls back to defaults`() {
        val s = PeriodLogic.stats(emptyList(), d("2026-11-06"))
        assertEquals(PeriodLogic.DEFAULT_CYCLE, s.cycleDays)
        assertEquals(0, s.samples)
        assertFalse(s.inPeriod)
        assertNull(s.lastStart)
        assertNull(s.nextPredicted)
        assertNull(s.daysUntilNext)
    }

    @Test fun `in period with open ended log caps at default period`() {
        val logs = listOf(PeriodLog("1", "2026-11-04", null))
        val s = PeriodLogic.stats(logs, d("2026-11-06"))
        assertTrue(s.inPeriod)
        assertEquals(3, s.dayOfPeriod)
        assertNull(s.daysUntilNext)
        // 封顶：第 7 天起不再视为经期中
        val s2 = PeriodLogic.stats(logs, d("2026-11-12"))
        assertFalse(s2.inPeriod)
    }

    @Test fun `closed log counts period days from start to end`() {
        val logs = listOf(PeriodLog("1", "2026-10-01", "2026-10-05"))
        val s = PeriodLogic.stats(logs, d("2026-10-03"))
        assertTrue(s.inPeriod)
        assertEquals(3, s.dayOfPeriod)
        val s2 = PeriodLogic.stats(logs, d("2026-10-06"))
        assertFalse(s2.inPeriod)
    }

    @Test fun `prediction uses average of recent intervals`() {
        val logs = listOf(
            PeriodLog("1", "2026-08-01", "2026-08-05"),
            PeriodLog("2", "2026-08-29", "2026-09-02"), // 28 天
            PeriodLog("3", "2026-09-26", "2026-09-30"), // 28 天
            PeriodLog("4", "2026-10-24", "2026-10-28"), // 28 天
        )
        val s = PeriodLogic.stats(logs, d("2026-11-05"))
        assertEquals(28, s.cycleDays)
        assertEquals(3, s.samples)
        assertEquals(5, s.periodDays)
        assertEquals(d("2026-10-24"), s.lastStart)
        assertEquals(d("2026-11-21"), s.nextPredicted)
        assertEquals(16, s.daysUntilNext)
        assertFalse(s.inPeriod)
    }

    @Test fun `irregular gaps are excluded from average`() {
        val logs = listOf(
            PeriodLog("1", "2026-07-01", "2026-07-05"),
            PeriodLog("2", "2026-07-29", "2026-08-02"), // 28 天 ✓
            PeriodLog("3", "2026-10-01", "2026-10-05"), // 64 天 ✗（漏记）
            PeriodLog("4", "2026-10-29", "2026-11-02"), // 28 天 ✓
        )
        val s = PeriodLogic.stats(logs, d("2026-11-06"))
        assertEquals(2, s.samples)
        assertEquals(28, s.cycleDays)
    }

    @Test fun `only the last six intervals are sampled`() {
        var day = d("2026-01-01")
        val logs = mutableListOf<PeriodLog>()
        var i = 0
        while (day.isBefore(d("2026-10-01"))) {
            logs += PeriodLog("l$i", day.toString(), day.plusDays(4).toString())
            day = day.plusDays(30)
            i++
        }
        val s = PeriodLogic.stats(logs, day)
        assertEquals(PeriodLogic.MAX_SAMPLE, s.samples)
        assertEquals(30, s.cycleDays)
    }

    @Test fun `overdue prediction reports overdue days`() {
        val logs = listOf(
            PeriodLog("1", "2026-10-01", "2026-10-05"),
        )
        val s = PeriodLogic.stats(logs, d("2026-11-05")) // 28 天周期 → 预测 10-29，已过 7 天
        assertEquals(d("2026-10-29"), s.nextPredicted)
        assertNull(s.daysUntilNext)
        assertEquals(7, s.overdueDays)
    }

    @Test fun `deleted logs are ignored`() {
        val logs = listOf(
            PeriodLog("1", "2026-10-01", "2026-10-05", deleted = true),
            PeriodLog("2", "2026-10-29", "2026-11-02"),
        )
        val s = PeriodLogic.stats(logs, d("2026-11-06"))
        assertNull(s.overdueDays)
        assertEquals(d("2026-10-29"), s.lastStart)
        assertEquals(PeriodLogic.DEFAULT_CYCLE, s.cycleDays)
    }

    @Test fun `reminder fires within window and not during period`() {
        val logs = listOf(PeriodLog("1", "2026-10-01", "2026-10-05"))
        val inWindow = PeriodLogic.stats(logs, d("2026-10-27")) // 距预测 2 天
        assertTrue(PeriodLogic.shouldRemind(inWindow, d("2026-10-27")))
        val tooEarly = PeriodLogic.stats(logs, d("2026-10-10"))
        assertFalse(PeriodLogic.shouldRemind(tooEarly, d("2026-10-10")))
        val during = listOf(PeriodLog("2", "2026-10-27", null))
        assertFalse(PeriodLogic.shouldRemind(PeriodLogic.stats(during, d("2026-10-28")), d("2026-10-28")))
    }

    @Test fun `validate rejects inverted long and future ranges`() {
        val today = d("2026-11-06")
        assertEquals("结束日不能早于开始日", PeriodLogic.validate(d("2026-11-05"), d("2026-11-01"), today))
        assertEquals("一次经期不超过 14 天", PeriodLogic.validate(d("2026-11-01"), d("2026-11-20"), today))
        assertEquals("开始日不能是未来", PeriodLogic.validate(d("2026-11-07"), null, today))
        assertNull(PeriodLogic.validate(d("2026-11-01"), d("2026-11-05"), today))
    }
}
