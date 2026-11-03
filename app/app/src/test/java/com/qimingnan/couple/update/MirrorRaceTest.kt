package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.ApkDownloader
import com.qimingnan.couple.data.update.Mirror
import com.qimingnan.couple.data.update.MirrorRace
import com.qimingnan.couple.data.update.MonoClock
import com.qimingnan.couple.data.update.Probe
import com.qimingnan.couple.data.update.RangeResponse
import com.qimingnan.couple.data.update.RangeTransport
import com.qimingnan.couple.data.update.StallDetector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest

/** Race selection + failover with a fake clock and a fake transport (contract "Auto-update → mirrors"). */
class MirrorRaceTest {
    private class FakeClock(var now: Long = 0) : MonoClock {
        override fun nowMs() = now
    }

    /**
     * Each mirror serves [body] at [bytesPerSec] (the clock advances per read), optionally failing after [failAt]
     * bytes (read timeout = stall), or answering [code] / ignoring Range.
     */
    private data class Behaviour(
        val bytesPerSec: Long,
        val failAt: Long? = null,
        val code: Int = 206,
        val ignoreRange: Boolean = false,
        val corrupt: Boolean = false,
        /** After this offset the throughput drops to [slowBytesPerSec]. */
        val slowAfter: Long? = null,
        val slowBytesPerSec: Long = 0,
    )

    private inner class FakeTransport(private val clock: FakeClock, private val mirrors: Map<String, Behaviour>) : RangeTransport {
        val opened = mutableListOf<Pair<String, Long>>()
        val ifRanges = mutableListOf<String?>()

        override fun open(mirror: Mirror, from: Long, toInclusive: Long?, ifRange: String?): RangeResponse {
            val b = mirrors[mirror.url] ?: throw IOException("unknown host")
            opened += mirror.url to from
            ifRanges += ifRange
            val start = if (b.ignoreRange) 0L else from
            val end = if (b.ignoreRange || toInclusive == null) body.size.toLong() else minOf(toInclusive + 1, body.size.toLong())
            val data = if (b.corrupt) body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else body
            return object : RangeResponse {
                var pos = start
                override val code = if (b.code == 206 && b.ignoreRange) 200 else b.code
                override val contentRangeStart: Long? = if (code == 206) start else null
                override fun read(buf: ByteArray): Int {
                    if (pos >= end) return -1
                    if (b.failAt != null && pos >= b.failAt) {
                        clock.now += MirrorRace.STALL_NO_BYTES_MS
                        throw SocketTimeoutException("timeout")
                    }
                    val n = minOf(buf.size.toLong(), 16 * 1024L, end - pos, (b.failAt ?: Long.MAX_VALUE) - pos).toInt()
                    data.copyInto(buf, 0, pos.toInt(), pos.toInt() + n)
                    val rate = if (b.slowAfter != null && pos >= b.slowAfter) b.slowBytesPerSec else b.bytesPerSec
                    clock.now += n * 1000L / rate
                    pos += n
                    return n
                }
                override fun close() {}
            }
        }
    }

    private val body = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("race").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun m(url: String, auth: Boolean = false) = Mirror(url, auth)

