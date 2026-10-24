package com.qimingnan.couple.data.mqtt

/**
 * Per-message delivery latency (receive ts - send ts) over a sliding window, plus connection counters.
 *
 * Two series:
 * - echo: our own publishes coming back through the broker. Same clock on both ends, so this is the true
 *   publish -> broker -> subscriber round trip.
 * - peer: messages from the partner's phone. Includes the clock offset between the two phones, so treat it as
 *   approximate (it can even be negative).
 */
class LatencyStats(private val window: Int = 500) {
    data class Snapshot(
        val echoCount: Int,
        val echoP50: Long?,
        val echoP95: Long?,
        val peerCount: Int,
        val peerP50: Long?,
        val peerP95: Long?,
        val reconnects: Int,
        val networkChanges: Int,
        val duplicatesDropped: Int,
        val staleDropped: Int,
    )

    private val echo = ArrayDeque<Long>()
    private val peer = ArrayDeque<Long>()
    private var reconnects = 0
    private var networkChanges = 0
    private var duplicates = 0
    private var stale = 0

    @Synchronized fun recordEcho(sendTs: Long, receiveTs: Long) = push(echo, receiveTs - sendTs)
    @Synchronized fun recordPeer(sendTs: Long, receiveTs: Long) = push(peer, receiveTs - sendTs)
    @Synchronized fun recordReconnect() { reconnects++ }
    @Synchronized fun recordNetworkChange() { networkChanges++ }
    @Synchronized fun recordDuplicate() { duplicates++ }
    @Synchronized fun recordStale() { stale++ }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        echoCount = echo.size,
        echoP50 = percentile(echo, 50.0),
        echoP95 = percentile(echo, 95.0),
        peerCount = peer.size,
        peerP50 = percentile(peer, 50.0),
        peerP95 = percentile(peer, 95.0),
        reconnects = reconnects,
        networkChanges = networkChanges,
        duplicatesDropped = duplicates,
        staleDropped = stale,
    )

    private fun push(q: ArrayDeque<Long>, v: Long) {
        q.addLast(v)
        while (q.size > window) q.removeFirst()
    }

    companion object {
        /** Nearest-rank percentile; null when there are no samples. */
        fun percentile(values: Collection<Long>, p: Double): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
