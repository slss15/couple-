package com.qimingnan.couple.ui.components

import org.junit.Assert.*
import org.junit.Test

class DragReorderSessionTest {
    private val ids = listOf("a", "b", "c", "d")
    private val heights = ids.associateWith { 100f }
    private fun start(y: Float) = DragReorderSession.start(ids, heights, 8f, y)!!

    @Test fun longPressDoesNotChangeOrderBeforeMoving() {
        val drag = start(158f)
        assertEquals("b", drag.draggedId)
        assertEquals(ids, drag.order)
        assertEquals(108f, drag.top, 0f)
    }
    @Test fun gapsEmptyAndSingleItemDoNotStartDragging() {
        assertNull(DragReorderSession.start(ids, heights, 8f, 104f))
        assertNull(DragReorderSession.start(emptyList(), emptyMap(), 8f, 0f))
        assertNull(DragReorderSession.start(listOf("a"), heights, 8f, 50f))
        assertNull(DragReorderSession.start(ids, emptyMap(), 8f, 50f))
    }
    @Test fun crossesMultipleRowsInOneMoveAndPreservesOriginalForCancel() {
        val drag = start(50f).dragBy(12f, 260f)
        assertEquals(listOf("b", "c", "a", "d"), drag.order)
        assertEquals(ids, drag.originalOrder)
        assertEquals(12f, drag.horizontalOffset, 0f)
        assertEquals(260f, drag.top, 0f)
    }
    @Test fun movingUpClampsAtFirstSlotAndCanReturnToOriginalOrder() {
        val drag = start(374f).dragBy(0f, -1000f)
        assertEquals(listOf("d", "a", "b", "c"), drag.order)
        assertEquals(0f, drag.top, 0f)
        assertEquals(ids, drag.dragBy(0f, 1000f).order)
    }
    @Test fun movingDownClampsAtLastSlot() {
        val drag = start(50f).dragBy(0f, 1000f)
        assertEquals(listOf("b", "c", "d", "a"), drag.order)
        assertEquals(324f, drag.top, 0f)
    }
    @Test fun otherRowsOnlyMoveWhenTheirMidpointIsCrossed() {
        val drag = start(50f).dragBy(0f, 107f)
        assertEquals(ids, drag.order)
        assertEquals(listOf("b", "a", "c", "d"), drag.dragBy(0f, 2f).order)
    }
    @Test fun unequalHeightsRemainStableWhenFingerStops() {
        val unequal = mapOf("a" to 48f, "b" to 240f, "c" to 72f, "d" to 180f)
        ids.forEach { id ->
            val bounds = reorderBounds(ids, unequal, 12f)
            for (dy in -600..600 step 5) {
                val initial = DragReorderSession.start(ids, unequal, 12f, bounds.getValue(id).center)!!
                val moved = initial.dragBy(0f, dy.toFloat())
                assertEquals(ids.toSet(), moved.order.toSet())
                assertEquals(ids.size, moved.order.size)
                assertEquals("No oscillation for $id at $dy", moved.order, moved.dragBy(0f, 0f).order)
            }
        }
    }
}
