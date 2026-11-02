package com.qimingnan.couple.chat

import com.qimingnan.couple.data.chat.TypingEvent
import com.qimingnan.couple.data.chat.TypingThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {
    @Test fun `typing true is throttled to one per 3 seconds`() {
        val th = TypingThrottle()
        assertTrue(th.onTextChanged(0))
        assertFalse(th.onTextChanged(1_000))
        assertFalse(th.onTextChanged(2_999))
        assertTrue(th.onTextChanged(3_000))
        assertFalse(th.onTextChanged(4_000))
    }

    @Test fun `typing false only after a true was sent, then resets throttle`() {
        val th = TypingThrottle()
        assertFalse(th.onStopped())
        assertTrue(th.onTextChanged(0))
        assertTrue(th.onStopped())
        assertFalse(th.onStopped())
        assertTrue(th.onTextChanged(500)) // after a stop the next keystroke publishes again
    }

    @Test fun `payload round trip and bad payloads`() {
        val e = TypingEvent("U1", true, 42)
        assertEquals(e, TypingEvent.parse(e.toJson()))
        assertEquals(TypingEvent("U2", false, 7), TypingEvent.parse("""{"userId":"U2","typing":false,"ts":7,"extra":1}"""))
        assertNull(TypingEvent.parse("""{"userId":"U2"}"""))
        assertNull(TypingEvent.parse("garbage"))
    }

    @Test fun `timing constants match the contract`() {
        assertEquals(3_000L, TypingThrottle.THROTTLE_MS)
        assertEquals(5_000L, TypingThrottle.IDLE_MS)
        assertEquals(6_000L, TypingThrottle.STALE_MS)
    }
}
