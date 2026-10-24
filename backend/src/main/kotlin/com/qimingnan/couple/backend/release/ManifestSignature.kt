package com.qimingnan.couple.backend.release

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 verification of release.json.sig (standard base64 signature over the exact bytes of release.json) against
 * the OTA release public key (standard base64 of the raw 32-byte key). This key is distinct from the bootstrap key.
 */
object ManifestSignature {
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    private fun rawKey(b64: String): ByteArray? = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()?.takeIf { it.size == 32 }

    fun isValidPublicKey(b64: String): Boolean = rawKey(b64) != null

    fun verify(publicKeyB64: String, message: ByteArray, signatureB64: String): Boolean {
        val raw = rawKey(publicKeyB64) ?: return false
        return try {
            val sig = Base64.getDecoder().decode(signatureB64.trim())
            if (sig.size != 64) return false
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(sig)
            }
        } catch (e: Exception) {
            false
        }
    }
}
