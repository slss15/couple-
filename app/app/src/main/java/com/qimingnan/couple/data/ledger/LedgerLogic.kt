package com.qimingnan.couple.data.ledger

import java.time.LocalDate
import java.time.YearMonth

/**
 * Ordinary personal ledger, pure logic (PR4, first slice).
 *
 * This file deliberately contains no Android, Room or UI code so the money
 * rules are unit-testable on the JVM. Room entities, repository and UI land
 * in the same PR later; they must reuse these rules, not re-implement them.
 *
 * Money is always cents ([Long]), never floating point. A transfer is not
 * income and not expense: it only moves money between two accounts, so
 * total assets do not change because of a transfer.
 */
enum class LedgerAccountType { CASH, BANK, ALIPAY, WECHAT, OTHER }

enum class LedgerEntryType { EXPENSE, INCOME, TRANSFER }

data class LedgerAccount(
    val id: String,
    val name: String,
    val type: LedgerAccountType,
    /** Opening balance in cents, before any entry. */
    val initialBalanceCents: Long,
)

data class LedgerEntry(
    val id: String,
    val ownerId: String,
    /** Account for EXPENSE/INCOME; null for TRANSFER (see from/to below). */
    val accountId: String?,
    val type: LedgerEntryType,
    /** Null for TRANSFER. */
    val categoryId: String?,
    val amountCents: Long,
    val fromAccountId: String?,
    val toAccountId: String?,
    val occurredOn: LocalDate,
    val deleted: Boolean = false,
)

object LedgerLogic {
    /** Entries that count in balances and statistics: soft-deleted entries never do. */
    fun live(entries: List<LedgerEntry>): List<LedgerEntry> = entries.filter { !it.deleted }

    /**
     * Validation for a new/edited entry. Returns null when valid, otherwise a
     * short Chinese reason the UI can show directly. A negative balance is
     * deliberately NOT rejected here: back-filled entries may legitimately
     * take an account negative, and the UI warns instead of blocking.
     */
    fun validate(entry: LedgerEntry): String? = when {
        entry.amountCents <= 0 -> "金额必须大于 0"
        entry.type == LedgerEntryType.TRANSFER && entry.categoryId != null -> "转账不需要分类"
        entry.type == LedgerEntryType.TRANSFER &&
            (entry.fromAccountId.isNullOrBlank() || entry.toAccountId.isNullOrBlank()) -> "转账需要转出和转入账户"
        entry.type == LedgerEntryType.TRANSFER && entry.fromAccountId == entry.toAccountId -> "转出和转入账户不能相同"
        entry.type != LedgerEntryType.TRANSFER && entry.accountId.isNullOrBlank() -> "需要选择账户"
        entry.type != LedgerEntryType.TRANSFER && entry.categoryId.isNullOrBlank() -> "需要选择分类"
        else -> null
    }

    fun balance(account: LedgerAccount, entries: List<LedgerEntry>): Long {
        var total = account.initialBalanceCents
        for (e in live(entries)) {
            when (e.type) {
                LedgerEntryType.INCOME -> if (e.accountId == account.id) total += e.amountCents
                LedgerEntryType.EXPENSE -> if (e.accountId == account.id) total -= e.amountCents
                LedgerEntryType.TRANSFER -> {
                    if (e.fromAccountId == account.id) total -= e.amountCents
                    if (e.toAccountId == account.id) total += e.amountCents
                }
            }
        }
        return total
    }

    fun totalAssets(accounts: List<LedgerAccount>, entries: List<LedgerEntry>): Long =
        accounts.sumOf { balance(it, entries) }

    data class MonthTotals(val incomeCents: Long, val expenseCents: Long) {
        val netCents: Long get() = incomeCents - expenseCents
    }

    /** Transfers are excluded: they are neither income nor expense. */
    fun monthTotals(entries: List<LedgerEntry>, month: YearMonth): MonthTotals {
        var income = 0L
        var expense = 0L
        for (e in live(entries)) {
            if (YearMonth.from(e.occurredOn) != month) continue
            when (e.type) {
                LedgerEntryType.INCOME -> income += e.amountCents
                LedgerEntryType.EXPENSE -> expense += e.amountCents
                LedgerEntryType.TRANSFER -> Unit
            }
        }
        return MonthTotals(income, expense)
    }

    fun categoryTotals(entries: List<LedgerEntry>, month: YearMonth, type: LedgerEntryType): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (e in live(entries)) {
            if (e.type != type || YearMonth.from(e.occurredOn) != month) continue
            val key = e.categoryId ?: continue
            out[key] = (out[key] ?: 0L) + e.amountCents
        }
        return out
    }

    /** Budget progress as a ratio (1.0 = exactly at budget); null when no budget is set. */
    fun budgetProgress(spentCents: Long, budgetCents: Long?): Double? =
        if (budgetCents == null || budgetCents <= 0) null else spentCents.toDouble() / budgetCents.toDouble()
}
