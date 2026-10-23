package com.qimingnan.couple.backend.release

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Extracts the signing certificate from an APK's v3 (preferred) or v2 APK Signature Scheme block, without
 * verifying the signature itself (the device does that at install time; the app also re-checks the cert).
 * The mirror uses it so a release whose `release.json` claims one cert but whose APK carries another is rejected
 * server-side, and so the cert pin is checked against what is really inside the APK.
 */
object ApkSigningCert {
    private const val EOCD_SIG = 0x06054b50
    private const val V2_ID = 0x7109871a
    private const val V3_ID = 0xf05368c0.toInt()
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    class ApkFormatException(message: String) : Exception(message)

    /** Lowercase hex SHA-256 of the first signer's first certificate (DER). */
    fun certSha256(file: File): String = sha256Hex(firstCertificate(file))

    fun firstCertificate(file: File): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val cdOffset = centralDirectoryOffset(raf)
        if (cdOffset < 32) throw ApkFormatException("no APK signing block")
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24)
        raf.readFully(footer)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) throw ApkFormatException("no APK signing block")
        val sizeInFooter = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (sizeInFooter < 24 || sizeInFooter > Int.MAX_VALUE - 8 || sizeInFooter + 8 > cdOffset) throw ApkFormatException("bad signing block size")
        val blockStart = cdOffset - sizeInFooter - 8
        val block = ByteArray((sizeInFooter + 8).toInt())
        raf.seek(blockStart)
        raf.readFully(block)
        val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.long != sizeInFooter) throw ApkFormatException("signing block size mismatch")
        val pairsEnd = block.size - 24
        var v2: ByteBuffer? = null
        var v3: ByteBuffer? = null
        while (buf.position() < pairsEnd) {
            if (pairsEnd - buf.position() < 8) throw ApkFormatException("truncated pair")
            val len = buf.long
            if (len < 4 || len > pairsEnd - buf.position()) throw ApkFormatException("bad pair length")
            val id = buf.int
            val value = slice(buf, (len - 4).toInt())
            when (id) {
                V3_ID -> v3 = value
                V2_ID -> v2 = value
            }
        }
        val scheme = v3 ?: v2 ?: throw ApkFormatException("no v2/v3 signature")
        // scheme value: length-prefixed sequence of length-prefixed signers.
        val signers = lengthPrefixed(scheme)
        val signer = lengthPrefixed(signers)
        val signedData = lengthPrefixed(signer)
        lengthPrefixed(signedData) // digests
        val certs = lengthPrefixed(signedData)
        val cert = lengthPrefixed(certs)
        ByteArray(cert.remaining()).also { cert.get(it) }
    }

    private fun centralDirectoryOffset(raf: RandomAccessFile): Long {
        val len = raf.length()
        if (len < 22) throw ApkFormatException("not a zip")
        val tailLen = minOf(len, 22L + 0xFFFF).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        val b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (i in tailLen - 22 downTo 0) {
            if (b.getInt(i) == EOCD_SIG) {
                val commentLen = b.getShort(i + 20).toInt() and 0xFFFF
                if (i + 22 + commentLen != tailLen) continue
                return b.getInt(i + 16).toLong() and 0xFFFFFFFFL
            }
        }
        throw ApkFormatException("no end of central directory")
    }

    private fun slice(buf: ByteBuffer, n: Int): ByteBuffer {
        if (n < 0 || n > buf.remaining()) throw ApkFormatException("truncated field")
        val s = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        s.limit(n)
        buf.position(buf.position() + n)
        return s
    }

    private fun lengthPrefixed(buf: ByteBuffer): ByteBuffer {
        if (buf.remaining() < 4) throw ApkFormatException("truncated length prefix")
        return slice(buf, buf.int)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
