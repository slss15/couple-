package com.qimingnan.couple.data.mqtt

/**
 * Deduplicates inbound MQTT messages by messageId and keeps per-sender order.
 *
 * Why: with cleanSession=false and QoS1 the broker redelivers anything it did not see a PUBACK for, and every
 * re-subscribe replays the retained message, so the same message can arrive several times. QoS1 also only orders
 * messages within one network connection; after a reconnect an older message can arrive after a newer one.
 *
 * Rules:
 * - A messageId seen before (bounded LRU of [capacity] ids) is dropped as a duplicate.
 * - Per sender, messages are ordered by (sendTs, messageId). ULIDs from one sender sort in send order, so the pair is
 *   a total order. A message older than the newest one already accepted from that sender is [Verdict.Stale]: a
 *   state topic (presence, latest wins) must drop it; an event stream can still deliver it, flagged as late.
 * - Accepted messages keep arrival order; this class never buffers or reorders.
 */
class InboundFilter(private val capacity: Int = 1_024) {
    enum class Verdict { ACCEPT, DUPLICATE, STALE }

    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
    }
    private val newest = HashMap<String, Pair<Long, String>>()

    @Synchronized
    fun offer(senderId: String, messageId: String, sendTs: Long): Verdict {
        if (seen.containsKey(messageId)) return Verdict.DUPLICATE
        seen[messageId] = Unit
        val prev = newest[senderId]
        val key = sendTs to messageId
        if (prev != null && compare(key, prev) < 0) return Verdict.STALE
        newest[senderId] = key
        return Verdict.ACCEPT
    }

    @Synchronized
    fun reset() {
        seen.clear()
        newest.clear()
    }

    private fun compare(a: Pair<Long, String>, b: Pair<Long, String>): Int =
        a.first.compareTo(b.first).takeIf { it != 0 } ?: a.second.compareTo(b.second)

    companion object {
        /** Messages from clients that predate messageId get a deterministic id so replays still dedupe. */
        fun legacyId(senderId: String, sendTs: Long, discriminator: String): String = "legacy:$senderId:$sendTs:$discriminator"
    }
}
