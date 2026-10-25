package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.release.ManifestSignature
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OTA v2 (docs/ota-design.md): signed manifest, anti-downgrade, partner gate, staged rollout + health. */
class OtaV2Test {

    internal suspend fun HttpClient.sync(token: String): JsonObject {
        val r = post("/api/v1/admin/releases/sync?wait=true") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.json()
    }

    internal suspend fun HttpClient.putSettings(token: String, body: JsonObject) = put("/api/v1/admin/releases/settings") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    internal suspend fun HttpClient.patchRelease(token: String, vc: Int, body: JsonObject) = patch("/api/v1/admin/releases/$vc") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    internal suspend fun HttpClient.update(token: String, vc: Int, extra: String = ""): JsonObject {
        val r = get("/api/v1/app/update?versionCode=$vc$extra") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.json()
    }

    // ---------------- 1. signed manifest ----------------

    @Test
    fun opensslScriptVectorVerifies() {
        // Produced by deploy/scripts/ota-sign.sh (OpenSSL) over the bytes "{\"a\":1}\n".
        val pub = "b78RIFY/vpGop+EHB8apnzoVcQj8eJSsba6Hu5nv5Ww="
        val sig = "yjmhFEVMwrf/rKVoiauRnrHfUB4gOXOSHDhUA+26rbGD13VrCIwSRCqomV/tl2aEg6+DLj4TFgWbDKSgR1oBAw=="
        assertTrue(ManifestSignature.verify(pub, "{\"a\":1}\n".toByteArray(), sig))
        assertFalse(ManifestSignature.verify(pub, "{\"a\":2}\n".toByteArray(), sig))
        assertFalse(ManifestSignature.isValidPublicKey("AAAA"))
    }

    @Test
    fun signedManifestIsStoredAndServedByteExact() {
        val key = TestReleaseKey()
        FakeGitHub().use { gh ->
            gh.signer = key::sign
            gh.extraFields = ""","schema":2,"packageName":"com.qimingnan.couple","protocolVersion":2,"minPeerProtocol":1,"minSdk":26,"rollout":"all""""
            val dir = Files.createTempDirectory("rel").toFile()
            appTest(testConfig(githubApiBase = gh.base, releasesDir = dir.path, otaPublicKey = key.publicKeyB64)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r", "autoPublish" to true))
                val st = client.sync(admin.accessToken)
                assertEquals(listOf(10001), st["mirrored"]!!.jsonArray.map { it.jsonPrimitive.int }, st.toString())

                val latest = client.update(admin.accessToken, 10000)["latest"]!!.jsonObject
                val bytes = Base64.getDecoder().decode(latest["manifest"]!!.jsonPrimitive.content)
                assertContentEquals(gh.manifest().toByteArray(), bytes)
                val sig = latest["manifestSig"]!!.jsonPrimitive.content
                assertEquals(key.sign(gh.manifest().toByteArray()).length, sig.length)
                assertTrue(ManifestSignature.verify(key.publicKeyB64, bytes, sig))
                assertEquals(2, latest["protocolVersion"]!!.jsonPrimitive.int)
                assertEquals(1, latest["minPeerProtocol"]!!.jsonPrimitive.int)

                val row = client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.let { AppJson.parseToJsonElement(it.bodyAsText()).jsonArray[0].jsonObject }
                assertTrue(row["signed"]!!.jsonPrimitive.boolean)
                assertEquals(26, row["minSdk"]!!.jsonPrimitive.int)
                assertContentEquals(gh.manifest().toByteArray(), dir.resolve("couple-1.0.1.release.json").readBytes())
            }
        }
    }