    @Test
    fun probeRanksByThroughputRangeCapableFirstFailedLast() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf(
            "https://slow" to Behaviour(100_000),
            "https://fast" to Behaviour(2_000_000),
            "https://norange" to Behaviour(5_000_000, ignoreRange = true),
            "https://down" to Behaviour(1, code = 502),
        ))
        val probes = MirrorRace.probeAll(listOf(m("https://slow"), m("https://fast"), m("https://norange"), m("https://down"), m("https://dns-fail")), t, clock, parallel = false)
        val ranked = MirrorRace.rank(probes).map { it.mirror.url }
        assertEquals(listOf("https://fast", "https://slow", "https://norange", "https://down", "https://dns-fail"), ranked)
        // The probe asks for the first 256 KiB only.
        assertTrue(t.opened.all { it.second == 0L })
        val fast = probes.first { it.mirror.url == "https://fast" }
        assertEquals(MirrorRace.PROBE_BYTES.toInt(), fast.head!!.size)
        assertEquals(2_000_000.0, fast.bytesPerSec, 100_000.0)
    }

    @Test
    fun probeStopsAtWindowOnVerySlowMirror() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf("https://crawl" to Behaviour(10_000)))
        val p = MirrorRace.probeOne(m("https://crawl"), t, clock)
        assertTrue(p.ok)
        assertTrue("probe must stop near the 3 s window", clock.now in MirrorRace.PROBE_WINDOW_MS..MirrorRace.PROBE_WINDOW_MS + 2_000)
        assertTrue(p.head!!.size < MirrorRace.PROBE_BYTES)
    }

    @Test
    fun candidatesCappedSameOriginFirstDeduplicated() {
        val list = (1..9).map { m("https://m$it") } + m("https://m1") + m("https://origin", auth = true)
        val c = MirrorRace.candidates(list)
        assertEquals(MirrorRace.MAX_PROBES, c.size)
        assertEquals("https://origin", c.first().url)
        assertEquals(c.size, c.map { it.url }.distinct().size)
    }

    @Test
    fun stallDetectorNoBytesAndSlowThroughput() {
        val clock = FakeClock()
        val d = StallDetector(1_000_000.0, clock)
        clock.now += 5_000; assertFalse(d.onRead(5_000_000)) // healthy
        clock.now += MirrorRace.STALL_NO_BYTES_MS; assertTrue(d.onRead(0))
        assertTrue(d.reason!!.contains("没有数据"))

        val c2 = FakeClock()
        val slow = StallDetector(1_000_000.0, c2)
        // 15% of the probe speed for 10 s -> stalled
        repeat(10) { c2.now += 1_000; slow.onRead(150_000).let { stalled -> if (it < 9) assertFalse(stalled) else assertTrue(stalled) } }
        assertTrue(slow.reason!!.contains("20%"))

        val c3 = FakeClock()
        val ok = StallDetector(1_000_000.0, c3)
        // 30% of the probe speed is acceptable
        repeat(30) { c3.now += 1_000; assertFalse(ok.onRead(300_000)) }
    }

    private fun ranked(clock: FakeClock, t: RangeTransport, vararg urls: String) =
        MirrorRace.rank(MirrorRace.probeAll(urls.map { m(it) }, t, clock, parallel = false))

    @Test
    fun failsOverToNextFastestAndResumesFromCurrentOffset() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf(
            "https://fast" to Behaviour(4_000_000, failAt = 600_000),
            "https://second" to Behaviour(1_000_000),
            "https://third" to Behaviour(100_000),
        ))
        val r = ranked(clock, t, "https://third", "https://fast", "https://second")
        assertEquals("https://fast", r.first().mirror.url)
        t.opened.clear()
        val switched = mutableListOf<String>()
        val f = ApkDownloader(t, clock).downloadFrom(r, File(dir, "a.apk"), body.size.toLong(), sha, onSwitch = { mm, _ -> switched += mm.url })
        assertArrayEquals(body, f.readBytes())
        assertEquals(listOf("https://fast"), switched)
        // Probe bytes reused (resume at 256 KiB on the winner), then resume on the second mirror at the stall offset.
        assertEquals(listOf("https://fast" to MirrorRace.PROBE_BYTES, "https://second" to 600_000L), t.opened)
        // Third-party mirrors never get If-Range (different ETags) — and never a token (transport decides by Mirror.authenticated).
        assertTrue(t.ifRanges.all { it == null })
    }

    @Test
    fun switchesWhenThroughputCollapsesBelowFifthOfProbe() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf(
            "https://a" to Behaviour(2_000_000, slowAfter = 400_000, slowBytesPerSec = 30_000),
            "https://b" to Behaviour(1_000_000),
        ))
        val r = ranked(clock, t, "https://a", "https://b")
        val switched = mutableListOf<String>()
        ApkDownloader(t, clock).downloadFrom(r, File(dir, "b.apk"), body.size.toLong(), sha, onSwitch = { mm, why -> switched += "${mm.url} $why" })
        assertEquals(1, switched.size)
        assertTrue(switched[0], switched[0].startsWith("https://a") && switched[0].contains("20%"))
        assertEquals("https://b", t.opened.last().first)
        assertTrue(t.opened.last().second > 400_000)
    }

    @Test
    fun allMirrorsFailingIsRetryableAndKeepsPartialBytes() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf(
            "https://a" to Behaviour(1_000_000, failAt = 300_000),
            "https://b" to Behaviour(500_000, failAt = 500_000),
        ))
        val r = ranked(clock, t, "https://a", "https://b")
        val dest = File(dir, "c.apk")
        try {
            ApkDownloader(t, clock).downloadFrom(r, dest, body.size.toLong(), sha)
            fail("expected failure")
        } catch (e: ApkDownloader.DownloadException) {
            assertTrue(e.retryable)
        }
        assertEquals(500_000L, File(dest.path + ".part").length())
        // Worker retry with backoff: next run re-races and resumes at 500 000.
        val t2 = FakeTransport(clock, mapOf("https://a" to Behaviour(1_000_000)))
        val f = ApkDownloader(t2, clock).downloadFrom(listOf(Probe(m("https://a"), 1_000_000.0, true)), dest, body.size.toLong(), sha)
        assertArrayEquals(body, f.readBytes())
        assertEquals(listOf("https://a" to 500_000L), t2.opened)
    }

    @Test
    fun corruptMirrorIsRejectedBySha256WhicheverMirrorServedIt() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf("https://evil" to Behaviour(5_000_000, corrupt = true)))
        val r = ranked(clock, t, "https://evil")
        val dest = File(dir, "d.apk")
        try {
            ApkDownloader(t, clock).downloadFrom(r, dest, body.size.toLong(), sha)
            fail("expected sha256 failure")
        } catch (e: ApkDownloader.DownloadException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("sha256"))
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun mirrorWithoutRangeSupportRestartsFromZero() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf(
            "https://a" to Behaviour(2_000_000, failAt = 700_000),
            "https://plain" to Behaviour(1_000_000, ignoreRange = true),
        ))
        val r = ranked(clock, t, "https://a", "https://plain")
        val f = ApkDownloader(t, clock).downloadFrom(r, File(dir, "e.apk"), body.size.toLong(), sha)
        assertArrayEquals(body, f.readBytes())
        assertEquals("https://plain" to 700_000L, t.opened.last()) // asked to resume, got a 200 and rewrote from 0
    }

    @Test
    fun authenticatedOriginGetsIfRangeAnd404MeansWithdrawn() {
        val clock = FakeClock()
        val t = FakeTransport(clock, mapOf("https://origin/apk" to Behaviour(1_000_000, code = 404)))
        try {
            ApkDownloader(t, clock).downloadFrom(listOf(Probe(m("https://origin/apk", auth = true), 0.0, true)), File(dir, "f.apk"), body.size.toLong(), sha)
            fail()
        } catch (e: ApkDownloader.DownloadException) {
            assertFalse(e.retryable)
        }
        val t2 = FakeTransport(clock, mapOf("https://origin/apk" to Behaviour(1_000_000)))
        val dest = File(dir, "g.apk")
        File(dest.path + ".part").writeBytes(body.copyOf(1000))
        File(dest.path + ".part.sha256").writeText(sha)
        ApkDownloader(t2, clock).downloadFrom(listOf(Probe(m("https://origin/apk", auth = true), 0.0, true)), dest, body.size.toLong(), sha)
        assertEquals("\"$sha\"", t2.ifRanges.single())
        assertNull(MirrorRace.rank(emptyList()).firstOrNull())
    }
}
