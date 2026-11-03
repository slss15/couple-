package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.data.ledger.LedgerLogic
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LedgerLogicTest {
    private val cash = LedgerAccount("cash", "现金", LedgerAccountType.CASH, 10_000)
    private val bank = LedgerAccount("bank", "储蓄卡", LedgerAccountType.BANK, 50_000)
    private val day = LocalDate.of(2026, 11, 4)
    private val month = YearMonth.of(2026, 11)

    private fun entry(
        id: String,
        type: LedgerEntryType,
        amount: Long,
        account: String? = "cash",
        category: String? = "food",
        from: String? = null,
        to: String? = null,
        deleted: Boolean = false,
    ) = LedgerEntry(id, "me", account, type, category, amount, from, to, day, deleted)

    @Test fun transferMovesMoneyButNotTotalsOrAssets() {
        val entries = listOf(
            entry("e1", LedgerEntryType.TRANSFER, 3_000, account = null, category = null, from = "cash", to = "bank"),
        )
        assertEquals(7_000, LedgerLogic.balance(cash, entries))
        assertEquals(53_000, LedgerLogic.balance(bank, entries))
        assertEquals(60_000, LedgerLogic.totalAssets(listOf(cash, bank), entries))
        assertEquals(LedgerLogic.MonthTotals(0, 0), LedgerLogic.monthTotals(entries, month))
    }

    @Test fun incomeExpenseAndSoftDelete() {
        val entries = listOf(
            entry("e1", LedgerEntryType.INCOME, 100_000, category = "salary"),
            entry("e2", LedgerEntryType.EXPENSE, 2_500, category = "food"),
            entry("e3", LedgerEntryType.EXPENSE, 99_999, category = "food", deleted = true),
        )
        assertEquals(107_500, LedgerLogic.balance(cash, entries))
        assertEquals(LedgerLogic.MonthTotals(100_000, 2_500), LedgerLogic.monthTotals(entries, month))
        assertEquals(mapOf("food" to 2_500L), LedgerLogic.categoryTotals(entries, month, LedgerEntryType.EXPENSE))
    }

    @Test fun validationRejectsSameAccountTransferAndZeroAmount() {
        assertNotNull(LedgerLogic.validate(entry("x", LedgerEntryType.TRANSFER, 100, account = null, category = null, from = "cash", to = "cash")))
        assertNotNull(LedgerLogic.validate(entry("x", LedgerEntryType.EXPENSE, 0)))
        assertNull(LedgerLogic.validate(entry("x", LedgerEntryType.TRANSFER, 100, account = null, category = null, from = "cash", to = "bank")))
    }

    @Test fun budgetProgress() {
        assertEquals(0.8, LedgerLogic.budgetProgress(800, 1_000)!!, 0.0001)
        assertNull(LedgerLogic.budgetProgress(800, null))
    }
}
