package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.release.ApkSigningCert
import com.qimingnan.couple.backend.release.AppRelease
import com.qimingnan.couple.backend.release.ByteRangeSel
import com.qimingnan.couple.backend.release.ReleaseStatus
import com.qimingnan.couple.backend.release.UpdatePolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReleaseTest {

    private suspend fun HttpClient.putSettings(token: String, body: JsonObject) = put("/api/v1/admin/releases/settings") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.patchRelease(token: String, vc: Int, body: JsonObject) = patch("/api/v1/admin/releases/$vc") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.sync(token: String): JsonObject {
        val r = post("/api/v1/admin/releases/sync?wait=true") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.json()
    }

    private suspend fun HttpClient.update(token: String, vc: Int, device: String = "device-1"): JsonObject {
        val r = get("/api/v1/app/update?versionCode=$vc&deviceId=$device") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.json()
    }

    private suspend fun HttpResponse.jsonArray(): JsonArray = AppJson.parseToJsonElement(bodyAsText()).jsonArray

    @Test
    fun mirrorPinsCertThenPublishAndDownloadWithRange() {
        FakeGitHub().use { gh ->
            val dir = Files.createTempDirectory("rel").toFile()
            appTest(testConfig(githubApiBase = gh.base, releasesDir = dir.path)) { client ->
                val admin = client.register("admin")
                val user = client.register("bob")
                assertEquals(200, client.putSettings(admin.accessToken, obj("repo" to "o/r", "githubToken" to "ghp_secret_token_value_1234")).status.value)

                val st = client.sync(admin.accessToken)
                assertEquals("ok", st["lastResult"]!!.jsonPrimitive.content, st.toString())
                assertEquals(listOf(10001), st["mirrored"]!!.jsonArray.map { it.jsonPrimitive.int })

                val list = client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.jsonArray()
                assertEquals(1, list.size)
                val row = list[0].jsonObject
                assertEquals("MIRRORED", row["status"]!!.jsonPrimitive.content)
                assertEquals(sha256Hex(gh.apk), row["sha256"]!!.jsonPrimitive.content)
                assertTrue(row["fileExists"]!!.jsonPrimitive.boolean)
                assertTrue(File(dir, "couple-1.0.1.apk").isFile)

                // Cert pinned from the first release (TOFU) and audit-logged; token masked.
                val settings = client.get("/api/v1/admin/releases/settings") { bearerAuth(admin.accessToken) }
                val sText = settings.bodyAsText()
                assertFalse(sText.contains("ghp_secret_token_value_1234"))
                assertEquals("****1234", settings.json()["githubToken"]!!.jsonPrimitive.content)
                assertEquals(gh.claimedCert, settings.json()["expectedSigningCertSha256"]!!.jsonPrimitive.content)
                val audit = client.get("/api/v1/admin/audit?limit=200") { bearerAuth(admin.accessToken) }.jsonArray()
                    .map { it.jsonObject["action"]!!.jsonPrimitive.content }
                assertTrue("release.cert_pinned" in audit && "release.mirrored" in audit && "admin.release.sync" in audit, audit.toString())

                // Not published yet -> no update; the file is not downloadable either.
                assertEquals(JsonNull, client.update(user.accessToken, 100)["latest"])
                assertEquals(404, client.get("/api/v1/app/releases/10001/apk") { bearerAuth(user.accessToken) }.status.value)

                val p = client.patchRelease(admin.accessToken, 10001, obj("status" to "PUBLISHED"))
                assertEquals(200, p.status.value, p.bodyAsText())
                val upd = client.update(user.accessToken, 100)
                val latest = upd["latest"]!!.jsonObject
                assertEquals(10001, latest["versionCode"]!!.jsonPrimitive.int)
                assertEquals("1.0.1", latest["versionName"]!!.jsonPrimitive.content)
                assertEquals(gh.claimedCert, latest["signingCertSha256"]!!.jsonPrimitive.content)
                assertEquals("/api/v1/app/releases/10001/apk", latest["downloadUrl"]!!.jsonPrimitive.content)
                assertEquals(gh.apk.size.toLong(), latest["size"]!!.jsonPrimitive.long)
                assertFalse(upd["force"]!!.jsonPrimitive.boolean)
                // Already on it -> nothing.
                assertEquals(JsonNull, client.update(user.accessToken, 10001)["latest"])

                // Download: full, ranges, validators.
                val url = "/api/v1/app/releases/10001/apk"
                assertEquals(401, client.get(url).status.value)
                val full = client.get(url) { bearerAuth(user.accessToken) }
                assertEquals(200, full.status.value)
                assertEquals("\"${sha256Hex(gh.apk)}\"", full.headers["ETag"])
                assertEquals("bytes", full.headers["Accept-Ranges"])
                assertEquals(gh.apk.size.toString(), full.headers["Content-Length"])
                assertTrue(full.bodyAsBytes().contentEquals(gh.apk))

                val part = client.get(url) { bearerAuth(user.accessToken); header("Range", "bytes=10-19") }
                assertEquals(206, part.status.value)
                assertEquals("bytes 10-19/${gh.apk.size}", part.headers["Content-Range"])
                assertEquals("10", part.headers["Content-Length"])
                assertTrue(part.bodyAsBytes().contentEquals(gh.apk.copyOfRange(10, 20)))

                val resume = client.get(url) { bearerAuth(user.accessToken); header("Range", "bytes=100-"); header("If-Range", full.headers["ETag"]!!) }
                assertEquals(206, resume.status.value)
                assertTrue(resume.bodyAsBytes().contentEquals(gh.apk.copyOfRange(100, gh.apk.size)))

                val stale = client.get(url) { bearerAuth(user.accessToken); header("Range", "bytes=100-"); header("If-Range", "\"other\"") }
                assertEquals(200, stale.status.value)

                val bad = client.get(url) { bearerAuth(user.accessToken); header("Range", "bytes=${gh.apk.size + 5}-") }
                assertEquals(416, bad.status.value)
                assertEquals("bytes */${gh.apk.size}", bad.headers["Content-Range"])

                val nm = client.get(url) { bearerAuth(user.accessToken); header("If-None-Match", full.headers["ETag"]!!) }
                assertEquals(304, nm.status.value)

                // Second sync: nothing new.
                assertEquals(0, client.sync(admin.accessToken)["mirrored"]!!.jsonArray.size)

                // Force rule + withdraw.
                assertEquals(400, client.patchRelease(admin.accessToken, 10001, obj("minSupportedVersionCode" to 20000)).status.value)
                assertEquals(400, client.patchRelease(admin.accessToken, 10001, obj("rolloutPercent" to 101)).status.value)
                assertEquals(400, client.patchRelease(admin.accessToken, 10001, obj("status" to "DELETED")).status.value)
                client.patchRelease(admin.accessToken, 10001, obj("minSupportedVersionCode" to 10001, "rolloutPercent" to 0))
                val forced = client.update(user.accessToken, 100)
                assertTrue(forced["force"]!!.jsonPrimitive.boolean, "below minSupported -> force, rollout ignored")
                assertEquals(10001, forced["latest"]!!.jsonObject["versionCode"]!!.jsonPrimitive.int)
                client.patchRelease(admin.accessToken, 10001, obj("minSupportedVersionCode" to 0))
                assertEquals(JsonNull, client.update(user.accessToken, 100)["latest"], "rollout 0% -> nobody")
                client.patchRelease(admin.accessToken, 10001, obj("status" to "WITHDRAWN", "rolloutPercent" to 100))
                assertEquals(JsonNull, client.update(user.accessToken, 100)["latest"])
                assertEquals(404, client.get(url) { bearerAuth(user.accessToken) }.status.value)
            }
            // With a token the mirror authenticates and uses the API asset URLs (private-repo friendly).
            assertTrue("/repos/o/r/releases [auth]" in gh.requests, gh.requests.toString())
            assertTrue("/api-asset/apk [auth]" in gh.requests, gh.requests.toString())
        }
    }

    @Test
    fun autoPublishAndShaMismatchRejected() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r", "autoPublish" to true))

                gh.manifestOverride = { it.replace(sha256Hex(gh.apk), "0".repeat(64)) }
                val st = client.sync(admin.accessToken)
                assertEquals(listOf("10001: SHA256_MISMATCH"), st["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(0, client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.jsonArray().size)

                // Size mismatch is rejected too.
                gh.manifestOverride = { it.replace("\"size\":${gh.apk.size}", "\"size\":${gh.apk.size - 1}") }
                assertEquals(listOf("10001: SIZE_MISMATCH"), client.sync(admin.accessToken)["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Fixed upstream -> mirrored and, with auto-publish, published right away.
                gh.manifestOverride = null
                val ok = client.sync(admin.accessToken)
                assertEquals(listOf(10001), ok["mirrored"]!!.jsonArray.map { it.jsonPrimitive.int })
                val row = client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.jsonArray()[0].jsonObject
                assertEquals("PUBLISHED", row["status"]!!.jsonPrimitive.content)
                val audit = client.get("/api/v1/admin/audit?limit=200") { bearerAuth(admin.accessToken) }.bodyAsText()
                assertTrue(audit.contains("release.rejected"))
            }
        }
    }

    @Test
    fun certMismatchRejected() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r"))

                // release.json claims a cert that is not the one inside the APK.
                gh.claimedCert = sha256Hex("CERT-B".toByteArray())
                var st = client.sync(admin.accessToken)
                assertEquals(listOf("10001: CERT_MISMATCH"), st["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })
                // Nothing was pinned by a rejected release.
                assertEquals(JsonNull, client.get("/api/v1/admin/releases/settings") { bearerAuth(admin.accessToken) }.json()["expectedSigningCertSha256"])

                // APK + claim consistent, but a different cert is pinned -> rejected.
                gh.apk = fakeSignedApk("CERT-B".toByteArray(), schemeId = 0xf05368c0.toInt())
                val pinA = sha256Hex("CERT-A".toByteArray()).chunked(2).joinToString(":").uppercase()
                assertEquals(200, client.putSettings(admin.accessToken, obj("expectedSigningCertSha256" to pinA)).status.value)
                st = client.sync(admin.accessToken)
                assertEquals(listOf("10001: CERT_MISMATCH"), st["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Unsigned APK -> rejected.
                val plain = java.io.ByteArrayOutputStream().also { b ->
                    java.util.zip.ZipOutputStream(b).use { z -> z.putNextEntry(java.util.zip.ZipEntry("a")); z.write(1); z.closeEntry() }
                }.toByteArray()
                gh.apk = plain
                st = client.sync(admin.accessToken)
                assertEquals(listOf("10001: APK_UNSIGNED"), st["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(0, client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.jsonArray().size)

                assertEquals(400, client.putSettings(admin.accessToken, obj("expectedSigningCertSha256" to "nothex")).status.value)
                assertEquals(400, client.putSettings(admin.accessToken, obj("pollMinutes" to 1)).status.value)
                assertEquals(400, client.putSettings(admin.accessToken, obj("repo" to "no-slash")).status.value)
            }
        }
    }

    @Test
    fun githubUnreachableIsReportedNotThrown() = appTest { client ->
        val admin = client.register("admin")
        val st = client.sync(admin.accessToken)
        assertEquals("error", st["lastResult"]!!.jsonPrimitive.content)
        assertNotNull(st["lastError"]!!.jsonPrimitive.content)
        val get = client.get("/api/v1/admin/releases/sync") { bearerAuth(admin.accessToken) }.json()
        assertEquals("error", get["lastResult"]!!.jsonPrimitive.content)
    }

    @Test
    fun adminAuthAndUpdaterStatus() = appTest(testConfig(mqttHookSecret = "hook-secret")) { client ->
        val admin = client.register("admin")
        val user = client.register("bob")
        val u = user.accessToken
        assertEquals(403, client.get("/api/v1/admin/releases") { bearerAuth(u) }.status.value)
        assertEquals(403, client.post("/api/v1/admin/releases/sync") { bearerAuth(u) }.status.value)
        assertEquals(403, client.get("/api/v1/admin/releases/settings") { bearerAuth(u) }.status.value)
        assertEquals(403, client.putSettings(u, obj("autoPublish" to true)).status.value)
        assertEquals(403, client.patchRelease(u, 1, obj("status" to "PUBLISHED")).status.value)
        assertEquals(403, client.get("/api/v1/admin/server/update-status") { bearerAuth(u) }.status.value)
        assertEquals(401, client.get("/api/v1/admin/releases").status.value)
        assertEquals(401, client.get("/api/v1/app/update?versionCode=1").status.value)
        assertEquals(400, client.get("/api/v1/app/update") { bearerAuth(u) }.status.value)
        assertEquals(404, client.patchRelease(admin.accessToken, 1, obj("status" to "PUBLISHED")).status.value)
        // No releases -> null, deviceId falls back to the token's device.
        val none = client.get("/api/v1/app/update?versionCode=1") { bearerAuth(u) }.json()
        assertEquals(JsonNull, none["latest"])
        assertFalse(none["force"]!!.jsonPrimitive.boolean)

        val status = obj(
            "current" to obj("backend" to "1.2.0", "admin" to "1.2.0"),
            "lastCheck" to 1760000000000L, "lastResult" to "up-to-date", "lastError" to null,
        )
        suspend fun postStatus(token: String?, cf: Boolean = false) = client.post("/api/v1/internal/updater/status") {
            contentType(ContentType.Application.Json)
            if (token != null) header("X-Internal-Token", token)
            if (cf) header("CF-Connecting-IP", "1.2.3.4")
            setBody(status)
        }
        assertEquals(404, postStatus(null).status.value)
        assertEquals(404, postStatus("wrong").status.value)
        assertEquals(404, postStatus("hook-secret", cf = true).status.value)
        assertEquals(204, postStatus("hook-secret").status.value)

        val s = client.get("/api/v1/admin/server/update-status") { bearerAuth(admin.accessToken) }.json()
        assertEquals("1.2.0", s["updater"]!!.jsonObject["current"]!!.jsonObject["backend"]!!.jsonPrimitive.content)
        assertNotNull(s["backend"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue(s["updaterReportedAt"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun updaterStatusDisabledWithoutSecret() = appTest { client ->
        val r = client.post("/api/v1/internal/updater/status") {
            contentType(ContentType.Application.Json); header("X-Internal-Token", ""); setBody(obj("a" to 1))
        }
        assertEquals(404, r.status.value)
    }

    private fun rel(vc: Int, rollout: Int = 100, min: Int = 0, status: ReleaseStatus = ReleaseStatus.PUBLISHED) = AppRelease(
        id = "id$vc", versionCode = vc, versionName = "v$vc", sha256 = "0".repeat(64), size = 1, signingCertSha256 = "a".repeat(64),
        minSupportedVersionCode = min, notes = null, publishedAt = 0, filePath = "/x", status = status, rolloutPercent = rollout, createdAt = 0,
    )

    @Test
    fun updatePolicyRolloutAndForce() {
        // Bucket is deterministic, in range, and roughly uniform.
        assertEquals(UpdatePolicy.bucket("dev", 5), UpdatePolicy.bucket("dev", 5))
        val buckets = (0 until 2000).map { UpdatePolicy.bucket("device-$it", 10001) }
        assertTrue(buckets.all { it in 0..99 })
        val inHalf = buckets.count { it < 50 }
        assertTrue(inHalf in 850..1150, "~50% expected, got $inHalf")

        val dev = (0 until 1000).map { "d$it" }.first { UpdatePolicy.bucket(it, 3) >= 50 && UpdatePolicy.bucket(it, 2) < 50 }
        val list = listOf(rel(1), rel(2, rollout = 50), rel(3, rollout = 50))
        // Not in 3's rollout, but in 2's -> gets 2.
        assertEquals(2, UpdatePolicy.decide(list, 1, dev).release?.versionCode)
        assertEquals(null, UpdatePolicy.decide(list, 2, dev).release)
        assertEquals(null, UpdatePolicy.decide(listOf(rel(3, rollout = 0)), 1, dev).release)
        assertEquals(3, UpdatePolicy.decide(listOf(rel(3, rollout = 100)), 1, dev).release?.versionCode)
        // Non-published rows are ignored.
        assertEquals(null, UpdatePolicy.decide(listOf(rel(3, status = ReleaseStatus.WITHDRAWN)), 1, dev).release)
        // Force ignores rollout and picks the newest published.
        val f = UpdatePolicy.decide(listOf(rel(2), rel(3, rollout = 0, min = 2)), 1, dev)
        assertTrue(f.force)
        assertEquals(3, f.release?.versionCode)
        assertFalse(UpdatePolicy.decide(listOf(rel(3, min = 2)), 2, dev).force)
        assertEquals(UpdatePolicy.Decision(null, false), UpdatePolicy.decide(emptyList(), 1, dev))
    }

    @Test
    fun rangeParsingAndApkCertExtraction() {
        assertEquals(ByteRangeSel.Full, ByteRangeSel.parse(null, 100))
        assertEquals(ByteRangeSel.Partial(0, 9), ByteRangeSel.parse("bytes=0-9", 100))
        assertEquals(ByteRangeSel.Partial(90, 99), ByteRangeSel.parse("bytes=-10", 100))
        assertEquals(ByteRangeSel.Partial(50, 99), ByteRangeSel.parse("bytes=50-1000", 100))
        assertEquals(ByteRangeSel.Unsatisfiable, ByteRangeSel.parse("bytes=100-", 100))
        assertEquals(ByteRangeSel.Full, ByteRangeSel.parse("bytes=0-1,5-6", 100))
        assertEquals(ByteRangeSel.Full, ByteRangeSel.parse("items=0-1", 100))

        val f = File.createTempFile("apk", ".apk")
        f.writeBytes(fakeSignedApk("MY-CERT".toByteArray(), schemeId = 0xf05368c0.toInt()))
        assertEquals(sha256Hex("MY-CERT".toByteArray()), ApkSigningCert.certSha256(f))
        f.delete()
    }
}
