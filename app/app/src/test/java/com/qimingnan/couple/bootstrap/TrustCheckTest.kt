package com.qimingnan.couple.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.bootstrap.ApplyOutcome
import com.qimingnan.couple.data.bootstrap.BootstrapError
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import com.qimingnan.couple.data.bootstrap.BootstrapState
import com.qimingnan.couple.data.bootstrap.HealthResult
import com.qimingnan.couple.data.bootstrap.KeyFingerprint
import com.qimingnan.couple.data.bootstrap.ServerConfig
import com.qimingnan.couple.data.bootstrap.TrustCheck
import com.qimingnan.couple.data.bootstrap.TrustConfirmation
import com.qimingnan.couple.data.bootstrap.TrustedHost
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class KeyFingerprintTest {
    @Test fun formatsShortHexGroups() {
        val bytes = byteArrayOf(0xAB.toByte(), 0x12, 0xCD.toByte(), 0x34, 0xEF.toByte(), 0x56, 0x78, 0x90.toByte())
        assertEquals("ab12 cd34 ef56 7890", KeyFingerprint.format(bytes))
    }

    @Test fun usesFirst8BytesOfSha256LikeAdmin() {
        val key = ByteArray(32) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val adminStyle = digest.take(8).joinToString(":") { "%02x".format(it) } // DashboardView.vue
        val fp = KeyFingerprint.of(key)
        assertTrue(Regex("^[0-9a-f]{4}( [0-9a-f]{4}){3}$").matches(fp))
        assertEquals(KeyFingerprint.normalize(adminStyle), KeyFingerprint.normalize(fp))
    }

    @Test fun normalizeIgnoresCaseAndSeparators() {
        assertEquals("ab12cd34", KeyFingerprint.normalize("AB:12:cd 34"))
    }
}

class TrustedHostTest {
    @Test fun hostOnlyWithNonDefaultPort() {
        assertEquals("a.example.com", TrustedHost.of("https://A.Example.com/"))
        assertEquals("a.example.com", TrustedHost.of("https://a.example.com:443/x"))
        assertEquals("a.example.com:8443", TrustedHost.of("https://a.example.com:8443"))
        assertEquals("192.168.1.2:8080", TrustedHost.of("http://192.168.1.2:8080"))
        assertNull(TrustedHost.of("not a url"))
    }
}

class TrustCheckEvaluateTest {
    private val fpA = "aaaa bbbb cccc dddd"
    private val fpB = "1111 2222 3333 4444"

    @Test fun firstTime() = assertEquals(TrustCheck.FirstTime, TrustCheck.evaluate(null, null, "a.com", fpA))
    @Test fun same() = assertEquals(TrustCheck.Same, TrustCheck.evaluate("a.com", fpA, "a.com", "AAAA:BBBB:CCCC:DDDD"))
    @Test fun hostChanged() = assertEquals(TrustCheck.HostChanged("a.com", "b.com"), TrustCheck.evaluate("a.com", fpA, "b.com", fpA))
    @Test fun keyChanged() = assertEquals(TrustCheck.KeyChanged(fpA, fpB), TrustCheck.evaluate("a.com", fpA, "a.com", fpB))
    @Test fun both() = assertEquals(
        TrustCheck.HostAndKeyChanged("a.com", "b.com", fpA, fpB),
        TrustCheck.evaluate("a.com", fpA, "b.com", fpB),
    )
    @Test fun manualConfigCannotChangeKey() = assertEquals(TrustCheck.Same, TrustCheck.evaluate("a.com", fpA, "a.com", null))
    @Test fun confirmFlags() {
        assertFalse(TrustCheck.FirstTime.needsExplicitConfirm)
        assertFalse(TrustCheck.Same.needsExplicitConfirm)
        assertTrue(TrustCheck.HostChanged("a", "b").needsExplicitConfirm)
        assertTrue(TrustCheck.KeyChanged(fpA, fpB).keyChanged)
    }
}

class TrustFlowTest {
    private val signer = TestSigner()
    private val A = "https://a.example.com"
    private val B = "https://b.example.com"

    private fun TestScope.manager(store: FakeStore, probe: FakeProbe) =
        BootstrapManager(store, probe, Clock { currentTime }, allowCleartext = false, sleeper = { kotlinx.coroutines.delay(it) })

    private fun okProbe(pub: ByteArray) = FakeProbe(pub).apply {
        health[A] = mutableListOf(HealthResult.Ok(null, 1)); health[B] = mutableListOf(HealthResult.Ok(null, 1))
    }

    @Test fun firstScanPersistsTrustedHostAndFingerprint() = runTest {
        val store = FakeStore()
        val m = manager(store, okProbe(signer.pubRaw))
        val uri = signer.uri(A, "wss://a/mqtt", 1)
        val preview = m.inspectUri(uri)
        assertEquals(TrustCheck.FirstTime, preview.trust)
        assertEquals("a.example.com", preview.host)
        assertEquals(KeyFingerprint.of(signer.pubRaw), preview.fingerprint)
        assertNull(store.value.current) // inspect stores nothing
        assertTrue(m.applyUri(uri, preview.confirmation(false)) is ApplyOutcome.Applied)
        assertEquals("a.example.com", store.value.trustedHost)
        assertEquals(KeyFingerprint.of(signer.pubRaw), store.value.trustedKeyFingerprint)
    }

