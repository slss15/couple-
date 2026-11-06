package com.qimingnan.couple.data.ledger

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额算式求值：内置数字键盘里的 ÷ × − + 运算符。
 *
 * 纯逻辑，不碰 Android：算式如 "32+10×2" 先乘除后加减，从左到右算同级；
 * 结果保留两位小数（四舍五入），除数为 0、算式不完整、字符非法时返回 null，
 * 调用方保持键盘打开并提示，不写库。金额本身仍由调用方校验必须大于 0
 * 才能下一步/保存，算式只是输金额的另一种方式。
 */
object AmountExpression {
    private val OPS = setOf('+', '-', '×', '÷')

    /** "32+10" -> 42.00；"12.5×2" -> 25.00；非法返回 null。 */
    fun evaluate(expr: String): BigDecimal? {
        val s = expr.replace(" ", "").replace('x', '×').replace('/', '÷')
        if (s.isEmpty()) return null
        if (s.last() in OPS || s.last() == '.') return null
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in s) {
            when {
                c.isDigit() || c == '.' -> cur.append(c)
                c in OPS -> {
                    // 开头的减号算负数的一部分，其余位置连续两个运算符非法。
                    if (cur.isEmpty() && tokens.isEmpty() && c == '-') {
                        cur.append(c)
                        continue
                    }
                    if (cur.isEmpty()) return null
                    tokens.add(cur.toString())
                    cur.clear()
                    tokens.add(c.toString())
                }
                else -> return null
            }
        }
        if (cur.isEmpty()) return null
        tokens.add(cur.toString())
        val numbers = tokens.filterIndexed { i, _ -> i % 2 == 0 }.map {
            runCatching { BigDecimal(it) }.getOrNull() ?: return null
        }
        val ops = tokens.filterIndexed { i, _ -> i % 2 == 1 }
        // 先算 × ÷，再算 + −。
        val values = numbers.toMutableList()
        val operators = ops.toMutableList()
        var i = 0
        while (i < operators.size) {
            val op = operators[i]
            if (op == "×" || op == "÷") {
                if (op == "÷" && values[i + 1].compareTo(BigDecimal.ZERO) == 0) return null
                val r = if (op == "×") values[i].multiply(values[i + 1])
                else values[i].divide(values[i + 1], 10, RoundingMode.HALF_UP)
                values[i] = r
                values.removeAt(i + 1)
                operators.removeAt(i)
            } else {
                i++
            }
        }
        var result = values[0]
        for (j in operators.indices) {
            result = if (operators[j] == "+") result.add(values[j + 1]) else result.subtract(values[j + 1])
        }
        return result.setScale(2, RoundingMode.HALF_UP)
    }

    /** 键盘上一个数字键能不能追加：当前这段数字最多两位小数。 */
    fun canAppendDigit(expr: String, digit: Char): Boolean {
        if (expr.length >= 16) return false
        val tail = expr.substringAfterLast('+').substringAfterLast('-')
            .substringAfterLast('×').substringAfterLast('÷')
        if (!tail.contains('.')) return true
        return tail.substringAfter('.').length < 2
    }

    /** 小数点能不能追加：当前这段数字里还没有小数点。 */
    fun canAppendDot(expr: String): Boolean {
        val tail = expr.substringAfterLast('+').substringAfterLast('-')
            .substringAfterLast('×').substringAfterLast('÷')
        return !tail.contains('.')
    }

    /** 运算符能不能追加：算式非空、末尾不是运算符也不是小数点。 */
    fun canAppendOperator(expr: String): Boolean =
        expr.isNotEmpty() && expr.last() !in OPS && expr.last() != '.'

    /** 快捷加法（+10/+50/+100）：先把算式算出来再加，算不出就按原样加到末尾数字上。 */
    fun addAmount(expr: String, delta: BigDecimal): String {
        val base = evaluate(expr) ?: runCatching { BigDecimal(expr) }.getOrNull() ?: BigDecimal.ZERO
        return base.add(delta).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    /** 整百凑整：向上取到下一个整百，0 则取 100。 */
    fun roundUpToHundred(expr: String): String {
        val base = evaluate(expr) ?: BigDecimal.ZERO
        var v = base.divide(BigDecimal(100), 0, RoundingMode.CEILING).multiply(BigDecimal(100))
        if (v.compareTo(BigDecimal.ZERO) == 0) v = BigDecimal(100)
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}
