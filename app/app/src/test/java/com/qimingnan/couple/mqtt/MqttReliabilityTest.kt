package com.qimingnan.couple.mqtt

import com.qimingnan.couple.data.mqtt.Backoff
import com.qimingnan.couple.data.mqtt.InboundFilter
import com.qimingnan.couple.data.mqtt.InboundFilter.Verdict
import com.qimingnan.couple.data.mqtt.LatencyStats
import com.qimingnan.couple.data.mqtt.MqttClientIds
import com.qimingnan.couple.data.mqtt.NetworkChangeDetector
import com.qimingnan.couple.data.mqtt.NetworkChangeDetector.Action
import com.qimingnan.couple.data.mqtt.NetworkChangeDetector.NetworkInfo
import com.qimingnan.couple.data.mqtt.NetworkChangeDetector.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun nominalDoublesAndCaps() {
        val b = Backoff(baseMillis = 1_000, maxMillis = 60_000, jitter = 0.2)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..8).map(b::nominal))
        assertEquals(60_000L, b.nominal(1_000)) // no overflow for huge attempt counts
        assertEquals(1_000L, b.nominal(0))
    }

    @Test
    fun jitterStaysWithinPlusMinus20Percent() {
        val b = Backoff(jitter = 0.2, random = Random(42))
        for (attempt in 1..12) {
            val n = b.nominal(attempt)
            repeat(2_000) {
                val d = b.delayFor(attempt)
                assertTrue("attempt $attempt: $d not in ±20% of $n", d >= (n * 0.8).toLong() && d <= (n * 1.2).toLong())
            }
        }
    }

    @Test
    fun jitterActuallySpreads() {
        val b = Backoff(jitter = 0.2, random = Random(7))
        val samples = List(1_000) { b.delayFor(6) } // nominal 32 s
        assertTrue(samples.min() < 32_000 * 0.9)
        assertTrue(samples.max() > 32_000 * 1.1)
    }

    @Test
    fun cappedDelayNeverExceeds120PercentOfMax() {
        val b = Backoff(baseMillis = 1_000, maxMillis = 60_000, jitter = 0.2, random = Random(1))
        repeat(5_000) { assertTrue(b.delayFor(50) <= 72_000) }
        assertEquals(48_000L..72_000L, b.bounds(50))
    }

    @Test
    fun zeroJitterIsDeterministic() {
        val b = Backoff(jitter = 0.0)
        assertEquals(4_000L, b.delayFor(3))
    }
}

class InboundFilterTest {
    @Test
    fun dropsDuplicatesByMessageId() {
        val f = InboundFilter()
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01A", 100))
        assertEquals(Verdict.DUPLICATE, f.offer("u1", "01A", 100))
        // A redelivery after reconnect, possibly with a different arrival time, is still the same message.
        assertEquals(Verdict.DUPLICATE, f.offer("u1", "01A", 100))
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01B", 101))
    }

    @Test
    fun olderMessageFromSameSenderIsStale() {
        val f = InboundFilter()
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01B", 200))
        assertEquals(Verdict.STALE, f.offer("u1", "01A", 100))
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01C", 300))
    }

    @Test
    fun sameTimestampOrdersByMessageId() {
        val f = InboundFilter()
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01B", 100))
        assertEquals(Verdict.STALE, f.offer("u1", "01A", 100))
        assertEquals(Verdict.ACCEPT, f.offer("u1", "01C", 100))
    }

    @Test
    fun lastWillSortsAfterItsSessionButBeforeTheNext() {
        val f = InboundFilter()
        // Session 1 starts at 1000: the will is built at 1000, "online" is published at 1005.
        assertEquals(Verdict.ACCEPT, f.offer("u1", "on1", 1_005, session = 1_000))
        assertEquals(Verdict.ACCEPT, f.offer("u1", "will1", 1_000, session = 1_000, isWill = true))
        // Session 2 (reconnect at 2000) beats the old will...
        assertEquals(Verdict.ACCEPT, f.offer("u1", "on2", 2_003, session = 2_000))
        // ...and a late redelivery of session 1's will is stale.
        assertEquals(Verdict.STALE, f.offer("u1", "will1b", 1_000, session = 1_000, isWill = true))
    }

    @Test
    fun orderIsPerSender() {
        val f = InboundFilter()
        assertEquals(Verdict.ACCEPT, f.offer("u1", "a2", 200))
        assertEquals(Verdict.ACCEPT, f.offer("u2", "b1", 100)) // other sender, older ts is fine
    }

    @Test
    fun acceptedSequenceKeepsArrivalOrder() {
        val f = InboundFilter()
        val arrivals = listOf("m1" to 1L, "m2" to 2L, "m1" to 1L, "m3" to 3L, "m2" to 2L, "m4" to 4L)
        val accepted = arrivals.filter { (id, ts) -> f.offer("u", id, ts) == Verdict.ACCEPT }.map { it.first }
        assertEquals(listOf("m1", "m2", "m3", "m4"), accepted)
    }

