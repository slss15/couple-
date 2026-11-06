package com.qimingnan.couple.ledger

import com.qimingnan.couple.data.ledger.AmountExpression
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置数字键盘的算式求值：先乘除后加减，结果两位小数。
 * 算式只是输金额的另一种方式，金额本身仍由调用方校验必须大于 0。
 */
class AmountExpressionTest {

    @Test fun addAndMultiplyPrecedence() {
        assertEquals(BigDecimal("42.00"), AmountExpression.evaluate("32+10"))
        assertEquals(BigDecimal("52.00"), AmountExpression.evaluate("32+10×2"))
        assertEquals(BigDecimal("25.00"), AmountExpression.evaluate("12.5×2"))
        assertEquals(BigDecimal("7.00"), AmountExpression.evaluate("10-6÷2"))
    }

    @Test fun divisionRoundsToTwoDecimals() {
        assertEquals(BigDecimal("3.33"), AmountExpression.evaluate("10÷3"))
        assertEquals(BigDecimal("2.50"), AmountExpression.evaluate("10÷4"))
    }

    @Test fun divideByZeroAndIncompleteAreNull() {
        assertNull(AmountExpression.evaluate("10÷0"))
        assertNull(AmountExpression.evaluate("32+"))
        assertNull(AmountExpression.evaluate("32×"))
        assertNull(AmountExpression.evaluate(""))
        assertNull(AmountExpression.evaluate("32a"))
    }

    @Test fun twoDecimalLimitPerSegment() {
        assertFalse(AmountExpression.canAppendDigit("1.23", '4'))
        assertTrue(AmountExpression.canAppendDigit("1.2", '3'))
        assertTrue(AmountExpression.canAppendDigit("32+1.23", '4').not())
        assertTrue(AmountExpression.canAppendDigit("32+1.2", '3'))
        assertFalse(AmountExpression.canAppendDot("1.2"))
        assertTrue(AmountExpression.canAppendDot("32+1"))
        assertFalse(AmountExpression.canAppendDot("32+1.5"))
    }

    @Test fun quickAddAndRoundUp() {
        assertEquals("42.00", AmountExpression.addAmount("32", BigDecimal(10)))
        assertEquals("50.00", AmountExpression.addAmount("", BigDecimal(50)))
        assertEquals("100.00", AmountExpression.roundUpToHundred("32"))
        assertEquals("200.00", AmountExpression.roundUpToHundred("150"))
        assertEquals("200.00", AmountExpression.roundUpToHundred("200"))
        assertEquals("100.00", AmountExpression.roundUpToHundred(""))
    }
}
