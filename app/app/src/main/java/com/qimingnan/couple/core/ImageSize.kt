package com.qimingnan.couple.core

/**
 * 纯 Kotlin 读取图片头部尺寸（JPEG / PNG / GIF / WebP），不依赖 BitmapFactory，JVM 单测可用。
 * 返回 (mime, width, height)；不认识的格式返回 null。
 */
object ImageSize {
    data class Info(val mime: String, val width: Int, val height: Int)

    fun read(b: ByteArray): Info? = runCatching {
        when {
            b.size >= 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() ->
                Info("image/png", be32(b, 16), be32(b, 20))
            b.size >= 10 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() ->
                Info("image/gif", le16(b, 6), le16(b, 8))
            b.size >= 30 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> webp(b)
            b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> jpeg(b)
            else -> null
        }
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }

    private fun jpeg(b: ByteArray): Info? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val len = be16(b, i + 2)
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) return Info("image/jpeg", be16(b, i + 7), be16(b, i + 5))
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): Info? = when (String(b, 12, 4, Charsets.US_ASCII)) {
        "VP8X" -> Info("image/webp", 1 + le24(b, 24), 1 + le24(b, 27))
        "VP8 " -> Info("image/webp", le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
        "VP8L" -> {
            val v = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
            Info("image/webp", (v and 0x3FFF) + 1, ((v shr 14) and 0x3FFF) + 1)
        }
        else -> null
    }

    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
}
