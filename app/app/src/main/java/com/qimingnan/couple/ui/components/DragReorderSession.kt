package com.qimingnan.couple.ui.components

/** Pixel geometry is kept separate from Compose so crossing, clamping and cancellation are testable. */
internal data class ReorderBounds(val top: Float, val height: Float) {
    val center: Float get() = top + height / 2f
}

internal fun reorderBounds(
    order: List<String>, heights: Map<String, Float>, spacing: Float,
): Map<String, ReorderBounds> {
    var top = 0f
    return order.associateWith { id ->
        val height = heights.getValue(id)
        ReorderBounds(top, height).also { top += height + spacing }
    }
}

internal data class DragReorderSession(
    val originalOrder: List<String>,
    val order: List<String>,
    val draggedId: String,
    val heights: Map<String, Float>,
    val spacing: Float,
    val top: Float,
    val horizontalOffset: Float = 0f,
) {
    fun dragBy(dx: Float, dy: Float): DragReorderSession {
        val height = heights.getValue(draggedId)
        val maxTop = (heights.values.sum() + spacing * (order.size - 1) - height).coerceAtLeast(0f)
        val nextTop = (top + dy).coerceIn(0f, maxTop)
        val center = nextTop + height / 2f
        val nextOrder = order.toMutableList()
        var index = nextOrder.indexOf(draggedId)
        var bounds = reorderBounds(nextOrder, heights, spacing)
        while (index > 0 && center <= bounds.getValue(nextOrder[index - 1]).center) {
            nextOrder.add(index - 1, nextOrder.removeAt(index))
            index--
            bounds = reorderBounds(nextOrder, heights, spacing)
        }
        while (index < nextOrder.lastIndex && center >= bounds.getValue(nextOrder[index + 1]).center) {
            nextOrder.add(index + 1, nextOrder.removeAt(index))
            index++
            bounds = reorderBounds(nextOrder, heights, spacing)
        }
        return copy(order = nextOrder, top = nextTop, horizontalOffset = horizontalOffset + dx)
    }

    companion object {
        fun start(order: List<String>, heights: Map<String, Float>, spacing: Float, y: Float): DragReorderSession? {
            if (order.size < 2 || !order.all { (heights[it] ?: 0f) > 0f }) return null
            val bounds = reorderBounds(order, heights, spacing)
            val id = order.firstOrNull { y >= bounds.getValue(it).top && y < bounds.getValue(it).top + heights.getValue(it) }
                ?: return null // Long-pressing a gap must not pick a neighbouring row.
            return DragReorderSession(order, order, id, heights, spacing, bounds.getValue(id).top)
        }
    }
}
