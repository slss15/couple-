package com.qimingnan.couple.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.bootstrap.ApplyOutcome
import com.qimingnan.couple.data.bootstrap.BootstrapError
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import com.qimingnan.couple.data.bootstrap.HealthResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapManagerTest {
    private val signer = TestSigner()
    private val A = "https://a.example.com"
    private val B = "https://b.example.com"

    private fun TestScope.manager(store: FakeStore, probe: FakeProbe) = BootstrapManager(
        store = store,
        probe = probe,
        clock = Clock { currentTime },
        allowCleartext = false,
        sleeper = { kotlinx.coroutines.delay(it) },
    )

    /** What the confirm page does: preview, then apply with the user's explicit confirmation. */
    private suspend fun BootstrapManager.applyConfirmed(uri: String) = applyUri(uri, inspectUri(uri).confirmation(acceptChange = true))

    @Test fun firstScanPinsKeyAndCommits() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply { health[A] = mutableListOf(HealthResult.Ok("1.0", 1)) }
        val out = manager(store, probe).applyUri(signer.uri(A, "wss://a/mqtt", 1))
        assertTrue(out is ApplyOutcome.Applied)
        assertEquals(A, store.value.current?.baseUrl)
        assertNull(store.value.pendingSince)
        assertEquals(Base64Url.encodeStd(signer.pubRaw), store.value.pinnedPublicKey)
        assertEquals(false, store.value.pinIsProvisional)
    }

    @Test fun pinnedKeyRejectsOtherSigner() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply { health[A] = mutableListOf(HealthResult.Ok(null, 1)) }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        // 攻击者的服务器返回它自己的公钥也没用：已经 TOFU 固定
        val attacker = TestSigner()
        probe.pubkey = attacker.pubRaw
        val out = m.applyUri(attacker.uri(B, "wss://b/mqtt", 9))
        assertTrue(out is ApplyOutcome.Rejected && out.error is BootstrapError.SignatureInvalid)
        assertEquals(A, store.value.current?.baseUrl)
        assertEquals(1, probe.pubkeyCalls)
    }

    @Test fun unreachableNewConfigRollsBackAfter30s() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply {
            health[A] = mutableListOf(HealthResult.Ok(null, 1))
            health[B] = mutableListOf(HealthResult.Unreachable("timeout"))
        }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val start = currentTime
        val out = m.applyConfirmed(signer.uri(B, "wss://b/mqtt", 2))
        assertTrue(out is ApplyOutcome.RolledBack)
        assertEquals(A, (out as ApplyOutcome.RolledBack).restored?.baseUrl)
        assertEquals(A, store.value.current?.baseUrl)
        assertNull(store.value.pendingSince)
        assertTrue("应等满 30 秒窗口", currentTime - start >= BootstrapManager.HEALTH_WINDOW_MILLIS)
        assertNotNull(store.value.pinnedPublicKey) // 已确认的公钥保留
    }

    @Test fun recoversWithinWindow() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply {
            health[A] = mutableListOf(
                HealthResult.Unreachable("dns"), HealthResult.Unreachable("dns"), HealthResult.Ok(null, 1),
            )
        }
        val out = manager(store, probe).applyUri(signer.uri(A, "wss://a/mqtt", 1))
        assertTrue(out is ApplyOutcome.Applied)
        assertEquals(3, probe.healthCalls)
        assertTrue(currentTime < BootstrapManager.HEALTH_WINDOW_MILLIS)
    }

    @Test fun firstConfigFailureAlsoDropsProvisionalPin() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw)
        val out = manager(store, probe).applyUri(signer.uri(A, "wss://a/mqtt", 1))
        assertTrue(out is ApplyOutcome.RolledBack)
        assertNull(store.value.current)
        assertNull(store.value.pinnedPublicKey)
    }

    @Test fun incompatibleProtocolRollsBackImmediately() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply {
            health[A] = mutableListOf(HealthResult.Ok(null, 1))
            health[B] = mutableListOf(HealthResult.Incompatible("protocol 2"))
        }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val start = currentTime
        val out = m.applyConfirmed(signer.uri(B, "wss://b/mqtt", 2))
        assertTrue(out is ApplyOutcome.RolledBack)
        assertEquals(0L, currentTime - start)
    }

    @Test fun rejectsVersionDowngrade() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply {
            health[A] = mutableListOf(HealthResult.Ok(null, 1)); health[B] = mutableListOf(HealthResult.Ok(null, 1))
        }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 5))
        val out = m.applyConfirmed(signer.uri(B, "wss://b/mqtt", 4))
        assertTrue(out is ApplyOutcome.Rejected && out.error is BootstrapError.Downgrade)
    }

    @Test fun verifyPendingResumesAfterProcessDeath() = runTest {
        // 模拟：上次写入试用配置后进程被杀，previous 仍保存着
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply {
            health[A] = mutableListOf(HealthResult.Ok(null, 1))
        }
        val m = manager(store, probe)
        m.applyUri(signer.uri(A, "wss://a/mqtt", 1))
        val a = store.value.current!!
        store.save(store.value.copy(current = a.copy(baseUrl = B), previous = a, pendingSince = currentTime))
        val r = m.verifyPending()
        assertNotNull(r)
        assertEquals(A, store.value.current?.baseUrl)
    }

    @Test fun manualConfigSkipsSignatureButStillHealthChecks() = runTest {
        val store = FakeStore()
        val probe = FakeProbe(signer.pubRaw).apply { health[A] = mutableListOf(HealthResult.Ok(null, 1)) }
        val out = manager(store, probe).applyManual(A, "wss://a/mqtt")
        assertTrue(out is ApplyOutcome.Applied)
        assertEquals(false, store.value.current?.signed)
        assertEquals(0, probe.pubkeyCalls)
    }
}
