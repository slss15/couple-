package com.qimingnan.couple.anniversary

import com.qimingnan.couple.data.anniversary.AnniversaryCalendar
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.anniversary.AnniversaryType
import com.qimingnan.couple.data.anniversary.LunarCalendar
import com.qimingnan.couple.data.anniversary.LunarDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryLogicTest {
    private fun solarEvent(month: Int, day: Int, year: Int? = null, recurring: Boolean = true) =
        AnniversaryEvent("e", "生日", AnniversaryType.BIRTHDAY, AnniversaryCalendar.SOLAR, month, day, year = year, recurring = recurring)

    private fun lunarEvent(month: Int, day: Int, isLeap: Boolean = false, year: Int? = null, onlyLeap: Boolean = false) =
        AnniversaryEvent("e", "生日", AnniversaryType.BIRTHDAY, AnniversaryCalendar.LUNAR, month, day, isLeap = isLeap, year = year, onlyLeapMonth = onlyLeap)

    @Test fun solarFeb29FallsBackToFeb28() {
        val occ = AnniversaryLogic.nextOccurrence(solarEvent(2, 29), LocalDate.of(2025, 2, 27))!!
        assertEquals(LocalDate.of(2025, 2, 28), occ.date)
        val next = AnniversaryLogic.nextOccurrence(solarEvent(2, 29), LocalDate.of(2025, 3, 1))!!
        assertEquals(LocalDate.of(2026, 2, 28), next.date)
    }

    @Test fun lunarDragonBoatNextOccurrence() {
        val occ = AnniversaryLogic.nextOccurrence(lunarEvent(5, 5), LocalDate.of(2024, 6, 9))!!
        assertEquals(LocalDate.of(2024, 6, 10), occ.date)
        val after = AnniversaryLogic.nextOccurrence(lunarEvent(5, 5), LocalDate.of(2024, 6, 11))!!
        assertEquals(LocalDate.of(2025, 5, 31), after.date)
    }

    @Test fun lunarDay30ClampsToLastDayAndFlagsIt() {
        // 2024 九月 has 29 days: 09-29 is 2024-10-31, so 09-30 clamps there.
        val occ = AnniversaryLogic.nextOccurrence(lunarEvent(9, 30), LocalDate.of(2024, 10, 1))!!
        assertEquals(LocalDate.of(2024, 10, 31), occ.date)
        assertTrue(occ.clampedDay)
    }

    @Test fun leapBirthdayFallsBackToRegularMonth() {
        val occ = AnniversaryLogic.nextOccurrence(lunarEvent(2, 1, isLeap = true), LocalDate.of(2024, 1, 1))!!
        assertTrue(occ.leapFallback)
        assertEquals(LunarCalendar.lunarToSolar(LunarDate(2024, 2, 1)), occ.date)
        val onlyLeap = AnniversaryLogic.nextOccurrence(lunarEvent(2, 1, isLeap = true, onlyLeap = true), LocalDate.of(2024, 1, 1))!!
        // 2023 has leap month 2, but that occurrence is in the past; the next
        // leap-2 occurrence must be a real leap month, not the 2024 fallback.
        assertTrue(!onlyLeap.leapFallback)
        val onlyLeapLunar = LunarCalendar.solarToLunar(onlyLeap.date)!!
        assertEquals(2, onlyLeapLunar.month)
        assertTrue(onlyLeapLunar.isLeap)
    }

    @Test fun solarAge() {
        val event = solarEvent(11, 4, year = 2020)
        assertEquals(6, AnniversaryLogic.age(event, LocalDate.of(2026, 11, 4)))
        assertEquals(5, AnniversaryLogic.age(event, LocalDate.of(2026, 11, 3)))
    }

    @Test fun lunarAgeCountsLunarBirthdays() {
        val event = lunarEvent(5, 5, year = 2020)
        assertEquals(4, AnniversaryLogic.age(event, LocalDate.of(2024, 6, 10)))
        assertEquals(3, AnniversaryLogic.age(event, LocalDate.of(2024, 6, 9)))
    }

    @Test fun daysTogether() {
        assertEquals(10L, AnniversaryLogic.daysTogether(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 4)))
    }
}
