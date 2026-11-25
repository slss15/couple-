package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.ServerOutcome
import com.qimingnan.couple.data.update.unwrapServerOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateRepositoryTest {
    @Test
    fun successfulServerOutcomeKeepsDefinitiveResult() {
        val outcome = unwrapServerOutcome(Result.success(ServerOutcome.Definitive("服务器结果")))

        assertTrue(outcome is ServerOutcome.Definitive)
        assertEquals("服务器结果", outcome.message)
    }

    @Test
    fun failedServerOutcomeFallsBackWithReason() {
        val outcome = unwrapServerOutcome(Result.failure(IOException("boom")))

        assertTrue(outcome is ServerOutcome.Fallback)
        assertEquals("服务器检查异常：boom", outcome.message)
    }
}
