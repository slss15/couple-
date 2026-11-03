package com.qimingnan.couple.data.anniversary

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Anniversary event rules, pure logic (PR3, second slice).
 *
 * The calendar system is chosen by the user at entry time and stored as-is;
 * a lunar date is never silently converted to solar and thrown away, because
 * the original calendar is first-class data. The same holds for the leap
 * flag: a leap-month birthday stays a leap-month birthday.
 *
 * Edge cases, all decided here so the UI cannot drift:
 * - Solar Feb 29 in a non-leap year is observed on Feb 28.
 * - Lunar day 30 in a 29-day month is observed on that month's last day,
 *   flagged as [Occurrence.clampedDay] so the UI can say so.
 * - A leap-month birthday in a year without that leap month is observed on
 *   the regular month's same day, flagged as [Occurrence.leapFallback],
 *   unless [AnniversaryEvent.onlyLeapMonth] is true, in which case that
 *   year has no occurrence at all.
 * - Age is completed years (周岁), counted by actual occurrences, not by
 *   solar-year subtraction: a lunar birthday ages on its lunar date.
 */
enum class AnniversaryCalendar { SOLAR, LUNAR }

enum class AnniversaryType { BIRTHDAY, LOVE, WEDDING, FIRST_MEETING, FESTIVAL, CUSTOM }

data class AnniversaryEvent(
    val id: String,
    val title: String,
    val type: AnniversaryType,
    val calendar: AnniversaryCalendar,
    val month: Int,
    val day: Int,
    val isLeap: Boolean = false,
    /** Birth / event year, null for a recurring date with no year (e.g. a festival). */
    val year: Int? = null,
    val recurring: Boolean = true,
    val onlyLeapMonth: Boolean = false,
    val reminderDaysAhead: Int = 0,
    val note: String = "",
    val deleted: Boolean = false,
)

data class Occurrence(
    val date: LocalDate,
    val leapFallback: Boolean = false,
    val clampedDay: Boolean = false,
)

object AnniversaryLogic {
    private fun solarOccurrence(year: Int, event: AnniversaryEvent): LocalDate? {
        return try {
            if (event.month == 2 && event.day == 29 && !LocalDate.of(year, 1, 1).isLeapYear) {
                LocalDate.of(year, 2, 28)
            } else {
                LocalDate.of(year, event.month, event.day)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun lunarOccurrence(lunarYear: Int, event: AnniversaryEvent): Occurrence? {
        if (lunarYear !in LunarCalendar.MIN_YEAR..LunarCalendar.MAX_YEAR) return null
        val leap = LunarCalendar.leapMonth(lunarYear)
        val fallback = event.isLeap && leap != event.month
        if (fallback && event.onlyLeapMonth) return null
        val segmentDays = when {
            event.isLeap && !fallback -> LunarCalendar.leapMonthDays(lunarYear)
            else -> LunarCalendar.monthDays(lunarYear, event.month)
        }
        if (segmentDays <= 0) return null
        val clamped = event.day > segmentDays
        val day = if (clamped) segmentDays else event.day
        val solar = LunarCalendar.lunarToSolar(
            LunarDate(lunarYear, event.month, day, isLeap = event.isLeap && !fallback),
        ) ?: return null
        return Occurrence(solar, leapFallback = fallback, clampedDay = clamped)
    }

    fun nextOccurrence(event: AnniversaryEvent, from: LocalDate): Occurrence? {
        if (event.deleted) return null
        if (!event.recurring) {
            val y = event.year ?: return null
            return when (event.calendar) {
                AnniversaryCalendar.SOLAR -> solarOccurrence(y, event)?.let { Occurrence(it) }
                AnniversaryCalendar.LUNAR -> lunarOccurrence(y, event)
            }?.takeIf { it.date >= from }
        }
        return when (event.calendar) {
            AnniversaryCalendar.SOLAR -> {
                val thisYear = solarOccurrence(from.year, event)?.let { Occurrence(it) }
                if (thisYear != null && thisYear.date >= from) thisYear
                else solarOccurrence(from.year + 1, event)?.let { Occurrence(it) }
            }
            AnniversaryCalendar.LUNAR -> {
                // A lunar year spans two solar years, so search lunar years
                // around the reference solar year and take the earliest
                // occurrence on or after `from`.
                // Leap-only events recur rarely (a given leap month returns
                // roughly once a generation), so search far ahead for them.
                val endYear = if (event.onlyLeapMonth) from.year + 40 else from.year + 2
                (from.year - 1..endYear).mapNotNull { lunarOccurrence(it, event) }
                    .filter { it.date >= from }
                    .minByOrNull { it.date }
            }
        }
    }

    fun daysUntil(from: LocalDate, occurrence: Occurrence): Long =
        ChronoUnit.DAYS.between(from, occurrence.date)

    /**
     * Completed years for a birthday-type event, or null when no year is set
     * or the event has not happened yet. Counts real occurrences after the
     * birth event, so lunar birthdays age on their lunar date.
     */
    fun age(event: AnniversaryEvent, on: LocalDate): Int? {
        val birthYear = event.year ?: return null
        val birth = when (event.calendar) {
            AnniversaryCalendar.SOLAR -> solarOccurrence(birthYear, event)?.let { Occurrence(it) }
            AnniversaryCalendar.LUNAR -> lunarOccurrence(birthYear, event)
        } ?: return null
        if (on < birth.date) return null
        val occurrences = when (event.calendar) {
            AnniversaryCalendar.SOLAR ->
                (birthYear..on.year).mapNotNull { solarOccurrence(it, event)?.let { d -> Occurrence(d) } }
            AnniversaryCalendar.LUNAR ->
                (birthYear..on.year + 1).mapNotNull { lunarOccurrence(it, event) }
        }
        return occurrences.count { it.date > birth.date && it.date <= on }
    }

    /** Days together for the "在一起 X 天" card, from a base date to today. */
    fun daysTogether(start: LocalDate, today: LocalDate): Long =
        ChronoUnit.DAYS.between(start, today)
}
