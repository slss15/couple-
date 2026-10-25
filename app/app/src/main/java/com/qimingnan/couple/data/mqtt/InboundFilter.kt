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
 * - Per sender, messages are ordered by (session, sendTs, messageId), where session is the sender's connection start
 *   and a Last Will sorts after everything else of its session (the broker sends it last, although it was built at
 *   connect time). ULIDs from one sender sort in send order, so the key is a total order. A message older than the
 *   newest one already accepted from that sender is [Verdict.STALE]: a state topic (presence, latest wins) must drop
 *   it; an event stream can still deliver it, flagged as late. Without a session (older clients) session = sendTs.
 * - Accepted messages keep arrival order; this class never buffers or reorders.
 */
class InboundFilter(private val capacity: Int = 1_024) {
    enum class Verdict { ACCEPT, DUPLICATE, STALE }

    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
    }
    private val newest = HashMap<String, Key>()

    private data class Key(val session: Long, val position: Long, val messageId: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, Key::session, Key::position, Key::messageId)
    }

    /**
     * @param session sender's connection start (defaults to [sendTs] for clients that do not send one)
     * @param isWill the sender's Last Will: newest message of its session
     */
    @Synchronized
    fun offer(senderId: String, messageId: String, sendTs: Long, session: Long = sendTs, isWill: Boolean = false): Verdict {
        if (seen.containsKey(messageId)) return Verdict.DUPLICATE
        seen[messageId] = Unit
        val key = Key(session, if (isWill) Long.MAX_VALUE else sendTs, messageId)
        val prev = newest[senderId]
        if (prev != null && key < prev) return Verdict.STALE
        newest[senderId] = key
        return Verdict.ACCEPT
    }

    @Synchronized
    fun reset() {
        seen.clear()
        newest.clear()
    }

    companion object {
        /** Messages from clients that predate messageId get a deterministic id so replays still dedupe. */
        fun legacyId(senderId: String, sendTs: Long, discriminator: String): String = "legacy:$senderId:$sendTs:$discriminator"
    }
}
