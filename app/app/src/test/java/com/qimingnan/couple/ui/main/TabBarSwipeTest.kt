package com.qimingnan.couple.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TabBarSwipeTest {
    @Test fun xFallsIntoTabSlot() {
        // 宽 400、4 项：每项 100，边界落在哪一项。
        assertEquals(0, TabBarSwipe.indexForX(0f, 400f, 4))
        assertEquals(0, TabBarSwipe.indexForX(99.9f, 400f, 4))
        assertEquals(1, TabBarSwipe.indexForX(100f, 400f, 4))
        assertEquals(2, TabBarSwipe.indexForX(250f, 400f, 4))
        assertEquals(3, TabBarSwipe.indexForX(399f, 400f, 4))
    }

    @Test fun outOfBoundsIsClamped() {
        assertEquals(0, TabBarSwipe.indexForX(-30f, 400f, 4))
        assertEquals(3, TabBarSwipe.indexForX(900f, 400f, 4))
    }

    @Test fun degenerateInputFallsBackToFirst() {
        assertEquals(0, TabBarSwipe.indexForX(120f, 400f, 1))
        assertEquals(0, TabBarSwipe.indexForX(120f, 400f, 0))
        assertEquals(0, TabBarSwipe.indexForX(120f, 0f, 4))
    }
}
