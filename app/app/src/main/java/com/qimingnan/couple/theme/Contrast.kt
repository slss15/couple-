package com.qimingnan.couple.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.x 相对亮度与对比度。 */
object Contrast {
    fun relativeLuminance(argb: Long): Double {
        fun channel(c: Long): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun ratio(fg: Long, bg: Long): Double {
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    const val MIN_TEXT = 4.5
    const val MIN_ICON = 3.0
}
