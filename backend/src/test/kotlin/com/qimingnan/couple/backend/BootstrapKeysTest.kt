package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.auth.PasswordHasher
import com.qimingnan.couple.backend.bootstrap.BootstrapKeys
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapKeysTest {

    @Test
    fun parsesPkcs8PemAndRawSeedConsistently() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pkcs8 = kp.private.encoded // same DER layout as `openssl genpkey -algorithm ed25519 -outform DER`
        val rawPub = kp.public.encoded.takeLast(32).toByteArray()

        val fromDer = BootstrapKeys.parse(Base64.getEncoder().encodeToString(pkcs8))
        assertContentEquals(rawPub, fromDer.rawPublicKey, "BC-derived public key must match the JDK key pair")

        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs8) + "\n-----END PRIVATE KEY-----\n"
        assertContentEquals(rawPub, BootstrapKeys.parse(pem).rawPublicKey)

        val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
        assertContentEquals(rawPub, BootstrapKeys.parse(Base64.getEncoder().encodeToString(seed)).rawPublicKey)

        val msg = "hello".toByteArray()
        assertTrue(BootstrapKeys.verify(rawPub, msg, fromDer.sign(msg)))
    }

    @Test
    fun argon2idRoundTrip() {
        val h = PasswordHasher()
        val enc = h.hash("correct horse")
        assertTrue(enc.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertTrue(h.verify("correct horse", enc))
        assertFalse(h.verify("wrong horse", enc))
    }
}
