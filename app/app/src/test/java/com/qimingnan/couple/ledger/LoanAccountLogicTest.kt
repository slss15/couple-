package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class LoanAccountLogicTest {
    private val date = LocalDate.of(2026, 11, 7)
    private val cash = LedgerAccount("cash", "现金", LedgerAccountType.CASH, 10000)
    private val lent = LedgerAccount("lent", "借出", LedgerAccountType.LENT, 0)
    private val borrowed = LedgerAccount("borrowed", "借入", LedgerAccountType.BORROWED, 0)
    private fun entry(action: LoanAction, cents: Long) = LedgerEntry(action.name, "u", "cash", action.entryType,
        action.label, cents, null, null, date, loanId = "loan", loanAction = action)

    @Test fun borrowingMovesCashAndDebtWithoutCreatingIncome() {
        val loan = LoanRecord("loan", LoanKind.BORROWED, "cash", 5000, loanAccountId = "borrowed", counterparty = "平台A", counterpartyType = CounterpartyType.PLATFORM)
        val entries = listOf(entry(LoanAction.BORROW, 5000))
        assertEquals(15000L, LedgerLogic.balance(cash, entries))
        assertEquals(-5000L, LedgerLogic.balance(borrowed, entries, listOf(loan)))
        assertEquals(10000L, LedgerLogic.totalAssets(listOf(cash, borrowed), entries, listOf(loan)))
        assertEquals(LedgerLogic.MonthTotals(0, 0), LedgerLogic.monthTotals(entries, YearMonth.from(date)))
    }
    @Test fun lendingAndCollectingPreserveNetAssetsAndDoNotInflateBudget() {
        val loan = LoanRecord("loan", LoanKind.LENT, "cash", 5000, loanAccountId = "lent").repay(2000, date.toString())!!
        val entries = listOf(entry(LoanAction.LEND, 5000), entry(LoanAction.COLLECT_LENT, 2000))
        assertEquals(7000L, LedgerLogic.balance(cash, entries))
        assertEquals(3000L, LedgerLogic.balance(lent, entries, listOf(loan)))
        assertEquals(10000L, LedgerLogic.totalAssets(listOf(cash, lent), entries, listOf(loan)))
        assertTrue(LedgerLogic.categoryTotals(entries, YearMonth.from(date), LedgerEntryType.EXPENSE).isEmpty())
    }
    @Test fun repaymentsHaveTypedLinksAndCorrectDirections() {
        val loan = LoanRecord("loan", LoanKind.BORROWED, null, 5000)
            .repay(1200, date.toString(), "分次还款", "p", "e", "cash")!!
        assertEquals("e", loan.repayments.single().entryId)
        assertEquals("cash", loan.repayments.single().cashAccountId)
        assertEquals(loan, LoanRecord.decode(loan.encode()))
        assertNotNull(LedgerLogic.validate(entry(LoanAction.REPAY_BORROWED, 1000).copy(type = LedgerEntryType.INCOME)))
        assertNotNull(LedgerLogic.validate(entry(LoanAction.REPAY_BORROWED, 1000).copy(loanId = null)))
    }
    @Test fun sameNamePersonAndPlatformHaveSeparateLedgers() {
        val person = LoanRecord("a", LoanKind.LENT, null, 1000, counterparty = "同名", loanAccountId = "lent")
        val platform = person.copy(id = "b", counterpartyType = CounterpartyType.PLATFORM)
        assertNotEquals(person.counterpartyKey, platform.counterpartyKey)
        val anotherLoan = person.copy(id = "c", label = "另一笔")
        assertEquals(person.counterpartyKey, anotherLoan.counterpartyKey)
    }
    @Test fun legacyBlankNamesAndHistoricalRepaymentsRemainReadable() {
        val old = LoanRecord.decode("a|LENT|cash|5000|1000|旧借款")!!
        assertEquals("旧借款", old.counterpartyName)
        assertEquals(4000L, old.remainingCents)
        assertTrue(old.repayments.isEmpty())
    }
    @Test fun loanInputCannotWrapOverflowOrTruncateFractions() {
        assertEquals(0L, com.qimingnan.couple.ui.ledger.parseCents("184467440737095516.17"))
        assertEquals(0L, com.qimingnan.couple.ui.ledger.parseCents("1.009"))
        assertEquals(12345L, com.qimingnan.couple.ui.ledger.parseCents("123.45"))
    }
    @Test fun loanAccountsCannotBeUsedAsTravelSavings() {
        assertNull(com.qimingnan.couple.data.anniversary.TravelSavings.from(lent, emptyList(), 10000))
        assertNull(com.qimingnan.couple.data.anniversary.TravelSavings.from(borrowed, emptyList(), 10000))
        assertNotNull(com.qimingnan.couple.data.anniversary.TravelSavings.from(cash, emptyList(), 10000))
    }

}
