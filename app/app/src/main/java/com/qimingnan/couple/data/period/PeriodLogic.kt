package com.qimingnan.couple.data.period

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 经期记录的纯逻辑（contract-free：PERSONAL 插件，数据只在本机，见 data/plugin/Plugin.kt 隔离契约）。
 *
 * 所有边界决策收在这里，UI 不能自行发挥：
 * - 周期 = 相邻两次「开始日」的间隔；取最近 [MAX_SAMPLE] 个有效间隔求平均，得到 [Stats.cycleDays]；
 * - 间隔不在 [MIN_CYCLE, MAX_CYCLE] 视为异常数据（漏记/补记），不参与平均；
 * - 不足两个有效间隔时回落到 [DEFAULT_CYCLE]；
 * - 「经期中」= 今天落在某条记录的 [start, end] 内；未填结束日的进行中记录按 [DEFAULT_PERIOD] 天封顶；
 * - 预测下次 = 最近一次开始日 + 平均周期；预测日已过且没有新记录时，展示为「推迟 N 天」；
 * - 记录重叠（新记录开始日早于上一条结束日）在录入层校验，这里不再防御。
 */
enum class PeriodPhase { NONE, IN_PERIOD, PREDICTED_SOON, WAITING }

data class PeriodLog(
    val id: String,
    /** ISO: yyyy-MM-dd */
    val startDate: String,
    /** ISO；null = 还没结束（进行中）。 */
    val endDate: String?,
    val note: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    val start: LocalDate get() = LocalDate.parse(startDate)
    val end: LocalDate? get() = endDate?.let(LocalDate::parse)
}
data class Stats(
    /** 平均周期天数；样本不足时为 [DEFAULT_CYCLE]。 */
    val cycleDays: Int,
    /** 参与平均的间隔样本数。 */
    val samples: Int,
    /** 平均经期天数；没有已结束记录时为 [DEFAULT_PERIOD]。 */
    val periodDays: Int,
    /** 最近一次开始日（不含已删除）。 */
    val lastStart: LocalDate?,
    /** 今天是否在经期内。 */
    val inPeriod: Boolean,
    /** 经期第几天（从 1 起）；不在经期内为 null。 */
    val dayOfPeriod: Int?,
    /** 距预测下次开始的天数；经期中为 null。 */
    val daysUntilNext: Int?,
    /** 预测下次开始日；无历史时为 null。 */
    val nextPredicted: LocalDate?,
    /** 预测日已过的天数（推迟）；未推迟为 null。 */
    val overdueDays: Int?,
)

object PeriodLogic {
    const val DEFAULT_CYCLE = 28
    const val DEFAULT_PERIOD = 5
    const val MIN_CYCLE = 15
    const val MAX_CYCLE = 60
    const val MAX_SAMPLE = 6

    fun start(log: PeriodLog): LocalDate = log.start
    fun end(log: PeriodLog): LocalDate = log.end ?: log.start.plusDays(DEFAULT_PERIOD - 1L)

    /** 有效日志按开始日升序；调用方保证未删除。 */
    private fun sorted(logs: List<PeriodLog>): List<PeriodLog> = logs.sortedBy { it.start }

    fun stats(logs: List<PeriodLog>, today: LocalDate): Stats {
        val live = sorted(logs.filter { !it.deleted })
        val intervals = live.zipWithNext().mapNotNull { (a, b) ->
            val gap = ChronoUnit.DAYS.between(start(a), start(b)).toInt()
            if (gap in MIN_CYCLE..MAX_CYCLE) gap else null
        }
        val recent = intervals.takeLast(MAX_SAMPLE)
        val cycleDays = if (recent.isEmpty()) DEFAULT_CYCLE else recent.sum() / recent.size
        val periodDays = live.mapNotNull { l ->
            l.end?.let { ChronoUnit.DAYS.between(l.start, it).toInt() + 1 }
        }.filter { it in 1..14 }.takeLast(6).let { if (it.isEmpty()) DEFAULT_PERIOD else it.sum() / it.size }

        val last = live.lastOrNull()
        val lastStart = last?.let(::start)
        var inPeriod = false
        var dayOfPeriod: Int? = null
        for (l in live.asReversed()) {
            val s = start(l)
            val e = end(l)
            if (!today.isBefore(s) && !today.isAfter(e)) {
                inPeriod = true
                dayOfPeriod = ChronoUnit.DAYS.between(s, today).toInt() + 1
                break
            }
            if (today.isAfter(e)) break // 再往前的不可能覆盖今天
        }
        var nextPredicted: LocalDate? = null
        var daysUntilNext: Int? = null
        var overdueDays: Int? = null
        if (!inPeriod && lastStart != null) {
            val predicted = lastStart.plusDays(cycleDays.toLong())
            nextPredicted = predicted
            val d = ChronoUnit.DAYS.between(today, predicted).toInt()
            if (d >= 0) daysUntilNext = d else overdueDays = -d
        }
        return Stats(
            cycleDays = cycleDays, samples = recent.size, periodDays = periodDays,
            lastStart = lastStart, inPeriod = inPeriod, dayOfPeriod = dayOfPeriod,
            daysUntilNext = daysUntilNext, nextPredicted = nextPredicted, overdueDays = overdueDays,
        )
    }

    /** 提醒：距预测开始 ≤ [daysAhead] 天（含今天开始）。 */
    fun shouldRemind(s: Stats, today: LocalDate, daysAhead: Int = 2): Boolean =
        !s.inPeriod && s.nextPredicted != null && ChronoUnit.DAYS.between(today, s.nextPredicted) <= daysAhead

    /** 录入校验：结束日不早于开始日、跨度 ≤ 14 天、开始日不晚于今天。返回错误信息，null = 通过。 */
    fun validate(start: LocalDate, end: LocalDate?, today: LocalDate = LocalDate.now()): String? {
        if (end != null) {
            if (end.isBefore(start)) return "结束日不能早于开始日"
            if (ChronoUnit.DAYS.between(start, end) >= 14) return "一次经期不超过 14 天"
        }
        if (start.isAfter(today)) return "开始日不能是未来"
        return null
    }
}