    @Test
    fun pinnedKeyRejectsMissingOrForgedSignature() {
        val key = TestReleaseKey()
        val other = TestReleaseKey()
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base, otaPublicKey = key.publicKeyB64)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r"))
                val missing = client.sync(admin.accessToken)
                assertEquals(listOf("10001: SIGNATURE_MISSING"), missing["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })

                gh.signer = other::sign
                val forged = client.sync(admin.accessToken)
                assertEquals(listOf("10001: SIGNATURE_INVALID"), forged["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Signature over different bytes (e.g. server re-serialised the JSON) is also invalid.
                gh.signer = { b -> key.sign(b + ' '.code.toByte()) }
                val reserialised = client.sync(admin.accessToken)
                assertEquals(listOf("10001: SIGNATURE_INVALID"), reserialised["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })

                gh.signer = key::sign
                val ok = client.sync(admin.accessToken)
                assertEquals(listOf(10001), ok["mirrored"]!!.jsonArray.map { it.jsonPrimitive.int }, ok.toString())
            }
        }
    }

    @Test
    fun unsignedReleaseStillMirrorsWithoutServerKey() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r", "autoPublish" to true))
                client.sync(admin.accessToken)
                val latest = client.update(admin.accessToken, 10000)["latest"]!!.jsonObject
                assertEquals(JsonNull, latest["manifestSig"])
                assertContentEquals(gh.manifest().toByteArray(), Base64.getDecoder().decode(latest["manifest"]!!.jsonPrimitive.content))
            }
        }
    }

