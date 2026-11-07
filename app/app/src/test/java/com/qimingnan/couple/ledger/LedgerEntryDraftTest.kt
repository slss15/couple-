package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LedgerEntryDraftTest {
    @Test fun personalExpenseHasSixStepsAndNoCoupleFields() {
        val draft = LedgerEntryDraft(1250, LedgerEntryType.EXPENSE, "cash", category = "餐饮", detail = "午饭", note = "  一个人午饭  ")
        assertEquals(6, draft.steps.size)
        val entry = draft.toEntry("id", "me")
        assertNull(LedgerLogic.validate(entry))
        assertEquals("餐饮·午饭", entry.categoryId)
        assertEquals("一个人午饭", entry.note)
        assertNull(entry.fromAccountId)
        assertNull(entry.toAccountId)
    }
    @Test fun transferUsesFiveStepsAndDoesNotInventIncomeOrExpense() {
        val date = LocalDate.of(2026, 11, 7)
        val entry = LedgerEntryDraft(2500, LedgerEntryType.TRANSFER, "a", "b", category = "不应保存", detail = "也不保存", occurredOn = date).toEntry("x")
        assertEquals(5, LedgerEntryDraft(2500, LedgerEntryType.TRANSFER).steps.size)
        assertNull(entry.accountId)
        assertNull(entry.categoryId)
        assertNull(LedgerLogic.validate(entry))
        val accounts = listOf(LedgerAccount("a", "现金", LedgerAccountType.CASH, 10000), LedgerAccount("b", "银行卡", LedgerAccountType.BANK, 5000))
        assertEquals(15000L, LedgerLogic.totalAssets(accounts, listOf(entry)))
        assertEquals(0L, LedgerLogic.monthTotals(listOf(entry), java.time.YearMonth.from(date)).expenseCents)
        assertEquals(0L, LedgerLogic.monthTotals(listOf(entry), java.time.YearMonth.from(date)).incomeCents)
    }
    @Test fun sameAccountTransferIsRejectedByExistingRule() {
        val entry = LedgerEntryDraft(100, LedgerEntryType.TRANSFER, "a", "a").toEntry("x")
        assertEquals("转出和转入账户不能相同", LedgerLogic.validate(entry))
    }
    @Test fun backdatedEntryKeepsSelectedDate() {
        val date = LocalDate.of(2024, 2, 29)
        assertEquals(date, LedgerEntryDraft(100, LedgerEntryType.INCOME, "a", category = "红包", occurredOn = date).toEntry("x").occurredOn)
    }
}
