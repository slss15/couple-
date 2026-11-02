package com.qimingnan.couple.pairing

import com.qimingnan.couple.data.couple.PairEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairEventTest {
    @Test fun `parses the three event kinds`() {
        assertEquals(PairEvent.Paired("C1"), PairEvent.parse("""{"kind":"paired","coupleId":"C1"}"""))
        assertEquals(
            PairEvent.UnpairRequested("R1", "U1"),
            PairEvent.parse("""{"kind":"unpair_requested","requestId":"R1","by":"U1"}"""),
        )
        assertEquals(PairEvent.Unpaired, PairEvent.parse("""{"kind":"unpaired"}"""))
        assertEquals(PairEvent.Unpaired, PairEvent.parse("""{"kind":"unpaired","coupleId":"C1"}"""))
    }

    @Test fun `ignores unknown or incomplete payloads`() {
        assertNull(PairEvent.parse("""{"kind":"paired"}"""))
        assertNull(PairEvent.parse("""{"kind":"unpair_requested","requestId":"R1"}"""))
        assertNull(PairEvent.parse("""{"kind":"future_kind"}"""))
        assertNull(PairEvent.parse("[]"))
        assertNull(PairEvent.parse("not json"))
    }
}
