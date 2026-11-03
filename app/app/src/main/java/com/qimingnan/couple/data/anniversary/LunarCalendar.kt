package com.qimingnan.couple.data.anniversary

import java.time.LocalDate

/**
 * Pure Kotlin Chinese lunar calendar (PR3, first slice).
 *
 * No network, no Android ICU, no system-calendar differences: the conversion
 * is driven by the classic 1900-2049 lunar-info table, encoded per year as:
 * - low 4 bits: leap month number (0 = no leap month)
 * - bit 16: leap month has 30 days (1) or 29 days (0)
 * - bits 4-15: months 1-12 have 30 days (1) or 29 days (0)
 *
 * Range is deliberately 1900-01-31 to 2049-12-31, the range this table
 * covers. It is NOT claimed to cover 2100: dates outside the range return
 * null instead of a guessed conversion. A lunar birthday in 2050+ therefore
 * cannot be scheduled from this table alone, and the UI must say so rather
 * than invent a date.
 *
 * All dates are PRC calendar dates (UTC+8); the conversion itself is
 * timezone-free because it works on [LocalDate] days, and callers must pass
 * the date already resolved in Asia/Shanghai.
 *
 * Verified test vectors (see LunarCalendarTest):
 * 2024-02-10 = 2024 正月初一, 2025-01-29 = 2025 正月初一,
 * 2024-09-17 = 2024 八月十五, 2024-06-10 and 2025-05-31 = 五月初五,
 * 2023 has leap month 2, 2025 has leap month 6.
 */
data class LunarDate(val year: Int, val month: Int, val day: Int, val isLeap: Boolean = false) {
    init {
        require(year in LunarCalendar.MIN_YEAR..LunarCalendar.MAX_YEAR) { "农历年份超出 1900-2049" }
        require(month in 1..12) { "农历月份必须在 1-12" }
        require(day >= 1) { "农历日期必须大于 0" }
    }
}

object LunarCalendar {
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2049
    private val BASE: LocalDate = LocalDate.of(1900, 1, 31)

    // 1900-2049, one value per year, in year order.
    private val LUNAR_INFO = intArrayOf(
        0x04BD8, 0x04AE0, 0x0A570, 0x054D5, 0x0D260, 0x0D950, 0x16554, 0x056A0, 0x09AD0, 0x055D2,
        0x04AE0, 0x0A5B6, 0x0A4D0, 0x0D250, 0x1D255, 0x0B540, 0x0D6A0, 0x0ADA2, 0x095B0, 0x14977,
        0x04970, 0x0A4B0, 0x0B4B5, 0x06A50, 0x06D40, 0x1AB54, 0x02B60, 0x09570, 0x052F2, 0x04970,
        0x06566, 0x0D4A0, 0x0EA50, 0x06E95, 0x05AD0, 0x02B60, 0x186E3, 0x092E0, 0x1C8D7, 0x0C950,
        0x0D4A0, 0x1D8A6, 0x0B550, 0x056A0, 0x1A5B4, 0x025D0, 0x092D0, 0x0D2B2, 0x0A950, 0x0B557,
        0x06CA0, 0x0B550, 0x15355, 0x04DA0, 0x0A5D0, 0x14573, 0x052D0, 0x0A9A8, 0x0E950, 0x06AA0,
        0x0AEA6, 0x0AB50, 0x04B60, 0x0AAE4, 0x0A570, 0x05260, 0x0F263, 0x0D950, 0x05B57, 0x056A0,
        0x096D0, 0x04DD5, 0x04AD0, 0x0A4D0, 0x0D4D4, 0x0D250, 0x0D558, 0x0B540, 0x0B5A0, 0x195A6,
        0x095B0, 0x049B0, 0x0A974, 0x0A4B0, 0x0B27A, 0x06A50, 0x06D40, 0x0AF46, 0x0AB60, 0x09570,
        0x04AF5, 0x04970, 0x064B0, 0x074A3, 0x0EA50, 0x06B58, 0x055C0, 0x0AB60, 0x096D5, 0x092E0,
        0x0C960, 0x0D954, 0x0D4A0, 0x0DA50, 0x07552, 0x056A0, 0x0ABB7, 0x025D0, 0x092D0, 0x0CAB5,
        0x0A950, 0x0B4A0, 0x0BAA4, 0x0AD50, 0x055D9, 0x04BA0, 0x0A5B0, 0x15176, 0x052B0, 0x0A930,
        0x07954, 0x06AA0, 0x0AD50, 0x05B52, 0x04B60, 0x0A6E6, 0x0A4E0, 0x0D260, 0x0EA65, 0x0D530,
        0x05AA0, 0x076A3, 0x096D0, 0x04BD7, 0x04AD0, 0x0A4D0, 0x1D0B6, 0x0D250, 0x0D520, 0x0DD45,
        0x0B5A0, 0x056D0, 0x055B2, 0x049B0, 0x0A577, 0x0A4B0, 0x0AA50, 0x1B255, 0x06D20, 0x0ADA0,
    )

