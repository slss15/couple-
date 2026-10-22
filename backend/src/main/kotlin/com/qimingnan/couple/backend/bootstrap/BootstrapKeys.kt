package com.qimingnan.couple.backend.bootstrap

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 signing key for bootstrap QR payloads. Signing/verification use the JDK ("Ed25519" provider, JDK 15+);
 * Bouncy Castle is used only to derive the public key from a raw 32-byte seed.
 *
 * Public key wire format (GET /bootstrap/pubkey): standard base64 of the RAW 32-byte Ed25519 public key.
 */
class BootstrapKeys private constructor(private val seed: ByteArray) {
    val privateKey: PrivateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))
    val rawPublicKey: ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    val publicKey: PublicKey = publicKeyFromRaw(rawPublicKey)

    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(rawPublicKey)
    val seedBase64: String get() = Base64.getEncoder().encodeToString(seed)

    fun sign(data: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(data)
        sign()
    }

    companion object {
        /** DER prefix of a PKCS#8 Ed25519 private key; followed by the 32-byte seed. */
        private val PKCS8_PREFIX = hex("302e020100300506032b657004220420")
        /** DER prefix of an X.509 SubjectPublicKeyInfo Ed25519 key; followed by the 32-byte key. */
        private val X509_PREFIX = hex("302a300506032b6570032100")

        fun fromSeed(seed: ByteArray): BootstrapKeys {
            require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
            return BootstrapKeys(seed.copyOf())
        }

        /**
         * Parses BOOTSTRAP_PRIVATE_KEY: base64/base64url of either the raw 32-byte seed or a PKCS#8 DER key,
         * or a PEM "-----BEGIN PRIVATE KEY-----" block.
         */
        fun parse(value: String): BootstrapKeys {
            val body = value.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
            val bytes = try {
                Base64.getDecoder().decode(body)
            } catch (e: IllegalArgumentException) {
                Base64.getUrlDecoder().decode(body.trimEnd('='))
            }
            val seed = when {
                bytes.size == 32 -> bytes
                bytes.size == 48 && bytes.copyOfRange(0, 16).contentEquals(PKCS8_PREFIX) -> bytes.copyOfRange(16, 48)
                else -> throw IllegalArgumentException("BOOTSTRAP_PRIVATE_KEY must be a 32-byte Ed25519 seed or PKCS#8 DER (base64)")
            }
            return fromSeed(seed)
        }

        fun generate(): BootstrapKeys {
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val enc = kp.private.encoded // PKCS#8, 48 bytes
            return fromSeed(enc.copyOfRange(enc.size - 32, enc.size))
        }

        fun publicKeyFromRaw(raw: ByteArray): PublicKey =
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))

        fun verify(rawPublicKey: ByteArray, data: ByteArray, sig: ByteArray): Boolean = Signature.getInstance("Ed25519").run {
            initVerify(publicKeyFromRaw(rawPublicKey))
            update(data)
            verify(sig)
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
