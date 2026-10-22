package com.qimingnan.couple.core

import java.security.MessageDigest

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 公钥指纹：SHA-256 前 8 字节，形如 ab12:cd34:… 便于两人肉眼核对。 */
fun fingerprint(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
        .joinToString(":") { "%02x".format(it) }
