package com.qimingnan.couple.data.ledger

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 个人借贷账户明细；旧 DataStore 记录兼容读取后迁入 Room，与资金账目事务化保存。 */
@Serializable
enum class LoanKind(val label: String) { BORROWED("借入"), LENT("借出") }

@Serializable
enum class CounterpartyType(val label: String) { PERSON("个人"), PLATFORM("平台") }

@Serializable
enum class LoanAction(val label: String, val entryType: LedgerEntryType) {
    BORROW("借入到账", LedgerEntryType.INCOME),
    LEND("借出付款", LedgerEntryType.EXPENSE),
    REPAY_BORROWED("还钱", LedgerEntryType.EXPENSE),
    COLLECT_LENT("收回借款", LedgerEntryType.INCOME),
}

@Serializable
data class LoanRepayment(
    val id: String,
    val amountCents: Long,
    val date: String,
    val note: String = "",
    val entryId: String? = null,
    val cashAccountId: String? = null,
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
    /** 借出/借入账户；accountId 仍是实际资金账户，避免误解旧关联字段。 */
    val loanAccountId: String? = null,
    val counterpartyType: CounterpartyType = CounterpartyType.PERSON,
    val occurredOn: String? = null,
) {
    val remainingCents: Long get() = (amountCents - repaidCents).coerceAtLeast(0)
    val settled: Boolean get() = remainingCents == 0L
    val counterpartyName: String get() = counterparty.ifBlank { label.ifBlank { "未分类（旧记录）" } }
    val counterpartyKey: String get() = "${counterpartyType.name}:${counterpartyName.trim()}"

    fun validationError(): String? = when {
        id.isBlank() -> "借贷编号不能为空"
        amountCents <= 0 -> "金额必须大于 0"
        repaidCents < 0 || repaidCents > amountCents -> "已还金额不能超过借贷金额"
        occurredOn != null && runCatching { LocalDate.parse(occurredOn) }.isFailure -> "借款日期格式为 yyyy-MM-dd"
        dueOn != null && runCatching { LocalDate.parse(dueOn) }.isFailure -> "约定日期格式为 yyyy-MM-dd"
        repayments.any { it.amountCents <= 0 || runCatching { LocalDate.parse(it.date) }.isFailure } -> "还款记录无效"
        else -> null
    }

    fun overdue(today: LocalDate): Boolean = !settled && dueOn?.let {
        runCatching { LocalDate.parse(it).isBefore(today) }.getOrDefault(false)
    } == true

    /** 拒绝负数、零、超额与非法日期；加法上界先检查，不会 Long 溢出。 */
    fun repay(cents: Long, date: String, note: String = "", paymentId: String = UUID.randomUUID().toString(),
        entryId: String? = null, cashAccountId: String? = null): LoanRecord? {
        if (validationError() != null || cents <= 0 || cents > remainingCents ||
            runCatching { LocalDate.parse(date) }.isFailure) return null
        return copy(
            repaidCents = repaidCents + cents,
            repayments = repayments + LoanRepayment(paymentId, cents, date, note.trim(), entryId, cashAccountId),
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
