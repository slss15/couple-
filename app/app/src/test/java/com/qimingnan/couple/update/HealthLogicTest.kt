package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.HealthLogic
import com.qimingnan.couple.data.update.HealthLogic.Pending
import com.qimingnan.couple.data.update.HealthLogic.StartAction
import com.qimingnan.couple.data.update.HealthReport
import com.qimingnan.couple.core.CoupleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLogicTest {
    private val now = 1_000_000_000L
    private val p = Pending(from = 100, to = 101, at = now - 60_000)

    @Test
    fun nothingPending() = assertEquals(StartAction.NONE, HealthLogic.onStart(null, 101, null, now))

    @Test
    fun newVersionRunningIsWatched() = assertEquals(StartAction.WATCH, HealthLogic.onStart(p, 101, null, now))

    @Test
    fun crashMarkerOfThisVersionIsReported() {
        assertEquals(StartAction.REPORT_CRASH, HealthLogic.onStart(p, 101, 101, now))
        // A stale marker for another version does not count.
        assertEquals(StartAction.WATCH, HealthLogic.onStart(p, 101, 99, now))
    }

    @Test
    fun installNotDoneYetKeepsPendingUntilStale() {
        assertEquals(StartAction.NONE, HealthLogic.onStart(p, 100, null, now))
        assertEquals(StartAction.CLEAR, HealthLogic.onStart(p, 100, null, p.at + HealthLogic.STALE_PENDING_MS + 1))
        assertEquals(StartAction.CLEAR, HealthLogic.onStart(p, 100, null, p.at - 1)) // clock went backwards
    }

    @Test
    fun newerVersionInstalledElsewhereClears() = assertEquals(StartAction.CLEAR, HealthLogic.onStart(p, 102, null, now))

    @Test
    fun crashDetailHasClassNamesOnly() {
        val e = IllegalStateException("secret user message", RuntimeException("more secrets"))
        val d = HealthLogic.crashDetail(e)
        assertTrue(d, d.contains("java.lang.IllegalStateException"))
        assertTrue(d, d.contains("java.lang.RuntimeException"))
        assertFalse(d, d.contains("secret"))
    }

    @Test
    fun reportSerializesContractFields() {
        val json = CoupleJson.encodeToString(
            HealthReport.serializer(),
            HealthReport(101, 100, "OK", true, null, protocolVersion = 2, minPeerProtocol = 1, sdkInt = 34),
        )
        for (k in listOf("\"versionCode\":101", "\"fromVersionCode\":100", "\"status\":\"OK\"", "\"protocolVersion\":2", "\"sdkInt\":34")) {
            assertTrue(json, json.contains(k))
        }
    }
}
