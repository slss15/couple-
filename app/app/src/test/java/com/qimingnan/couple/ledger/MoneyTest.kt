package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.Money
import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun yuanToCentsIsExact() {
        assertEquals(123L, Money.parseCents("1.23"))
        assertEquals(120L, Money.parseCents("1.2"))
        assertEquals(100L, Money.parseCents(" 1.00 "))
        assertEquals(-150L, Money.parseCents("-1.50"))
    }
    @Test fun neverSilentlyTruncatesFractionalCentsOrOverflows() {
        assertNull(Money.parseCents("1.239"))
        assertNull(Money.parseCents("92233720368547758.08"))
        assertNull(Money.parseCents("99999999999999999999999"))
        assertEquals(Long.MAX_VALUE, Money.parseCents("92233720368547758.07"))
    }
    @Test fun formattingHandlesNegativeAndLongMinWithoutAbsOverflow() {
        assertEquals("-1.50", Money.decimal(-150))
        assertEquals("-92233720368547758.08", Money.decimal(Long.MIN_VALUE))
    }
}