    private fun info(year: Int): Int = LUNAR_INFO[year - MIN_YEAR]

    /** Leap month number, or 0 when the year has none. */
    fun leapMonth(year: Int): Int = info(year) and 0xF

    fun leapMonthDays(year: Int): Int =
        if (leapMonth(year) == 0) 0 else if (info(year) and 0x10000 != 0) 30 else 29

    fun monthDays(year: Int, month: Int): Int =
        if (info(year) and (0x10000 shr month) != 0) 30 else 29

    fun yearDays(year: Int): Int {
        var sum = 12 * 29
        for (i in 0 until 12) if (info(year) and (0x8000 shr i) != 0) sum += 1
        return sum + leapMonthDays(year)
    }

    /** Ordered segments of a lunar year: regular month, then its leap month if any. */
    private fun segments(year: Int): List<LunarDate> {
        val out = ArrayList<LunarDate>()
        val leap = leapMonth(year)
        for (m in 1..12) {
            out.add(LunarDate(year, m, monthDays(year, m), false))
            if (m == leap) out.add(LunarDate(year, m, leapMonthDays(year), true))
        }
        return out
    }

    /** Solar -> lunar, or null when the solar date is outside 1900-01-31..2049-12-31. */
    fun solarToLunar(solar: LocalDate): LunarDate? {
        if (solar < BASE || solar.year > MAX_YEAR) return null
        var offset = (solar.toEpochDay() - BASE.toEpochDay()).toInt()
        var year = MIN_YEAR
        while (offset >= yearDays(year)) {
            offset -= yearDays(year)
            year++
            if (year > MAX_YEAR) return null
        }
        for (segment in segments(year)) {
            val days = segment.day
            if (offset < days) return LunarDate(year, segment.month, offset + 1, segment.isLeap)
            offset -= days
        }
        return null
    }

    /** Lunar -> solar, or null when the lunar date does not exist (e.g. day 30 in a 29-day month). */
    fun lunarToSolar(lunar: LunarDate): LocalDate? {
        if (lunar.year !in MIN_YEAR..MAX_YEAR) return null
        val segment = segments(lunar.year).firstOrNull { it.month == lunar.month && it.isLeap == lunar.isLeap }
            ?: return null
        if (lunar.day > segment.day) return null
        var total = 0L
        for (y in MIN_YEAR until lunar.year) total += yearDays(y)
        for (s in segments(lunar.year)) {
            if (s.month == lunar.month && s.isLeap == lunar.isLeap) break
            total += s.day
        }
        return BASE.plusDays(total + lunar.day - 1)
    }

    private val STEMS = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val BRANCHES = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val ZODIAC = listOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    /** GanZhi year, e.g. 2024 -> 甲辰. */
    fun ganZhi(year: Int): String = STEMS[(year - 4) % 10] + BRANCHES[(year - 4) % 12]

    fun zodiac(year: Int): String = ZODIAC[(year - 4) % 12]

    private val MONTH_NAMES = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")

    /** Chinese display, e.g. 甲辰年正月初一 / 闰二月初一. */
    fun display(lunar: LunarDate): String {
        val month = (if (lunar.isLeap) "闰" else "") + MONTH_NAMES[lunar.month - 1] + "月"
        val day = when (lunar.day) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> {
                val tens = listOf("", "十", "廿", "三")[lunar.day / 10]
                val ones = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[lunar.day % 10]
                if (lunar.day < 10) "初$ones" else tens + ones
            }
        }
        return "${ganZhi(lunar.year)}年$month$day"
    }
}
