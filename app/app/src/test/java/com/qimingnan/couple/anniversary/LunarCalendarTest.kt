package com.qimingnan.couple.anniversary

import com.qimingnan.couple.data.anniversary.LunarCalendar
import com.qimingnan.couple.data.anniversary.LunarDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LunarCalendarTest {
    @Test fun springFestivals() {
        assertEquals(LunarDate(2024, 1, 1), LunarCalendar.solarToLunar(LocalDate.of(2024, 2, 10)))
        assertEquals(LunarDate(2025, 1, 1), LunarCalendar.solarToLunar(LocalDate.of(2025, 1, 29)))
    }

    @Test fun midAutumnAndDragonBoat() {
        assertEquals(LunarDate(2024, 8, 15), LunarCalendar.solarToLunar(LocalDate.of(2024, 9, 17)))
        assertEquals(LunarDate(2024, 5, 5), LunarCalendar.solarToLunar(LocalDate.of(2024, 6, 10)))
        assertEquals(LunarDate(2025, 5, 5), LunarCalendar.solarToLunar(LocalDate.of(2025, 5, 31)))
    }

    @Test fun leapMonths() {
        assertEquals(2, LunarCalendar.leapMonth(2023))
        assertEquals(6, LunarCalendar.leapMonth(2025))
        assertEquals(LocalDate.of(2023, 3, 22), LunarCalendar.lunarToSolar(LunarDate(2023, 2, 1, isLeap = true)))
    }

    @Test fun roundTripAndInvalidDates() {
        val solar = LocalDate.of(2024, 9, 17)
        val lunar = LunarCalendar.solarToLunar(solar)!!
        assertEquals(solar, LunarCalendar.lunarToSolar(lunar))
        // 2024 八月 is a 30-day month (2024-10-02 is 八月三十), so use 九月
        // instead: 2024-10-31 is 九月廿九, and 九月 has 29 days.
        assertNull(LunarCalendar.lunarToSolar(LunarDate(2024, 9, 30)))
        // Outside the table range: no guessed conversion.
        assertNull(LunarCalendar.solarToLunar(LocalDate.of(2050, 1, 1)))
        // Out-of-range lunar years are rejected at construction, not guessed.
        try {
            LunarCalendar.lunarToSolar(LunarDate(2050, 1, 1))
            throw AssertionError("expected IllegalArgumentException for year 2050")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun ganZhiAndDisplay() {
        assertEquals("甲辰", LunarCalendar.ganZhi(2024))
        assertEquals("龙", LunarCalendar.zodiac(2024))
        assertEquals("甲辰年正月初一", LunarCalendar.display(LunarDate(2024, 1, 1)))
        assertEquals("癸卯年闰二月初一", LunarCalendar.display(LunarDate(2023, 2, 1, isLeap = true)))
    }
}
