package com.qimingnan.couple.data.ledger

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 个人借贷备忘，存本机 DataStore；关联账户只作归属，不重复改变账本余额。 */
@Serializable
enum class LoanKind(val label: String) { BORROWED("借入"), LENT("借出") }

@Serializable
data class LoanRepayment(
    val id: String,
    val amountCents: Long,
    val date: String,
    val note: String = "",
)

@Serializable
data class LoanRecord(
    val id: String,
    val kind: LoanKind,
    val accountId: String?,
    val amountCents: Long,
    val repaidCents: Long = 0,
    val label: String = "",
    val counterparty: String = "",
    val dueOn: String? = null,
    val repayments: List<LoanRepayment> = emptyList(),
) {
    val remainingCents: Long get() = (amountCents - repaidCents).coerceAtLeast(0)
    val settled: Boolean get() = remainingCents == 0L

    fun validationError(): String? = when {
        id.isBlank() -> "借贷编号不能为空"
        amountCents <= 0 -> "金额必须大于 0"
        repaidCents < 0 || repaidCents > amountCents -> "已还金额不能超过借贷金额"
        dueOn != null && runCatching { LocalDate.parse(dueOn) }.isFailure -> "约定日期格式为 yyyy-MM-dd"
        repayments.any { it.amountCents <= 0 || runCatching { LocalDate.parse(it.date) }.isFailure } -> "还款记录无效"
        else -> null
    }

    fun overdue(today: LocalDate): Boolean = !settled && dueOn?.let {
        runCatching { LocalDate.parse(it).isBefore(today) }.getOrDefault(false)
    } == true

    /** 拒绝负数、零、超额与非法日期；加法上界先检查，不会 Long 溢出。 */
    fun repay(cents: Long, date: String, note: String = ""): LoanRecord? {
        if (validationError() != null || cents <= 0 || cents > remainingCents ||
            runCatching { LocalDate.parse(date) }.isFailure) return null
        return copy(
            repaidCents = repaidCents + cents,
            repayments = repayments + LoanRepayment(UUID.randomUUID().toString(), cents, date, note.trim()),
        )
    }

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 向后兼容六段竖线格式；新格式保留名字里的竖线、换行及完整还款明细。 */
        fun decode(raw: String): LoanRecord? = runCatching {
            val record = if (raw.trimStart().startsWith("{")) json.decodeFromString<LoanRecord>(raw) else {
                val parts = raw.split("|")
                if (parts.size < 6) return null
                LoanRecord(
                    parts[0], LoanKind.valueOf(parts[1]), parts[2].ifBlank { null },
                    parts[3].toLong(), parts[4].toLong(), parts.drop(5).joinToString("|"),
                )
            }
            record.takeIf { it.validationError() == null }
        }.getOrNull()
    }
}

object LoanTotals {
    data class Summary(
        val borrowedCents: Long,
        val lentCents: Long,
        val repaidCents: Long,
        val outstandingBorrowedCents: Long = 0,
        val outstandingLentCents: Long = 0,
    ) {
        val remainingCents: Long get() = outstandingBorrowedCents + outstandingLentCents
    }

    fun summarize(records: List<LoanRecord>): Summary = Summary(
        borrowedCents = records.filter { it.kind == LoanKind.BORROWED }.sumOf { it.amountCents },
        lentCents = records.filter { it.kind == LoanKind.LENT }.sumOf { it.amountCents },
        repaidCents = records.sumOf { it.repaidCents },
        outstandingBorrowedCents = records.filter { it.kind == LoanKind.BORROWED }.sumOf { it.remainingCents },
        outstandingLentCents = records.filter { it.kind == LoanKind.LENT }.sumOf { it.remainingCents },
    )
}
