package com.qimingnan.couple.pairing

import com.qimingnan.couple.ui.pairing.PAIR_CODE_LIFETIME_MILLIS
import com.qimingnan.couple.ui.pairing.formatCountdown
import com.qimingnan.couple.ui.pairing.pairCodeDeadline
import org.junit.Assert.assertEquals
import org.junit.Test

class PairCodeExpiryTest {
    private val now = 1_700_000_000_000L

    @Test fun trustsServerExpiryWhenPlausible() =
        assertEquals(now + 9 * 60_000, pairCodeDeadline(now + 9 * 60_000, now))

    @Test fun serverClockAheadFallsBackToLifetime() =
        assertEquals(now + PAIR_CODE_LIFETIME_MILLIS, pairCodeDeadline(now + 25 * 60_000, now))

    @Test fun serverClockFarBehindFallsBackToLifetime() =
        assertEquals(now + PAIR_CODE_LIFETIME_MILLIS, pairCodeDeadline(now - 60_000, now))

    @Test fun countdownFormat() {
        assertEquals("10:00", formatCountdown(600))
        assertEquals("0:09", formatCountdown(9))
    }
}
