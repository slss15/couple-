package com.qimingnan.couple.data.ledger

import java.math.BigDecimal

/** 输入金额必须可精确转换为分；不截断小数，不让 Long 溢出后变成另一笔金额。 */
object Money {
    fun parseCents(text: String): Long? = runCatching {
        BigDecimal(text.trim()).movePointRight(2).longValueExact()
    }.getOrNull()
    fun decimal(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}
