package com.qimingnan.couple.anniversary

import com.qimingnan.couple.data.anniversary.*
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.ui.anniversary.AnniversaryExtras
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AnniversaryExtrasTest {
    private fun event(month: Int = 11, day: Int = 7) = AnniversaryEvent("a", "纪念日", AnniversaryType.LOVE, AnniversaryCalendar.SOLAR, month, day, year = 2026)
    @Test fun validatesDatesWithoutGuessingInvalidDays() {
        assertNull(AnniversaryLogic.validationError(event()))
        assertNull(AnniversaryLogic.validationError(event(2, 29)))
        assertNotNull(AnniversaryLogic.validationError(event(2, 30)))
        assertNotNull(AnniversaryLogic.validationError(event(13, 1)))
        assertNotNull(AnniversaryLogic.validationError(event().copy(title = " ")))
    }
    @Test fun rejectsUnconvertibleLunarYearAndMissingOneTimeYear() {
        assertNotNull(AnniversaryLogic.validationError(event().copy(calendar = AnniversaryCalendar.LUNAR, year = 2050)))
        assertNotNull(AnniversaryLogic.validationError(event().copy(recurring = false, year = null)))
        assertNotNull(AnniversaryLogic.validationError(event().copy(calendar = AnniversaryCalendar.LUNAR, day = 31)))
    }
    @Test fun checkinsAreScopedToEventAndOccurrence() {
        val extras = AnniversaryExtras(setOf("a:2026-11-07", "a:2025-11-07", "b:2026-11-07"))
        assertEquals(2, extras.checkinCount("a"))
        assertTrue(extras.checkedIn("a", "2026-11-07"))
        assertFalse(extras.checkedIn("a", "2026-11-08"))
    }
    @Test fun travelUsesRealAccountBalanceAndDoesNotChangeLedger() {
        val account = LedgerAccount("a", "旅行卡", LedgerAccountType.BANK, 10000)
        val entries = listOf(LedgerEntryDraft(3000, LedgerEntryType.EXPENSE, "a", category = "交通").toEntry("e"))
        val savings = TravelSavings.from(account, entries, 20000)!!
        assertEquals(7000L, savings.savedCents)
        assertEquals(13000L, savings.remainingCents)
        assertEquals(0.35f, savings.progress, 0.0001f)
        assertEquals(7000L, LedgerLogic.balance(account, entries))
    }
    @Test fun deletedAccountIsNotPretendedToHaveZeroSavings() { assertNull(TravelSavings.from(null, emptyList(), 100)) }
    @Test fun negativeBalanceAndReachedTargetAreHandled() {
        val debt = TravelSavings(-200, 1000)
        assertEquals(-200L, debt.balanceCents)
        assertEquals(0L, debt.savedCents)
        assertEquals(0f, debt.progress)
        val achieved = TravelSavings(3000, 1000)
        assertEquals(0L, achieved.remainingCents)
        assertEquals(1f, achieved.progress)
    }
}
