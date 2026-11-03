package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** contract "Chat → Scheduled messages"：重复规则。kind = ONCE | DAILY | WEEKLY | YEARLY。 */
@Serializable
data class ScheduleRule(
    val kind: String,
    val at: Long? = null,
    /** "HH:mm"，规则时区的本地时间。 */
    val time: String? = null,
    /** 1=周一 … 7=周日。 */
    val dayOfWeek: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    companion object {
        const val ONCE = "ONCE"
        const val DAILY = "DAILY"
        const val WEEKLY = "WEEKLY"
        const val YEARLY = "YEARLY"

        /** 由选中的本地日期时间生成规则：单次用绝对时刻，其余取其中的时分 / 星期 / 月日。 */
        fun fromPick(kind: String, at: ZonedDateTime): ScheduleRule {
            val hm = "%02d:%02d".format(at.hour, at.minute)
            return when (kind) {
                DAILY -> ScheduleRule(DAILY, time = hm)
                WEEKLY -> ScheduleRule(WEEKLY, time = hm, dayOfWeek = at.dayOfWeek.value)
                YEARLY -> ScheduleRule(YEARLY, time = hm, month = at.monthValue, day = at.dayOfMonth)
                else -> ScheduleRule(ONCE, at = at.toInstant().toEpochMilli())
            }
        }
    }
}

@Serializable
data class ScheduledMessage(
    val id: String,
    val senderId: String = "",
    val type: String = "text",
    val text: String = "",
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rule: ScheduleRule,
    val tz: String = ScheduleCalc.DEFAULT_TZ,
    /** null = 已结束（单次已发送）。 */
    val nextFireAt: Long? = null,
    val paused: Boolean = false,
    val lastFiredAt: Long? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ScheduledList(val items: List<ScheduledMessage> = emptyList())

@Serializable
data class CreateScheduledRequest(
    val type: String = "text",
    val text: String = "",
    val mediaId: String? = null,
    val stickerUrl: String? = null,
    val rule: ScheduleRule,
    val tz: String? = null,
)

@Serializable
data class PatchScheduledRequest(
    val paused: Boolean? = null,
    val text: String? = null,
    val rule: ScheduleRule? = null,
    val tz: String? = null,
)

/** 与后端 ScheduleMath 同一套规则（演示后端与 UI 预览共用）。 */
object ScheduleCalc {
    const val DEFAULT_TZ = "Asia/Shanghai"
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** 规则合法时返回 null，否则返回错误说明。 */
    fun problem(rule: ScheduleRule): String? = when (rule.kind) {
        ScheduleRule.ONCE -> if (rule.at == null) "rule.at required" else null
        ScheduleRule.DAILY, ScheduleRule.WEEKLY, ScheduleRule.YEARLY -> when {
            rule.time == null || !TIME_RE.matches(rule.time) -> "rule.time must be HH:mm"
            rule.kind == ScheduleRule.WEEKLY && rule.dayOfWeek !in 1..7 -> "rule.dayOfWeek must be 1..7"
            rule.kind == ScheduleRule.YEARLY && (rule.month !in 1..12 || rule.day == null ||
                rule.day !in 1..java.time.Month.of(rule.month!!).maxLength()) -> "rule.month/day out of range"
            else -> null
        }
        else -> "rule.kind must be ONCE, DAILY, WEEKLY or YEARLY"
    }

    /** 严格晚于 [afterMs] 的下一次触发；2 月 29 日在平年落到 2 月 28 日；ONCE 已过返回 null。 */
    fun next(rule: ScheduleRule, zone: ZoneId, afterMs: Long): Long? {
        val today = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        fun at(date: LocalDate) = ZonedDateTime.of(date, LocalTime.parse(rule.time!!), zone).toInstant().toEpochMilli()
        return when (rule.kind) {
            ScheduleRule.ONCE -> rule.at?.takeIf { it > afterMs }
            ScheduleRule.DAILY -> (0L..2L).map { at(today.plusDays(it)) }.first { it > afterMs }
            ScheduleRule.WEEKLY -> {
                val first = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(rule.dayOfWeek!!)))
                (0L..2L).map { at(first.plusWeeks(it)) }.first { it > afterMs }
            }
            ScheduleRule.YEARLY -> (0..8).map { off ->
                val y = today.year + off
                val d = if (rule.month == 2 && rule.day == 29 && !Year.isLeap(y.toLong())) 28 else rule.day!!
                at(LocalDate.of(y, rule.month!!, d))
            }.first { it > afterMs }
            else -> null
        }
    }

    private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 列表里的一行规则说明，如「每周三 08:00」。 */
    fun describe(rule: ScheduleRule, zone: ZoneId): String = when (rule.kind) {
        ScheduleRule.ONCE -> "单次 " + (rule.at?.let { format(it, zone) } ?: "")
        ScheduleRule.DAILY -> "每天 ${rule.time}"
        ScheduleRule.WEEKLY -> "每周${WEEK.getOrElse((rule.dayOfWeek ?: 1) - 1) { "?" }} ${rule.time}"
        ScheduleRule.YEARLY -> "每年 ${rule.month}月${rule.day}日 ${rule.time}"
        else -> rule.kind
    }

    fun format(ms: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        return "%d-%02d-%02d %02d:%02d".format(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }
}
