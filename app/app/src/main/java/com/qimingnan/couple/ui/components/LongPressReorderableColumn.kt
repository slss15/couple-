package com.qimingnan.couple.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.theme.LocalCoupleTokens
import kotlin.math.roundToInt

/**
 * A non-lazy reorder group inside an existing scrolling screen. Press anywhere on a row to lift it;
 * ordinary taps and pre-long-press scrolling still belong to its children/the parent scroll container.
 * Preview is local, a completed drop is saved once, and cancellation never saves.
 * onOrderChange must invoke onSaved on the main thread, including on persistence failure.
 */
@Composable
fun <T> LongPressReorderableColumn(
    items: List<T>,
    itemKey: (T) -> String,
    onOrderChange: (order: List<String>, onSaved: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    scrollState: ScrollState? = null,
    itemContent: @Composable (item: T, index: Int) -> Unit,
) {
    val ids = items.map(itemKey)
    require(ids.distinct().size == ids.size) { "Reorderable items must have unique keys" }
    // Membership changes (deletion, creation, account switch) cancel the gesture and reset geometry.
    // Order-only changes do not recreate the pointer input or lose an in-flight gesture.
    key(ids.toSet()) {
        val t = LocalCoupleTokens.current
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val gap = with(density) { spacing.roundToPx().toFloat() }
        val edge = with(density) { 56.dp.toPx() }
        val speed = with(density) { 640.dp.toPx() }
        val shadow = with(density) { 12.dp.toPx() }
        val baseOrder = remember { ids }
        val heights = remember { mutableStateMapOf<String, Float>() }
        var displayedOrder by remember { mutableStateOf(ids) }
        var session by remember { mutableStateOf<DragReorderSession?>(null) }
        var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
        var saveRevision by remember { mutableStateOf(0) }
        var saveFailed by remember { mutableStateOf(false) }
        var pointerY by remember { mutableStateOf(0f) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        var visibleBounds by remember { mutableStateOf(Rect.Zero) }
        val latestIds by rememberUpdatedState(ids)
        val latestSave by rememberUpdatedState(onOrderChange)
        val itemById = items.associateBy(itemKey)
        val measured = baseOrder.all { (heights[it] ?: 0f) > 0f }
        val baseBounds = if (measured) reorderBounds(baseOrder, heights, gap) else emptyMap()
        val previewOrder = session?.order ?: displayedOrder
        val targetBounds = if (measured) reorderBounds(previewOrder, heights, gap) else emptyMap()

        LaunchedEffect(ids) {
            // Ignore an older DataStore emission while a newer optimistic drop is awaiting acknowledgement.
            if (pendingOrder == null || pendingOrder == ids) {
                if (session == null) displayedOrder = ids
                if (pendingOrder == ids) pendingOrder = null
            }
        }
        LaunchedEffect(heights.toMap(), gap) {
            val drag = session
            if (drag != null && (drag.heights != heights.toMap() || drag.spacing != gap)) session = null
        }
        LaunchedEffect(session?.draggedId, scrollState) {
            if (session == null || scrollState == null) return@LaunchedEffect
            var previousFrame = withFrameNanos { it }
            while (session != null) {
                val now = withFrameNanos { it }
                val seconds = ((now - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                previousFrame = now
                val drag = session ?: break
                val pointerInWindow = origin.y + pointerY
                val totalHeight = drag.heights.values.sum() + gap * (drag.order.size - 1)
                // Scroll only towards clipped rows, not merely towards the end of a fully visible group.
                val pressure = when {
                    visibleBounds.height <= 0f -> 0f
                    visibleBounds.top > origin.y + 1f && pointerInWindow < visibleBounds.top + edge ->
                        -((visibleBounds.top + edge - pointerInWindow) / edge).coerceIn(0f, 1f)
                    visibleBounds.bottom < origin.y + totalHeight - 1f && pointerInWindow > visibleBounds.bottom - edge ->
                        ((pointerInWindow - visibleBounds.bottom + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (pressure != 0f) {
                    val consumed = scrollState.scrollBy(pressure * speed * seconds)
                    // The content moves underneath a stationary finger; compensate in list coordinates.
                    pointerY += consumed
                    session = session?.dragBy(0f, consumed)
                }
            }
        }

        val startDrag by rememberUpdatedState<(Offset) -> Unit>({ point ->
            val next = DragReorderSession.start(displayedOrder, heights.toMap(), gap, point.y)
            if (next != null) {
                saveFailed = false
                pointerY = point.y
                session = next
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        })
        val dragBy by rememberUpdatedState<(Offset) -> Unit>({ delta ->
            pointerY += delta.y
            session = session?.dragBy(delta.x, delta.y)
        })
        val cancelDrag by rememberUpdatedState<() -> Unit>({ session = null })
        val finishDrag by rememberUpdatedState<() -> Unit>({
            val completed = session
            session = null
            if (completed != null && completed.order != completed.originalOrder) {
                displayedOrder = completed.order
                pendingOrder = completed.order
                val revision = ++saveRevision
                latestSave(completed.order) { saved ->
                    if (!saved && revision == saveRevision) {
                        session = null
                        displayedOrder = latestIds
                        pendingOrder = null
                        saveFailed = true
                    }
                }
            }
        })

        Layout(
            modifier = modifier.fillMaxWidth()
                .onGloballyPositioned { origin = it.positionInWindow(); visibleBounds = it.boundsInWindow() }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startDrag(it) },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { cancelDrag() },
                        onDrag = { change, amount -> change.consume(); dragBy(amount) },
                    )
                },
            content = {
                baseOrder.forEach { id ->
                    key(id) {
                        val dragging = session?.draggedId == id
                        val baseTop = baseBounds[id]?.top ?: 0f
                        val targetY = (if (dragging) session?.top else targetBounds[id]?.top)?.minus(baseTop) ?: 0f
                        val targetX = if (dragging) session?.horizontalOffset ?: 0f else 0f
                        val y by animateFloatAsState(targetY, if (dragging) snap() else tween(180), label = "reorder-y")
                        val x by animateFloatAsState(targetX, if (dragging) snap() else tween(180), label = "reorder-x")
                        val lift by animateFloatAsState(if (dragging) 1f else 0f, tween(120), label = "reorder-lift")
                        Box(
                            Modifier.fillMaxWidth().sizeIn(minHeight = t.spacing.minTouchTarget)
                                .onSizeChanged { heights[id] = it.height.toFloat() }
                                .testTag("reorder-item-$id")
                                .semantics { stateDescription = if (dragging) "正在拖动" else "长按拖动调整顺序" }
                                .graphicsLayer {
                                    translationY = if (dragging) targetY else y
                                    translationX = if (dragging) targetX else x
                                    scaleX = 1f + lift * 0.02f
                                    scaleY = 1f + lift * 0.02f
                                    shadowElevation = shadow * lift
                                    shape = t.shapes.large
                                    clip = false
                                }
                                .background(t.colors.surfaceContainer, t.shapes.large),
                        ) { itemContent(itemById.getValue(id), previewOrder.indexOf(id)) }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            val gapPx = gap.roundToInt()
            val height = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, constraints.constrainHeight(height)) {
                var top = 0
                placeables.forEachIndexed { index, placeable ->
                    // Draw the lifted row last, without changing any child's composition/gesture identity.
                    placeable.placeRelative(0, top, zIndex = if (baseOrder[index] == session?.draggedId) 1f else 0f)
                    top += placeable.height + gapPx
                }
            }
        }
        if (saveFailed) Text("顺序保存失败，已恢复原顺序，请重试。", style = t.typography.caption, color = t.colors.error)
    }
}
