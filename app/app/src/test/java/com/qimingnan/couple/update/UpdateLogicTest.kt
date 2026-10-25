package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.UpdateLogic
import com.qimingnan.couple.data.update.UpdateLogic.CertCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateLogicTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun versionComparison() {
        assertEquals(10203, UpdateLogic.versionCodeOf("1.2.3"))
        assertEquals(100, UpdateLogic.versionCodeOf("0.1.0"))
        assertNull(UpdateLogic.versionCodeOf("1.2"))
        assertNull(UpdateLogic.versionCodeOf("1.100.0"))
        assertTrue(UpdateLogic.compareVersionNames("1.10.0", "1.9.3") > 0)
        assertTrue(UpdateLogic.compareVersionNames("v2.0", "1.99.99") > 0)
        assertEquals(0, UpdateLogic.compareVersionNames("1.2", "1.2.0"))
        assertTrue(UpdateLogic.compareVersionNames("1.2.3", "1.2.4") < 0)
    }

    @Test
    fun parsingIsRolloutIndependent() {
        // A device outside the rollout simply gets latest=null; unknown fields are ignored.
        val none = UpdateLogic.parseResponse("""{"latest":null,"force":false,"extra":1}""")
        assertNull(none.latest)
        assertFalse(none.force)
        val r = UpdateLogic.parseResponse(
            """{"latest":{"versionName":"1.0.1","versionCode":10001,"sha256":"$a","size":123,"signingCertSha256":"$b",
               "notes":null,"downloadUrl":"/api/v1/app/releases/10001/apk","publishedAt":5,"future":"x"},"force":true}""",
        )
        assertEquals(10001, r.latest!!.versionCode)
        assertTrue(r.force)
        assertNull(r.latest!!.notes)
        assertTrue(UpdateLogic.isNewer(r.latest, 10000))
        assertFalse(UpdateLogic.isNewer(r.latest, 10001))
        assertFalse(UpdateLogic.isNewer(null, 1))
        assertEquals(UpdateLogic.parseResponse("{}").latest, null)
    }

    @Test
    fun autoCheckDebounce() {
        val h = 60 * 60 * 1000L
        assertTrue(UpdateLogic.shouldAutoCheck(0, 1000))
        assertFalse(UpdateLogic.shouldAutoCheck(10 * h, 15 * h))
        assertTrue(UpdateLogic.shouldAutoCheck(10 * h, 16 * h))
        assertTrue(UpdateLogic.shouldAutoCheck(10 * h, 5 * h)) // clock went backwards
    }

    @Test
    fun downloadUrlMustBeSameOrigin() {
        assertEquals(
            "https://c.example.com/api/v1/app/releases/5/apk",
            UpdateLogic.resolveDownloadUrl("https://c.example.com", "/api/v1/app/releases/5/apk").toString(),
        )
        assertEquals(
            "https://c.example.com/api/v1/app/releases/5/apk",
            UpdateLogic.resolveDownloadUrl("https://c.example.com/", "https://c.example.com/api/v1/app/releases/5/apk").toString(),
        )
        assertNull(UpdateLogic.resolveDownloadUrl("https://c.example.com", "https://evil.example.net/x.apk"))
        assertNull(UpdateLogic.resolveDownloadUrl("https://c.example.com", "http://c.example.com/x.apk"))
        assertNull(UpdateLogic.resolveDownloadUrl("not a url", "/x"))
    }

    @Test
    fun sha256Verification() {
        val f = File.createTempFile("sha", ".bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateLogic.sha256Hex(f))
        f.delete()
        assertEquals(a, UpdateLogic.normalizeSha256(a.uppercase().chunked(2).joinToString(":")))
        assertNull(UpdateLogic.normalizeSha256("abc"))
        assertNull(UpdateLogic.normalizeSha256(null))
    }

    @Test
    fun certificateComparison() {
        assertEquals(CertCheck.Ok, UpdateLogic.checkCertificates(setOf(a), setOf(a), a))
        assertEquals(CertCheck.Ok, UpdateLogic.checkCertificates(setOf(a), setOf(a), a.uppercase()))
        // Foreign APK (different signer than the installed app) is refused even if the server vouches for it.
        assertTrue(UpdateLogic.checkCertificates(setOf(a), setOf(b), b) is CertCheck.Rejected)
        // Server claims a different cert than the APK carries.
        assertTrue(UpdateLogic.checkCertificates(setOf(a), setOf(a), b) is CertCheck.Rejected)
        // Missing data.
        assertTrue(UpdateLogic.checkCertificates(setOf(a), emptySet(), a) is CertCheck.Rejected)
        assertTrue(UpdateLogic.checkCertificates(emptySet(), setOf(a), a) is CertCheck.Rejected)
        assertTrue(UpdateLogic.checkCertificates(setOf(a), setOf(a), "nope") is CertCheck.Rejected)
        // Multi-signer sets must match exactly.
        assertTrue(UpdateLogic.checkCertificates(setOf(a), setOf(a, b), a) is CertCheck.Rejected)
        assertEquals(CertCheck.Ok, UpdateLogic.checkCertificates(setOf(a, b), setOf(b, a), b))
    }

    @Test
    fun antiDowngradeHighestSeen() {
        // Installed 100, never saw anything newer: 101 ok, 100/99 rejected.
        assertTrue(UpdateLogic.acceptOffer(101, 100, 100))
        assertFalse(UpdateLogic.acceptOffer(100, 100, 100))
        assertFalse(UpdateLogic.acceptOffer(99, 100, 100))
        // A verified manifest for 105 was seen; a (replayed, older but still newer-than-installed) 103 is refused.
        val seen = UpdateLogic.nextHighestSeen(100, 100, 105)
        assertEquals(105, seen)
        assertFalse(UpdateLogic.acceptOffer(103, 100, seen))
        assertTrue(UpdateLogic.acceptOffer(105, 100, seen))
        assertTrue(UpdateLogic.acceptOffer(106, 100, seen))
        // Unverified offers never raise the mark; installing raises it.
        assertEquals(105, UpdateLogic.nextHighestSeen(105, 100, null))
        assertEquals(110, UpdateLogic.nextHighestSeen(105, 110, null))
    }

    @Test
    fun reasonTexts() {
        assertTrue(UpdateLogic.reasonText("PEER_PROTOCOL", null)!!.contains("通信协议"))
        assertTrue(UpdateLogic.reasonText("PEER_NEWER", null)!!.contains("对方"))
        assertNull(UpdateLogic.reasonText(null, null))
    }
}