    @Test
    fun invalidProtocolFieldsAreRejected() {
        FakeGitHub().use { gh ->
            gh.extraFields = ""","protocolVersion":1,"minPeerProtocol":2"""
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin")
                client.putSettings(admin.accessToken, obj("repo" to "o/r"))
                val st = client.sync(admin.accessToken)
                assertEquals(listOf("10001: INVALID_METADATA"), st["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })
            }
        }
    }

    // ---------------- shared helpers for 2-4 ----------------

    /** Serves [vc] (versionName derived) from the fake GitHub and mirrors it. */
    private suspend fun HttpClient.mirror(gh: FakeGitHub, admin: String, vc: Int, extra: String = "", minSupported: Int = 0): JsonObject {
        gh.versionCode = vc
        gh.version = "${vc / 10000}.${vc / 100 % 100}.${vc % 100}"
        gh.apk = fakeSignedApk("CERT-A".toByteArray(), payload = "build-$vc")
        gh.extraFields = extra
        gh.minSupported = minSupported
        val st = sync(admin)
        assertTrue(st["mirrored"]!!.jsonArray.map { it.jsonPrimitive.int }.contains(vc), st.toString())
        return st
    }

    private suspend fun HttpClient.pair(a: String, b: String) {
        val code = postJson("/api/v1/pair/code", token = a).json()["code"]!!.jsonPrimitive.content
        assertEquals(200, postJson("/api/v1/pair/join", obj("code" to code), b).status.value)
    }

    private suspend fun HttpClient.releaseRow(admin: String, vc: Int): JsonObject =
        AppJson.parseToJsonElement(get("/api/v1/admin/releases") { bearerAuth(admin) }.bodyAsText()).jsonArray
            .map { it.jsonObject }.first { it["versionCode"]!!.jsonPrimitive.int == vc }

    private fun JsonObject.latestVc(): Int? = (this["latest"] as? JsonObject)?.get("versionCode")?.jsonPrimitive?.int

    private fun JsonObject.str(k: String): String? = this[k]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    // ---------------- 2. anti-downgrade ----------------

    @Test
    fun serverRefusesToPublishBelowHighWater() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                client.putSettings(admin, obj("repo" to "o/r"))
                client.mirror(gh, admin, 10002)
                client.mirror(gh, admin, 10001)
                assertEquals(200, client.patchRelease(admin, 10002, obj("status" to "PUBLISHED")).status.value)
                val down = client.patchRelease(admin, 10001, obj("status" to "PUBLISHED"))
                assertEquals(409, down.status.value)
                assertEquals("DOWNGRADE_REFUSED", down.json()["code"]!!.jsonPrimitive.content)
                // Withdrawing the newest does not lower the mark; re-publishing the same version is fine.
                assertEquals(200, client.patchRelease(admin, 10002, obj("status" to "WITHDRAWN")).status.value)
                assertEquals(409, client.patchRelease(admin, 10001, obj("status" to "PUBLISHED")).status.value)
                assertEquals(200, client.patchRelease(admin, 10002, obj("status" to "PUBLISHED")).status.value)
                // Auto-publish of a lower version only mirrors it.
                client.putSettings(admin, obj("autoPublish" to true))
                client.mirror(gh, admin, 10000)
                assertEquals("MIRRORED", client.releaseRow(admin, 10000)["status"]!!.jsonPrimitive.content)
                client.mirror(gh, admin, 10003)
                assertEquals("PUBLISHED", client.releaseRow(admin, 10003)["status"]!!.jsonPrimitive.content)
            }
        }
    }

    // ---------------- 3. device report + partner gate ----------------

    @Test
    fun partnerProtocolBreakForcesTheLaggingPhoneOnly() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val alice = client.register("alice").accessToken
                val bob = client.register("bob").accessToken
                client.pair(alice, bob)
                client.putSettings(admin, obj("repo" to "o/r"))
                client.mirror(gh, admin, 10001, extra = ""","protocolVersion":2,"minPeerProtocol":2""")
                assertEquals(200, client.patchRelease(admin, 10001, obj("status" to "PUBLISHED", "rolloutPercent" to 0)).status.value)

                // Nobody reported yet: no peer, rollout 0 -> nothing offered.
                val a0 = client.update(alice, 10000, "&protocolVersion=1&minPeerProtocol=1&sdkInt=34")
                assertEquals(null, a0.latestVc())
                assertEquals(JsonNull, a0["peer"])

                // Bob installed 10001 (e.g. sideloaded) and reports protocol 2 / minPeer 2.
                val b = client.update(bob, 10001, "&protocolVersion=2&minPeerProtocol=2&sdkInt=33")
                assertEquals(null, b.latestVc())
                val bp = b["peer"]!!.jsonObject
                assertEquals(10000, bp["versionCode"]!!.jsonPrimitive.int)
                assertFalse(bp["compatible"]!!.jsonPrimitive.boolean)

                // Alice can no longer talk to Bob: forced to 10001 despite rollout 0.
                val a1 = client.update(alice, 10000, "&protocolVersion=1&minPeerProtocol=1")
                assertEquals(10001, a1.latestVc())
                assertTrue(a1["force"]!!.jsonPrimitive.boolean)
                assertEquals("PEER_PROTOCOL", a1.str("reason"))

                // Reports are keyed by the token's did, never the query deviceId.
                client.update(alice, 10000, "&deviceId=dev-bob&protocolVersion=1&minPeerProtocol=1")
                val devices = AppJson.parseToJsonElement(client.get("/api/v1/admin/devices") { bearerAuth(admin) }.bodyAsText()).jsonArray.map { it.jsonObject }
                val bobRow = devices.first { it["deviceId"]!!.jsonPrimitive.content == "dev-bob" }
                assertEquals(10001, bobRow["versionCode"]!!.jsonPrimitive.int)
                assertEquals(33, bobRow["sdkInt"]!!.jsonPrimitive.int)
                // sdkInt kept when a later report omits it.
                assertEquals(34, devices.first { it["deviceId"]!!.jsonPrimitive.content == "dev-alice" }["sdkInt"]!!.jsonPrimitive.int)

                assertEquals(400, client.get("/api/v1/app/update?versionCode=1&protocolVersion=0") { bearerAuth(alice) }.status.value)
            }
        }
    }

    @Test
    fun partnerOnNewerCompatibleVersionGivesSoftPrompt() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val alice = client.register("alice").accessToken
                val bob = client.register("bob").accessToken
                client.pair(alice, bob)
                client.putSettings(admin, obj("repo" to "o/r", "autoPublish" to true))
                client.mirror(gh, admin, 10001)
                client.update(bob, 10001, "&protocolVersion=1&minPeerProtocol=1")
                val a = client.update(alice, 10000, "&protocolVersion=1&minPeerProtocol=1")
                assertEquals(10001, a.latestVc())
                assertFalse(a["force"]!!.jsonPrimitive.boolean)
                assertEquals("PEER_NEWER", a.str("reason"))
                assertTrue(a["peer"]!!.jsonObject["compatible"]!!.jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun forcedReleaseBlockedWhenPeerCannotInstallIt() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val alice = client.register("alice").accessToken
                client.putSettings(admin, obj("repo" to "o/r"))
                client.update(alice, 10000, "&protocolVersion=1&minPeerProtocol=1&sdkInt=28")
                client.mirror(gh, admin, 10001, extra = ""","minSdk":30""", minSupported = 10001)
                val r = client.patchRelease(admin, 10001, obj("status" to "PUBLISHED"))
                assertEquals(409, r.status.value)
                val body = r.json()
                assertEquals("PEER_CANNOT_FOLLOW", body["code"]!!.jsonPrimitive.content)
                assertTrue(body["message"]!!.jsonPrimitive.content.contains("dev-alice"))
                assertEquals(200, client.patchRelease(admin, 10001, obj("status" to "PUBLISHED", "overridePeerCheck" to true)).status.value)
            }
        }
    }

    // ---------------- 4. staged rollout + health ----------------

    @Test
    fun canaryFirstThenPromotedOnHealthyReport() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val alice = client.register("alice").accessToken // canary ("me first")
                val bob = client.register("bob").accessToken
                client.putSettings(admin, obj("repo" to "o/r", "autoPublish" to true, "canaryDeviceIds" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dev-alice")))))
                client.mirror(gh, admin, 10001, extra = ""","rollout":"staged"""")
                assertEquals("CANARY", client.releaseRow(admin, 10001)["stage"]!!.jsonPrimitive.content)

                assertEquals(null, client.update(bob, 10000).latestVc())
                assertEquals(10001, client.update(alice, 10000).latestVc())

                // A non-canary OK does not promote; the canary's OK does.
                assertEquals(204, client.health(bob, 10001, "OK").status.value)
                assertEquals("CANARY", client.releaseRow(admin, 10001)["stage"]!!.jsonPrimitive.content)
                assertEquals(204, client.health(alice, 10001, "OK").status.value)
                assertEquals("ALL", client.releaseRow(admin, 10001)["stage"]!!.jsonPrimitive.content)
                assertEquals(10001, client.update(bob, 10000).latestVc())

                val hist = AppJson.parseToJsonElement(client.get("/api/v1/admin/releases/10001/health") { bearerAuth(admin) }.bodyAsText()).jsonArray
                assertEquals(2, hist.size)
            }
        }
    }

    @Test
    fun crashReportAutoPausesAndAdminCanResume() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val alice = client.register("alice").accessToken
                val bob = client.register("bob").accessToken
                client.putSettings(admin, obj("repo" to "o/r", "autoPublish" to true, "canaryDeviceIds" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dev-alice")))))
                client.mirror(gh, admin, 10001, extra = ""","rollout":"staged"""", minSupported = 0)
                assertEquals(400, client.health(alice, 10001, "MAYBE").status.value)

                assertEquals(204, client.health(alice, 10001, "CRASH", "java.lang.IllegalStateException").status.value)
                val row = client.releaseRow(admin, 10001)
                assertTrue(row["pausedAt"] != JsonNull)
                assertTrue(row.str("pauseReason")!!.contains("CRASH from device dev-alice"))
                // Paused: offered to nobody; a later OK does not promote it.
                assertEquals(null, client.update(alice, 10000).latestVc())
                client.health(alice, 10001, "OK")
                assertEquals("CANARY", client.releaseRow(admin, 10001)["stage"]!!.jsonPrimitive.content)
                val audit = client.get("/api/v1/admin/audit?limit=50") { bearerAuth(admin) }.bodyAsText()
                assertTrue(audit.contains("release.auto_paused"), audit)

                // Admin resumes and promotes manually.
                assertEquals(200, client.patchRelease(admin, 10001, obj("paused" to false, "stage" to "ALL")).status.value)
                assertEquals(10001, client.update(bob, 10000).latestVc())
                // Pausing again by hand hides it.
                assertEquals(200, client.patchRelease(admin, 10001, obj("paused" to true)).status.value)
                assertEquals("manual", client.releaseRow(admin, 10001).str("pauseReason"))
                assertEquals(null, client.update(bob, 10000).latestVc())
            }
        }
    }

    @Test
    fun stagedManifestWithoutCanaryGoesToEveryone() {
        FakeGitHub().use { gh ->
            appTest(testConfig(githubApiBase = gh.base)) { client ->
                val admin = client.register("admin").accessToken
                val bob = client.register("bob").accessToken
                client.putSettings(admin, obj("repo" to "o/r", "autoPublish" to true))
                client.mirror(gh, admin, 10001, extra = ""","rollout":"staged"""")
                assertEquals("ALL", client.releaseRow(admin, 10001)["stage"]!!.jsonPrimitive.content)
                assertEquals(10001, client.update(bob, 10000).latestVc())
            }
        }
    }

    private suspend fun HttpClient.health(token: String, vc: Int, status: String, detail: String? = null) =
        postJson("/api/v1/app/health-report", obj("versionCode" to vc, "fromVersionCode" to vc - 1, "status" to status, "detail" to detail, "protocolVersion" to 1, "minPeerProtocol" to 1, "sdkInt" to 34), token)
}
