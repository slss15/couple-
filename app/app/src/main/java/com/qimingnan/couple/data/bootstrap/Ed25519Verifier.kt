package com.qimingnan.couple.data.bootstrap

import com.qimingnan.couple.core.Base64Url
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 校验。Android 平台 API 33 以下没有 Ed25519，所以用 BouncyCastle 轻量 API（minSdk 26 也可用）。
 */
object Ed25519Verifier {
    /** X.509 SubjectPublicKeyInfo 前缀，兼容后端返回 SPKI DER 而不是 raw 32 字节的情况。 */
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** 把 /bootstrap/pubkey 返回的 base64 公钥规范化成 raw 32 字节。 */
    fun normalizePublicKey(encoded: String): ByteArray {
        val bytes = Base64Url.decode(encoded)
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 44 && bytes.copyOfRange(0, 12).contentEquals(SPKI_PREFIX) -> bytes.copyOfRange(12, 44)
            else -> throw IllegalArgumentException("不是 Ed25519 公钥（长度 ${bytes.size}）")
        }
    }

    fun verify(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(rawPublicKey, 0))
        signer.update(message, 0, message.size)
        signer.verifySignature(signature)
    } catch (e: Exception) {
        false
    }
}
