package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.LoanKind
import com.qimingnan.couple.data.ledger.LoanRecord
import com.qimingnan.couple.data.ledger.LoanTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 借贷记录（信用与负债）：编码/解码往返、剩余与汇总。
 * 记录先存在设置 DataStore 里，不新开表、不升数据库版本。
 */
class LoanRecordTest {

    @Test fun encodeDecodeRoundTrip() {
        val record = LoanRecord("id1", LoanKind.BORROWED, "acc1", 10_000, 3_000, "向妈妈借的")
        val decoded = LoanRecord.decode(record.encode())
        assertEquals(record, decoded)
        assertEquals(7_000L, decoded!!.remainingCents)
        assertFalse(decoded.settled)
    }

    @Test fun labelPipeIsPreservedWithoutShiftingFields() {
        val record = LoanRecord("id1", LoanKind.LENT, null, 5_000, 0, "借给|小白")
        val decoded = LoanRecord.decode(record.encode())
        assertEquals("借给|小白", decoded!!.label)
        assertNull(decoded.accountId)
    }

    @Test fun invalidStringsDecodeToNull() {
        assertNull(LoanRecord.decode("bad"))
        assertNull(LoanRecord.decode("id|BORROWED||0|0|a"))
        assertNull(LoanRecord.decode("id|BORROWED||100|-5|a"))
        assertNull(LoanRecord.decode("id|UNKNOWN||100|0|a"))
        assertNull(LoanRecord.decode("id|BORROWED||abc|0|a"))
    }

    @Test fun settledWhenFullyRepaid() {
        val record = LoanRecord("id1", LoanKind.BORROWED, null, 10_000, 10_000)
        assertEquals(0L, record.remainingCents)
        assertTrue(record.settled)
    }

    @Test fun totalsSummarizeBorrowedLentRepaidAndRemaining() {
        val records = listOf(
            LoanRecord("a", LoanKind.BORROWED, null, 10_000, 2_000),
            LoanRecord("b", LoanKind.LENT, null, 5_000, 1_000),
        )
        val summary = LoanTotals.summarize(records)
        assertEquals(10_000L, summary.borrowedCents)
        assertEquals(5_000L, summary.lentCents)
        assertEquals(3_000L, summary.repaidCents)
        assertEquals(12_000L, summary.remainingCents)
    }
    @Test fun legacyPipeRecordStillDecodes() {
        val record = LoanRecord.decode("id1|BORROWED|acc1|10000|3000|向妈妈借的")!!
        assertEquals(7000L, record.remainingCents)
        assertTrue(record.repayments.isEmpty())
    }
    @Test fun repaymentHistoryAndDueDateRoundTrip() {
        val loan = LoanRecord("a", LoanKind.LENT, "bank", 10000, label = "旅行", counterparty = "小白", dueOn = "2026-12-01")
            .repay(2000, "2026-11-07", "第一笔")!!
        assertEquals(loan, LoanRecord.decode(loan.encode()))
        assertEquals(1, loan.repayments.size)
        assertEquals("第一笔", loan.repayments.single().note)
        assertTrue(loan.overdue(java.time.LocalDate.of(2026, 12, 2)))
        assertFalse(loan.overdue(java.time.LocalDate.of(2026, 12, 1)))
    }
    @Test fun repaymentCannotBeNegativeOrExceedRemaining() {
        val loan = LoanRecord("a", LoanKind.BORROWED, null, 10000, 9000)
        assertNull(loan.repay(0, "2026-11-07"))
        assertNull(loan.repay(-1, "2026-11-07"))
        assertNull(loan.repay(1001, "2026-11-07"))
        assertNull(loan.repay(100, "not-a-date"))
        assertTrue(loan.repay(1000, "2026-11-07")!!.settled)
    }
    @Test fun repaymentNearLongLimitDoesNotOverflow() {
        val loan = LoanRecord("a", LoanKind.BORROWED, null, Long.MAX_VALUE, Long.MAX_VALUE - 100)
        assertNull(loan.repay(Long.MAX_VALUE, "2026-11-07"))
        assertEquals(Long.MAX_VALUE, loan.repay(100, "2026-11-07")!!.repaidCents)
    }

}
