package com.qimingnan.couple.ui.main

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

data class SecondaryTabItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

data class SecondaryTabMenu(
    val items: List<SecondaryTabItem>,
    val selectedIndex: Int,
)

/** Pure drag math so the long-press selection can be unit-tested without Android UI. */
object SecondaryTabDrag {
    /** Hold for one second before the secondary-page overlay appears. */
    const val LONG_PRESS_MS = 1_000L
    const val STEP_DP = 56f

    fun indexForDelta(initialIndex: Int, deltaPx: Float, stepPx: Float, count: Int): Int {
        if (count <= 1 || stepPx <= 0f) return initialIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        val steps = (deltaPx / stepPx).roundToInt()
        return (initialIndex + steps).coerceIn(0, count - 1)
    }
}

/**
 * Long-press the current tab icon, then keep dragging horizontally. The overlay is shown by
 * [onOpen], updated by [onMove], and committed on release by [onFinish]. Releasing without
 * crossing a step keeps the current secondary page.
 */
fun Modifier.secondaryTabLongPress(
    enabled: Boolean,
    itemCount: Int,
    initialIndex: Int,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onFinish: (Int) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(enabled, itemCount, initialIndex) {
    if (!enabled || itemCount < 2) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var opened = false
        var selected = initialIndex.coerceIn(0, itemCount - 1)
        val deadline = SystemClock.uptimeMillis() + SecondaryTabDrag.LONG_PRESS_MS

        while (true) {
            val event = if (opened) {
                awaitPointerEvent()
            } else {
                val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                // withTimeoutOrNull keeps the pointer scope alive; a null result means the finger is still down
                // after the one-second hold, so the overlay can open without lifting.
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            }
            if (event == null) {
                opened = true
                selected = initialIndex.coerceIn(0, itemCount - 1)
                onOpen()
                continue
            }

            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (opened) onCancel()
                break
            }
            if (!change.pressed) {
                if (opened) {
                    change.consume()
                    onFinish(selected)
                }
                break
            }
            if (change.isConsumed) {
                if (opened) onCancel()
                break
            }
            if (opened) {
                val next = SecondaryTabDrag.indexForDelta(
                    initialIndex = initialIndex,
                    deltaPx = change.position.x - down.position.x,
                    stepPx = SecondaryTabDrag.STEP_DP.dp.toPx(),
                    count = itemCount,
                )
                if (next != selected) {
                    selected = next
                    onMove(next)
                }
                change.consume()
            }
        }
    }
}
