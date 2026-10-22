package com.qimingnan.couple.core

import java.util.Base64

/** base64url / 标准 base64 互通工具（java.util.Base64 需要 API 26，正好是 minSdk）。 */
object Base64Url {
    fun decode(value: String): ByteArray {
        val trimmed = value.trim().trimEnd('=')
        return if (trimmed.contains('+') || trimmed.contains('/')) {
            Base64.getDecoder().decode(pad(trimmed))
        } else {
            Base64.getUrlDecoder().decode(pad(trimmed))
        }
    }

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun encodeStd(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun pad(s: String): String = when (s.length % 4) {
        2 -> "$s=="
        3 -> "$s="
        else -> s
    }
}