    @Test
    fun lruIsBounded() {
        val f = InboundFilter(capacity = 3)
        listOf("a", "b", "c", "d").forEachIndexed { i, id -> f.offer("s$i", id, i.toLong()) }
        // "a" was evicted, so it is no longer recognized as a duplicate (per-sender order still protects state).
        assertEquals(Verdict.ACCEPT, f.offer("other", "a", 0))
        assertEquals(Verdict.DUPLICATE, f.offer("s3", "d", 3))
    }

    @Test
    fun legacyIdIsDeterministic() {
        assertEquals(InboundFilter.legacyId("u", 5, "true"), InboundFilter.legacyId("u", 5, "true"))
        assertTrue(InboundFilter.legacyId("u", 5, "true") != InboundFilter.legacyId("u", 5, "false"))
    }
}

class LatencyStatsTest {
    @Test
    fun nearestRankPercentiles() {
        val v = (1L..100L).toList()
        assertEquals(50L, LatencyStats.percentile(v, 50.0))
        assertEquals(95L, LatencyStats.percentile(v, 95.0))
        assertEquals(7L, LatencyStats.percentile(listOf(7L), 95.0))
        assertNull(LatencyStats.percentile(emptyList(), 50.0))
    }

    @Test
    fun snapshotAndWindow() {
        val s = LatencyStats(window = 10)
        for (i in 1..20) s.recordEcho(sendTs = 1_000, receiveTs = 1_000L + i * 10)
        s.recordReconnect(); s.recordReconnect(); s.recordDuplicate(); s.recordStale(); s.recordNetworkChange()
        val snap = s.snapshot()
        assertEquals(10, snap.echoCount) // only the last 10 samples (110..200 ms)
        assertEquals(150L, snap.echoP50)
        assertEquals(200L, snap.echoP95)
        assertEquals(2, snap.reconnects)
        assertEquals(1, snap.duplicatesDropped)
        assertEquals(1, snap.staleDropped)
        assertEquals(1, snap.networkChanges)
        assertNull(snap.peerP50)
    }
}

class MqttClientIdsTest {
    @Test
    fun stableAndBoundToUser() {
        val user = "01JABCDEFGHJKMNPQRSTVWXYZ0"
        val dev = "01JDEVICE000000000000000000"
        val id = MqttClientIds.forDevice(user, dev)
        assertEquals(id, MqttClientIds.forDevice(user, dev))
        assertTrue(id.startsWith("a-$user-"))
        assertTrue(id.length <= MqttClientIds.MAX_LENGTH)
    }

    @Test
    fun longIdsAreTruncatedButKeepPrefix() {
        val id = MqttClientIds.forDevice("u".repeat(30), "d".repeat(60))
        assertEquals(64, id.length)
        assertTrue(id.startsWith("a-${"u".repeat(30)}-"))
    }
}

class NetworkChangeDetectorTest {
    private fun wifi(id: String = "100", validated: Boolean = true) = NetworkInfo(id, setOf(Transport.WIFI), validated)
    private fun cell(id: String = "200", validated: Boolean = true) = NetworkInfo(id, setOf(Transport.CELLULAR), validated)

    @Test
    fun firstCallbackDoesNothing() {
        assertEquals(Action.None, NetworkChangeDetector().onAvailable(wifi()))
    }

    @Test
    fun wifiToCellularForcesReconnect() {
        val d = NetworkChangeDetector()
        d.onAvailable(wifi())
        val a = d.onAvailable(cell())
        assertTrue(a is Action.Reconnect && a.force)
        // The late onLost of the old Wi-Fi network must not count as "offline".
        assertEquals(Action.None, d.onLost("100"))
    }

    @Test
    fun lostThenBackReconnects() {
        val d = NetworkChangeDetector()
        d.onAvailable(wifi())
        assertEquals(Action.Lost, d.onLost("100"))
        val a = d.onAvailable(cell())
        assertTrue(a is Action.Reconnect && a.force)
    }

    @Test
    fun startingOfflineReconnectsOnFirstNetwork() {
        val d = NetworkChangeDetector()
        d.markOffline()
        assertTrue(d.onAvailable(wifi()) is Action.Reconnect)
    }

    @Test
    fun validationIsSoftReconnectAndRepeatsAreIgnored() {
        val d = NetworkChangeDetector()
        d.onAvailable(wifi(validated = false))
        val a = d.onAvailable(wifi(validated = true))
        assertTrue(a is Action.Reconnect && !a.force)
        assertEquals(Action.None, d.onAvailable(wifi(validated = true))) // e.g. signal-strength-only updates
    }

    @Test
    fun transportChangeOnSameNetworkReconnects() {
        val d = NetworkChangeDetector()
        d.onAvailable(wifi())
        val a = d.onAvailable(NetworkInfo("100", setOf(Transport.WIFI, Transport.VPN), true))
        assertTrue(a is Action.Reconnect && a.force)
    }
}
