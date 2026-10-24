package com.qimingnan.couple.core

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 公钥指纹：SHA-256 前 8 字节，四位一组（ab12 cd34 ef56 7890），便于两人肉眼核对。 */
fun fingerprint(bytes: ByteArray): String = com.qimingnan.couple.data.bootstrap.KeyFingerprint.of(bytes)
