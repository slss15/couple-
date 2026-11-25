package com.qimingnan.couple.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondaryTabDragTest {
    @Test fun zeroDragKeepsCurrentPage() {
        assertEquals(1, SecondaryTabDrag.indexForDelta(1, 0f, 56f, 3))
    }

    @Test fun dragMovesOneStepPerThreshold() {
        assertEquals(2, SecondaryTabDrag.indexForDelta(1, 80f, 56f, 3))
        assertEquals(0, SecondaryTabDrag.indexForDelta(1, -80f, 56f, 3))
        assertEquals(2, SecondaryTabDrag.indexForDelta(1, 200f, 56f, 3))
    }

    @Test fun selectionIsClampedToAvailablePages() {
        assertEquals(0, SecondaryTabDrag.indexForDelta(0, -500f, 56f, 3))
        assertEquals(2, SecondaryTabDrag.indexForDelta(2, 500f, 56f, 3))
        assertEquals(0, SecondaryTabDrag.indexForDelta(0, 100f, 56f, 1))
    }
}
