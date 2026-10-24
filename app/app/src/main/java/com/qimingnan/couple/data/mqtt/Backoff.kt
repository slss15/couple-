package com.qimingnan.couple.data.mqtt

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential reconnect backoff with symmetric jitter.
 *
 * nominal(attempt) = min(maxMillis, baseMillis * 2^(attempt-1)) for attempt >= 1;
 * delay = nominal * (1 + u), u uniform in [-jitter, +jitter]. With the defaults that is 1 s, 2 s, 4 s … capped at
 * 60 s, each ±20 %. Jitter keeps both phones (and every client after a server restart) from reconnecting in lockstep.
 */
class Backoff(
    val baseMillis: Long = 1_000,
    val maxMillis: Long = 60_000,
    val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(baseMillis > 0 && maxMillis >= baseMillis) { "invalid backoff range" }
        require(jitter in 0.0..1.0) { "jitter must be within 0..1" }
    }

    fun nominal(attempt: Int): Long {
        if (attempt <= 1) return baseMillis
        val shift = min(attempt - 1, 30)
        val raw = baseMillis.toDouble() * (1L shl shift).toDouble()
        return if (raw >= maxMillis) maxMillis else raw.toLong()
    }

    fun delayFor(attempt: Int): Long {
        val n = nominal(attempt)
        val u = if (jitter == 0.0) 0.0 else random.nextDouble(-jitter, jitter)
        return (n * (1.0 + u)).toLong().coerceAtLeast(1)
    }

    /** Inclusive bounds a jittered delay for [attempt] can take (used by tests and the debug screen). */
    fun bounds(attempt: Int): LongRange {
        val n = nominal(attempt)
        return (n * (1.0 - jitter)).toLong().coerceAtLeast(1)..(n * (1.0 + jitter)).toLong()
    }
}
