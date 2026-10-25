package com.qimingnan.couple.update

import com.qimingnan.couple.data.bootstrap.Ed25519Verifier
import com.qimingnan.couple.data.update.ManifestVerifier
import com.qimingnan.couple.data.update.ManifestVerifier.Result
import com.qimingnan.couple.data.update.UpdateInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class ManifestVerifierTest {
    private val b64 = Base64.getEncoder()
    private val sha = "ab".repeat(32)
    private val cert = "cd".repeat(32)
    private val pkg = "com.qimingnan.couple"

    private class Key {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pubB64: String = Base64.getEncoder().encodeToString(priv.generatePublicKey().encoded)
        fun sign(m: ByteArray): String = Ed25519Signer().run {
            init(true, priv); update(m, 0, m.size); Base64.getEncoder().encodeToString(generateSignature())
        }
    }

    private fun manifestJson(vc: Int = 10200, s: String = sha, size: Long = 1234, c: String = cert, p: String = pkg) =
        """{"schema":2,"packageName":"$p","versionName":"1.2.0","versionCode":$vc,"sha256":"$s","size":$size,""" +
            """"signingCertSha256":"$c","minSupportedVersionCode":0,"protocolVersion":2,"minPeerProtocol":1,"minSdk":26,""" +
            """"rollout":"staged","notes":"n","publishedAt":1,"futureField":true}"""

    private fun info(manifest: String?, sig: String?, vc: Int = 10200, s: String = sha, size: Long = 1234, c: String = cert) = UpdateInfo(
        versionName = "1.2.0", versionCode = vc, sha256 = s, size = size, signingCertSha256 = c,
        downloadUrl = "/api/v1/app/releases/$vc/apk", manifest = manifest?.let { b64.encodeToString(it.toByteArray()) }, manifestSig = sig,
    )

    @Test
    fun opensslVectorFromSignScript() {
        // deploy/scripts/ota-sign.sh output over "{\"a\":1}\n" — proves OpenSSL (CI) and BouncyCastle (app) agree.
        val pub = Base64.getDecoder().decode("b78RIFY/vpGop+EHB8apnzoVcQj8eJSsba6Hu5nv5Ww=")
        val sig = Base64.getDecoder().decode("yjmhFEVMwrf/rKVoiauRnrHfUB4gOXOSHDhUA+26rbGD13VrCIwSRCqomV/tl2aEg6+DLj4TFgWbDKSgR1oBAw==")
        assertTrue(Ed25519Verifier.verify(pub, "{\"a\":1}\n".toByteArray(), sig))
        assertFalse(Ed25519Verifier.verify(pub, "{\"a\":1}".toByteArray(), sig))
    }

    @Test
    fun verifiedManifestReturnsSignedFields() {
        val k = Key()
        val m = manifestJson()
        val r = ManifestVerifier.verify(info(m, k.sign(m.toByteArray())), k.pubB64, pkg)
        assertTrue(r.toString(), r is Result.Verified)
        r as Result.Verified
        assertEquals(10200, r.manifest.versionCode)
        assertEquals(2, r.manifest.protocolVersion)
        assertEquals("staged", r.manifest.rollout)
    }

    @Test
    fun noPinnedKeySkips() {
        assertEquals(Result.Skipped, ManifestVerifier.verify(info(null, null), "", pkg))
    }

    @Test
    fun missingOrForgedSignatureIsRejected() {
        val k = Key()
        val other = Key()
        val m = manifestJson()
        assertTrue(ManifestVerifier.verify(info(null, null), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, null), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, other.sign(m.toByteArray())), k.pubB64, pkg) is Result.Rejected)
        // Tampered manifest bytes (signature over the original).
        val sig = k.sign(m.toByteArray())
        assertTrue(ManifestVerifier.verify(info(m.replace("\"size\":1234", "\"size\":1235"), sig, size = 1235), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, "not-base64!"), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, sig), "AAAA", pkg) is Result.Rejected)
    }

    @Test
    fun serverFieldsMustMatchSignedManifest() {
        val k = Key()
        val m = manifestJson()
        val sig = k.sign(m.toByteArray())
        // A compromised server swaps the APK hash / size / cert / version but replays a valid signed manifest.
        assertTrue(ManifestVerifier.verify(info(m, sig, s = "ef".repeat(32)), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, sig, size = 999), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, sig, c = "ef".repeat(32)), k.pubB64, pkg) is Result.Rejected)
        assertTrue(ManifestVerifier.verify(info(m, sig, vc = 10300), k.pubB64, pkg) is Result.Rejected)
        // Uppercase / colon formatting of hex is tolerated.
        val upper = ManifestVerifier.verify(info(m, sig, s = sha.uppercase()), k.pubB64, pkg)
        assertTrue(upper.toString(), upper is Result.Verified)
        // Manifest for another app.
        val foreign = manifestJson(p = "com.evil.app")
        assertTrue(ManifestVerifier.verify(info(foreign, k.sign(foreign.toByteArray())), k.pubB64, pkg) is Result.Rejected)
    }

    @Test
    fun debugPackageMapsToReleasePackage() {
        assertEquals(pkg, ManifestVerifier.releasePackageOf("$pkg.debug"))
        assertEquals(pkg, ManifestVerifier.releasePackageOf(pkg))
    }
}
