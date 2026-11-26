package com.qimingnan.couple.ui.ledger

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** 记账页左右滑动的判定结果。 */
sealed interface LedgerSwipeResult {
    data object None : LedgerSwipeResult
    /** 切到相邻的二级页（统计 / 账户）。 */
    data class MoveTo(val index: Int) : LedgerSwipeResult
    /** 已经在边界上，大幅滑动切到相邻的底栏 Tab；delta 为 -1 / +1。 */
    data class SwitchTab(val delta: Int) : LedgerSwipeResult
}

/**
 * 记账页的横向滑动规则，纯函数，便于单测：
 * - 位移不到 [PAGE_FRACTION] 不动（回弹）；
 * - 还能换二级页就换二级页；
 * - 已经到头/到尾，位移超过 [TAB_FRACTION]（"大幅滑动"）才切底栏 Tab。
 */
object LedgerSwipe {
    const val PAGE_FRACTION = 0.16f
    const val TAB_FRACTION = 0.40f

    fun decide(dragPx: Float, widthPx: Float, index: Int, count: Int): LedgerSwipeResult {
        if (widthPx <= 0f || count <= 1) return LedgerSwipeResult.None
        val fraction = dragPx / widthPx
        if (abs(fraction) < PAGE_FRACTION) return LedgerSwipeResult.None
        // 左滑（负位移）= 下一页，右滑 = 上一页。
        val delta = if (fraction < 0f) 1 else -1
        val target = index + delta
        if (target in 0 until count) return LedgerSwipeResult.MoveTo(target)
        return if (abs(fraction) >= TAB_FRACTION) LedgerSwipeResult.SwitchTab(delta) else LedgerSwipeResult.None
    }
}

/**
 * 挂在记账页内容上的横向滑动：先等横向位移超过触摸阈值才接管，
 * 竖直滚动交给列表；横向拖动过程吃掉事件，避免误触列表项。
 */
fun Modifier.ledgerSwipe(
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onSwitchTab: (Int) -> Unit,
): Modifier = pointerInput(index, count) {
    if (count <= 1) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragging = false
        var total = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!dragging && change.isConsumed) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!dragging) {
                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx)) break
                if (abs(dx) > viewConfiguration.touchSlop) dragging = true
            }
            if (dragging) {
                total = dx
                change.consume()
            }
        }
        if (!dragging) return@awaitEachGesture
        when (val result = LedgerSwipe.decide(total, size.width.toFloat(), index, count)) {
            is LedgerSwipeResult.MoveTo -> onMove(result.index)
            is LedgerSwipeResult.SwitchTab -> onSwitchTab(result.delta)
            LedgerSwipeResult.None -> Unit
        }
    }
}
