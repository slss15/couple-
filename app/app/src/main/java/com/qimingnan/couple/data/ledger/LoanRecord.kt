package com.qimingnan.couple.data.ledger

/**
 * 借贷记录（信用与负债）：借入/借出、还了多少钱、还剩多少。
 *
 * 现状说明：记账表里没有借贷字段，数据库版本这次不升，所以记录先存在
 * 应用设置 DataStore 里（字符串编码），不新开表、不改 Room 版本。
 * 要长期存、要和账目联动（还款自动记一笔账、按对方汇总），下一步需要
 * 给 ledger_entries 加借贷类型字段或单独的 loans 表，那时再升数据库版本。
 * 编码用竖线分隔，标签里的竖线和换行会被替换掉，避免解析错位。
 */
enum class LoanKind(val label: String) { BORROWED("借入"), LENT("借出") }

data class LoanRecord(
    val id: String,
    val kind: LoanKind,
    val accountId: String?,
    val amountCents: Long,
    val repaidCents: Long = 0,
    val label: String = "",
) {
    /** 还剩多少没还（借入和借出都按“未结清”算）。 */
    val remainingCents: Long get() = (amountCents - repaidCents).coerceAtLeast(0)
    val settled: Boolean get() = remainingCents == 0L

    fun encode(): String = listOf(
        id,
        kind.name,
        accountId ?: "",
        amountCents.toString(),
        repaidCents.toString(),
        label.replace('|', ' ').replace('\n', ' '),
    ).joinToString("|")

    companion object {
        fun decode(raw: String): LoanRecord? {
            val parts = raw.split("|")
            if (parts.size < 6) return null
            val kind = LoanKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            val amount = parts[3].toLongOrNull() ?: return null
            val repaid = parts[4].toLongOrNull() ?: return null
            if (amount <= 0 || repaid < 0) return null
            return LoanRecord(parts[0], kind, parts[2].ifBlank { null }, amount, repaid, parts.subList(5, parts.size).joinToString("|"))
        }
    }
}

object LoanTotals {
    data class Summary(val borrowedCents: Long, val lentCents: Long, val repaidCents: Long) {
        val remainingCents: Long get() = (borrowedCents + lentCents - repaidCents).coerceAtLeast(0)
    }

    fun summarize(records: List<LoanRecord>): Summary = Summary(
        borrowedCents = records.filter { it.kind == LoanKind.BORROWED }.sumOf { it.amountCents },
        lentCents = records.filter { it.kind == LoanKind.LENT }.sumOf { it.amountCents },
        repaidCents = records.sumOf { it.repaidCents },
    )
}
