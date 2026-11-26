package com.qimingnan.couple.ui.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerSwipeTest {
    private val width = 400f

    // 记账页现在只有两个二级页：统计(0) / 账户(1)，记一笔是抽屉不是页。
    private val count = LedgerSubTab.entries.size

    @Test
    fun countIsTwoSubPages() {
        assertEquals(2, count)
        assertEquals(LedgerSubTab.Stats, LedgerSubTab.entries[0])
        assertEquals(LedgerSubTab.Accounts, LedgerSubTab.entries[1])
    }

    @Test
    fun smallDragDoesNothing() {
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(-30f, width, 0, count))
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(0f, width, 0, count))
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(30f, width, 1, count))
    }

    @Test
    fun dragSwitchesBetweenSubPages() {
        // 左滑（负位移）到下一页，右滑到上一页。
        assertEquals(LedgerSwipeResult.MoveTo(1), LedgerSwipe.decide(-120f, width, 0, count))
        assertEquals(LedgerSwipeResult.MoveTo(0), LedgerSwipe.decide(120f, width, 1, count))
    }

    @Test
    fun boundaryNeedsABigSwipeToChangeTab() {
        // 在最后一页继续左滑：小幅不动，大幅切下一个底栏 Tab。
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(-100f, width, 1, count))
        assertEquals(LedgerSwipeResult.SwitchTab(1), LedgerSwipe.decide(-200f, width, 1, count))
        // 在第一页继续右滑：同样规则。
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(100f, width, 0, count))
        assertEquals(LedgerSwipeResult.SwitchTab(-1), LedgerSwipe.decide(200f, width, 0, count))
    }

    @Test
    fun degenerateInputIsSafe() {
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(-200f, 0f, 0, count))
        assertEquals(LedgerSwipeResult.None, LedgerSwipe.decide(-200f, width, 0, 1))
    }
}
