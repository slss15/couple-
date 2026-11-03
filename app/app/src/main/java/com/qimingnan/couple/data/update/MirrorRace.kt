package com.qimingnan.couple.data.update

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One place the APK can be fetched from. [authenticated] = same origin as the configured server: only these requests
 * carry the access token (and the sha256 If-Range). Every other mirror is fetched with a token-free client.
 */
data class Mirror(val url: String, val authenticated: Boolean)

/** A ranged GET. Implemented with OkHttp in production ([OkHttpRangeTransport]) and faked in unit tests. */
fun interface RangeTransport {
    /** [toInclusive] null = open-ended. [ifRange] is only sent to authenticated mirrors (other hosts use other ETags). */
    @Throws(IOException::class)
    fun open(mirror: Mirror, from: Long, toInclusive: Long?, ifRange: String?): RangeResponse
}

interface RangeResponse : Closeable {
    val code: Int
    /** Start offset from Content-Range (206 only), else null. */
    val contentRangeStart: Long?
    /** Like InputStream.read; a stalled connection throws (read timeout) instead of blocking forever. */
    @Throws(IOException::class)
    fun read(buf: ByteArray): Int
}

/** Monotonic milliseconds; System.nanoTime in production, a fake in tests. */
fun interface MonoClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MonoClock { System.nanoTime() / 1_000_000 }
    }
}

/** Result of probing one mirror with `Range: bytes=0-262143`. */
data class Probe(
    val mirror: Mirror,
    /** Measured over the first chunk (or the probe window); 0 when the probe failed. */
    val bytesPerSec: Double,
    /** Answered 206 from offset 0 (so it can resume); a 200 still works but restarts from zero on every switch. */
    val rangeOk: Boolean,
    val error: String? = null,
    /** The probed bytes (from offset 0) — reused as the start of the file when the download starts from scratch. */
    val head: ByteArray? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Mirror racing (contract "Auto-update → mirrors"): probe every mirror in parallel, rank by measured throughput, then
 * download from the fastest and fail over to the next-fastest when it stalls. Pure JVM so it is tested with a fake
 * clock + transport. Which bytes are acceptable is NOT decided here: the caller always checks size + sha256 against
 * the signed manifest at the end, whichever mirror(s) served the file.
 */
object MirrorRace {
    const val PROBE_BYTES = 256L * 1024
    const val PROBE_WINDOW_MS = 3_000L
    const val MAX_PROBES = 6
    /** No byte at all for this long = stalled (OkHttp's read timeout enforces it while blocked in read()). */
    const val STALL_NO_BYTES_MS = 15_000L
    /** Throughput below [SLOW_FRACTION] of the probe, measured over a window this long, = stalled. */
    const val SLOW_WINDOW_MS = 10_000L
    const val SLOW_FRACTION = 0.2

    /** Same-origin first, then the rest in the order given, deduplicated, capped at [MAX_PROBES]. */
    fun candidates(mirrors: List<Mirror>): List<Mirror> =
        mirrors.distinctBy { it.url }.sortedByDescending { it.authenticated }.take(MAX_PROBES)

    fun probeOne(mirror: Mirror, transport: RangeTransport, clock: MonoClock): Probe {
        val start = clock.nowMs()
        return try {
            transport.open(mirror, 0, PROBE_BYTES - 1, null).use { r ->
                val rangeOk = r.code == 206 && (r.contentRangeStart ?: 0L) == 0L
                if (r.code != 206 && r.code != 200) return Probe(mirror, 0.0, false, "HTTP ${r.code}")
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < PROBE_BYTES && clock.nowMs() - start < PROBE_WINDOW_MS) {
                    val n = r.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, minOf(n, (PROBE_BYTES - out.size()).toInt()))
                }
                val elapsed = (clock.nowMs() - start).coerceAtLeast(1)
                if (out.size() == 0) return Probe(mirror, 0.0, rangeOk, "no data")
                Probe(mirror, out.size() * 1000.0 / elapsed, rangeOk, head = out.toByteArray())
            }
        } catch (e: IOException) {
            Probe(mirror, 0.0, false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Probes [candidates] of [mirrors]. Production runs them on a small thread pool and gives up on slow ones after
     * [deadlineMs]; tests pass `parallel = false` for determinism with a fake clock.
     */
    fun probeAll(
        mirrors: List<Mirror>,
        transport: RangeTransport,
        clock: MonoClock = MonoClock.SYSTEM,
        parallel: Boolean = true,
        deadlineMs: Long = PROBE_WINDOW_MS + 3_000,
    ): List<Probe> {
        val list = candidates(mirrors)
        if (!parallel || list.size <= 1) return list.map { probeOne(it, transport, clock) }
        val pool = Executors.newFixedThreadPool(list.size)
        try {
            val futures = list.map { m -> pool.submit(Callable { probeOne(m, transport, clock) }) }
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
            return list.zip(futures).map { (m, f) ->
                runCatching { f.get((until - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
                    .getOrElse { f.cancel(true); Probe(m, 0.0, false, "probe timeout") }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Download order: working mirrors that support Range by throughput (fastest first), then working ones without
     * Range, then the ones whose probe failed (kept as a last resort: a probe can fail transiently).
     */
    fun rank(probes: List<Probe>): List<Probe> {
        val ok = probes.filter { it.ok }
        return ok.filter { it.rangeOk }.sortedByDescending { it.bytesPerSec } +
            ok.filterNot { it.rangeOk }.sortedByDescending { it.bytesPerSec } +
            probes.filterNot { it.ok }
    }
}

/**
 * Decides when the current mirror counts as stalled while streaming. Call [onRead] after every read with the bytes
 * just read (0 is allowed); it returns true when the download should switch to the next mirror.
 */
class StallDetector(private val probeBytesPerSec: Double, private val clock: MonoClock) {
    private var lastByteAt = clock.nowMs()
    private var windowStart = lastByteAt
    private var windowBytes = 0L
    var reason: String? = null
        private set

    fun onRead(n: Int): Boolean {
        val now = clock.nowMs()
        if (n > 0) {
            lastByteAt = now
            windowBytes += n
        } else if (now - lastByteAt >= MirrorRace.STALL_NO_BYTES_MS) {
            reason = "${MirrorRace.STALL_NO_BYTES_MS / 1000} 秒没有数据"
            return true
        }
        val elapsed = now - windowStart
        if (elapsed >= MirrorRace.SLOW_WINDOW_MS) {
            val rate = windowBytes * 1000.0 / elapsed
            if (probeBytesPerSec > 0 && rate < probeBytesPerSec * MirrorRace.SLOW_FRACTION) {
                reason = "速度跌到测速的 ${(MirrorRace.SLOW_FRACTION * 100).toInt()}% 以下"
                return true
            }
            windowStart = now
            windowBytes = 0
        }
        return false
    }
}
