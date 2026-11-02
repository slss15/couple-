package com.qimingnan.couple.feed

/** 单测用的最小图片头部（只需能读出尺寸）。 */
object TestImages {
    fun png(w: Int, h: Int): ByteArray {
        val b = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        byteArrayOf(0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()).copyInto(b, 8)
        be32(w).copyInto(b, 16); be32(h).copyInto(b, 20)
        return b
    }

    fun jpeg(w: Int, h: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0, // APP0（长度 4）
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 1, 1, 0x11, 0,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
