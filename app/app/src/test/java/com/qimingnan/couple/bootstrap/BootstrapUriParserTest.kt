package com.qimingnan.couple.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.data.bootstrap.BootstrapError
import com.qimingnan.couple.data.bootstrap.BootstrapUriParser
import com.qimingnan.couple.data.bootstrap.Ed25519Verifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapUriParserTest {
    private val signer = TestSigner()

    @Test fun parsesAndVerifiesValidUri() {
        val uri = signer.uri("https://a.example.com", "wss://a.example.com/mqtt", 3)
        val p = BootstrapUriParser.parse(uri)
        assertEquals("https://a.example.com", p.payload.baseUrl)
        assertEquals(3L, p.payload.configVersion)
        assertTrue(Ed25519Verifier.verify(signer.pubRaw, p.dBytes, p.signature))
    }

    @Test fun tamperedPayloadFailsVerification() {
        val uri = signer.uri("https://a.example.com", "wss://a.example.com/mqtt", 3)
        val p = BootstrapUriParser.parse(uri)
        val tampered = p.dBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(Ed25519Verifier.verify(signer.pubRaw, tampered, p.signature))
    }

    @Test fun otherKeyFailsVerification() {
        val p = BootstrapUriParser.parse(signer.uri("https://a.example.com", "wss://a.example.com/mqtt", 1))
        assertFalse(Ed25519Verifier.verify(TestSigner().pubRaw, p.dBytes, p.signature))
    }

    @Test fun acceptsSpkiAndRawPublicKeys() {
        val raw = Base64Url.encodeStd(signer.pubRaw)
        assertTrue(Ed25519Verifier.normalizePublicKey(raw).contentEquals(signer.pubRaw))
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + signer.pubRaw
        assertTrue(Ed25519Verifier.normalizePublicKey(Base64Url.encodeStd(spki)).contentEquals(signer.pubRaw))
    }

    @Test(expected = BootstrapError.InvalidUri::class)
    fun rejectsWrongScheme() { BootstrapUriParser.parse("https://bootstrap?d=a&s=b") }

    @Test(expected = BootstrapError.InvalidUri::class)
    fun rejectsMissingSignature() { BootstrapUriParser.parse("couple://bootstrap?d=e30") }

    @Test(expected = BootstrapError.UnsupportedVersion::class)
    fun rejectsUnknownVersion() {
        val d = """{"v":2,"baseUrl":"https://x","mqttUrl":"wss://x","configVersion":1,"issuedAt":1}""".toByteArray()
        BootstrapUriParser.parse("couple://bootstrap?d=${Base64Url.encode(d)}&s=${Base64Url.encode(signer.sign(d))}")
    }

    @Test fun cleartextOnlyInDebug() {
        BootstrapUriParser.checkUrls("http://10.0.2.2:8080", "ws://10.0.2.2:8083/mqtt", allowCleartext = true)
        try {
            BootstrapUriParser.checkUrls("http://10.0.2.2:8080", "wss://x/mqtt", allowCleartext = false)
            throw AssertionError("expected InsecureUrl")
        } catch (e: BootstrapError.InsecureUrl) { /* ok */ }
    }
}
