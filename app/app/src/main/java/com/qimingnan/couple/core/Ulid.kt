package com.qimingnan.couple.core

import java.security.SecureRandom

/** 最小 ULID 生成器（Crockford base32，48 位时间戳 + 80 位随机）。用于本地 deviceId。 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun next(timeMillis: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(26)
        var t = timeMillis
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        sb.append(timeChars)
        val rnd = ByteArray(10).also(random::nextBytes)
        // 80 bits -> 16 chars
        var bits = 0
        var acc = 0
        for (b in rnd) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(acc ushr bits) and 31])
            }
        }
        return sb.toString()
    }
}