    @Test fun sameServerNeedsNoExtraConfirm() = runTest {
        val store = FakeStore()
        val m = manager(store, okProbe(signer.pubRaw))
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val uri2 = signer.uri(A, "wss://a/mqtt2", 2)
        assertEquals(TrustCheck.Same, m.inspectUri(uri2).trust)
        assertTrue(m.applyUri(uri2) is ApplyOutcome.Applied)
    }

    @Test fun hostChangeIsRejectedWithoutExplicitConfirm() = runTest {
        val store = FakeStore()
        val m = manager(store, okProbe(signer.pubRaw))
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val uri = signer.uri(B, "wss://b/mqtt", 2)
        val preview = m.inspectUri(uri)
        assertEquals(TrustCheck.HostChanged("a.example.com", "b.example.com"), preview.trust)
        val noConfirm = m.applyUri(uri)
        assertTrue(noConfirm is ApplyOutcome.Rejected && noConfirm.error is BootstrapError.TrustChangeUnconfirmed)
        val declined = m.applyUri(uri, preview.confirmation(acceptChange = false))
        assertTrue(declined is ApplyOutcome.Rejected)
        assertEquals(A, store.value.current?.baseUrl)
        assertTrue(m.applyUri(uri, preview.confirmation(acceptChange = true)) is ApplyOutcome.Applied)
        assertEquals("b.example.com", store.value.trustedHost)
    }

    @Test fun keyChangeShowsNewFingerprintAndRepinsOnlyAfterConfirm() = runTest {
        val store = FakeStore()
        val probe = okProbe(signer.pubRaw)
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 5))
        val other = TestSigner()
        probe.pubkey = other.pubRaw
        val uri = other.uri(B, "wss://b/mqtt", 1) // lower configVersion is fine for a new key
        val preview = m.inspectUri(uri)
        assertTrue(preview.trust is TrustCheck.HostAndKeyChanged)
        assertEquals(KeyFingerprint.of(other.pubRaw), preview.fingerprint)

        // Without confirmation: the contract's TOFU rule still rejects it.
        val rejected = m.applyUri(uri)
        assertTrue(rejected is ApplyOutcome.Rejected && rejected.error is BootstrapError.SignatureInvalid)
        assertEquals(Base64Url.encodeStd(signer.pubRaw), store.value.pinnedPublicKey)

        assertTrue(m.applyUri(uri, preview.confirmation(acceptChange = true)) is ApplyOutcome.Applied)
        assertEquals(Base64Url.encodeStd(other.pubRaw), store.value.pinnedPublicKey)
        assertEquals(KeyFingerprint.of(other.pubRaw), store.value.trustedKeyFingerprint)
    }

    @Test fun keySwappedAfterPreviewIsRefused() = runTest {
        val store = FakeStore()
        val probe = okProbe(signer.pubRaw)
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val other = TestSigner()
        probe.pubkey = other.pubRaw
        val uri = other.uri(A, "wss://a/mqtt", 2)
        val preview = m.inspectUri(uri)
        val third = TestSigner()
        probe.pubkey = third.pubRaw // server swaps key between preview and apply
        val out = m.applyUri(uri, TrustConfirmation(preview.host, preview.fingerprint, acceptChange = true))
        assertTrue(out is ApplyOutcome.Rejected && out.error is BootstrapError.TrustChangeUnconfirmed)
        assertEquals(Base64Url.encodeStd(signer.pubRaw), store.value.pinnedPublicKey)
    }

    @Test fun failedTrialOfNewKeyRestoresOldPin() = runTest {
        val store = FakeStore()
        val probe = okProbe(signer.pubRaw).apply { health[B] = mutableListOf(HealthResult.Unreachable("down")) }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val other = TestSigner()
        probe.pubkey = other.pubRaw
        val uri = other.uri(B, "wss://b/mqtt", 1)
        val out = m.applyUri(uri, m.inspectUri(uri).confirmation(true))
        assertTrue(out is ApplyOutcome.RolledBack)
        assertEquals(Base64Url.encodeStd(signer.pubRaw), store.value.pinnedPublicKey)
        assertEquals("a.example.com", store.value.trustedHost)
        assertEquals(A, store.value.current?.baseUrl)
    }

    @Test fun manualConfigOnOtherHostNeedsConfirm() = runTest {
        val store = FakeStore()
        val m = manager(store, okProbe(signer.pubRaw))
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val preview = m.inspectManual(B, "wss://b/mqtt")
        assertTrue(preview.trust is TrustCheck.HostChanged)
        assertTrue(m.applyManual(B, "wss://b/mqtt") is ApplyOutcome.Rejected)
        assertTrue(m.applyManual(B, "wss://b/mqtt", preview.confirmation(true)) is ApplyOutcome.Applied)
    }

    @Test fun legacyStateFallsBackToCommittedConfig() {
        val st = BootstrapState(
            current = ServerConfig(A, "wss://a/mqtt", 1, 0, true, 0),
            pinnedPublicKey = Base64Url.encodeStd(signer.pubRaw),
        )
        assertEquals("a.example.com", st.effectiveTrustedHost())
        assertEquals(KeyFingerprint.of(signer.pubRaw), st.effectiveTrustedFingerprint())
    }
}
