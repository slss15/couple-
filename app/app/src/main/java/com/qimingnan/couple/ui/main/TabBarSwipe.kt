package com.qimingnan.couple.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 底栏「滑动选择」：手指按在底栏上横向滑动，滑到哪一项就切到哪一项。
 * 纯数学部分单独放这里，方便不依赖 Android UI 的单测覆盖边界值。
 */
object TabBarSwipe {
    /** 手指 x 坐标落在第几项；宽度或项数非法时回退到第一项。 */
    fun indexForX(xPx: Float, widthPx: Float, count: Int): Int {
        if (count <= 1 || widthPx <= 0f) return 0
        val slot = widthPx / count
        return (xPx / slot).toInt().coerceIn(0, count - 1)
    }
}

/**
 * 在底栏上挂横向滑动选择：按下后超过触摸阈值的横向拖动才接管，
 * 所以普通点击不受影响；跨过一项时回调一次 [onSelect]，并吃掉这次事件避免误触子项。
 */
fun Modifier.tabBarSwipe(itemCount: Int, onSelect: (Int) -> Unit): Modifier =
    pointerInput(itemCount, onSelect) {
        if (itemCount < 2) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            var current = TabBarSwipe.indexForX(down.position.x, size.width.toFloat(), itemCount)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dx = change.position.x - down.position.x
                if (!dragging && abs(dx) > viewConfiguration.touchSlop) dragging = true
                if (!dragging) continue
                val next = TabBarSwipe.indexForX(change.position.x, size.width.toFloat(), itemCount)
                if (next != current) {
                    current = next
                    onSelect(next)
                }
                change.consume()
            }
        }
    }
