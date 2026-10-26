package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.admin.AdminConfigService
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.notify.WxPusherService
import com.qimingnan.couple.backend.notify.WxPusherSettings
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OTA §9: WxPusher operator alerts + the canary no-OK-timeout sweep. */
class NotifyTest {

    private suspend fun HttpClient.syncAdmin(token: String) {
        val r = post("/api/v1/admin/releases/sync?wait=true") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
    }

    private suspend fun HttpClient.putConfig(token: String, body: JsonObject) = put("/api/v1/admin/config") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.getConfig(token: String): JsonObject =
        get("/api/v1/admin/config") { bearerAuth(token) }.json()

    private fun svc(
        clock: TestClock = TestClock(),
        settings: WxPusherSettings = WxPusherSettings("app-token", listOf("UID_1")),
        sent: MutableList<Pair<String, String>> = mutableListOf(),
        failSend: Boolean = false,
    ): WxPusherService = WxPusherService(
        testConfig(clock = clock),
        { settings },
    ) { url, body ->
        if (failSend) throw java.io.IOException("boom")
        sent += url to body
        true
    }

    @Test
    fun notifyDebouncesPerKeyAndSkipsUnconfigured() = runBlocking {
        val clock = TestClock()
        val sent = mutableListOf<Pair<String, String>>()
        val s = svc(clock, sent = sent)
        assertTrue(s.notify("k", "s1", "c1"))
        assertFalse(s.notify("k", "s2", "c2")) // same key inside the window
        assertTrue(s.notify("other", "s3", "c3")) // different key is independent
        clock.advance(WxPusherService.DEFAULT_MIN_INTERVAL_MS + 1)
        assertTrue(s.notify("k", "s4", "c4")) // window passed
        assertEquals(3, sent.size)
        val body = AppJson.parseToJsonElement(sent[0].second).jsonObject
        assertEquals("app-token", body["appToken"]!!.jsonPrimitive.content)
        assertEquals("s1", body["summary"]!!.jsonPrimitive.content)
        assertEquals(1, body["contentType"]!!.jsonPrimitive.int)
        assertEquals(listOf("UID_1"), body["uids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun notifyIsNoOpWhenUnconfiguredOrFailing() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val none = svc(settings = WxPusherSettings.NONE, sent = sent)
        assertFalse(none.notify("k", "s", "c"))
        assertFalse(svc(failSend = true).notify("k", "s", "c")) // sender throws → swallowed, false
        assertEquals(0, sent.size)
        // Missing uids (token only) is also unconfigured: no recipient, nothing to send.
        val tokenOnly = svc(settings = WxPusherSettings("app-token", emptyList()), sent = sent)
        assertFalse(tokenOnly.notify("k", "s", "c"))
    }

    @Test
    fun settingsParseFromAdminConfig() {
        val wx = obj("appToken" to "tok-1", "uids" to JsonArray(listOf(JsonPrimitive("U1"), JsonPrimitive("U2"))))
        val s = WxPusherSettings.fromConfig(obj("wxpusher" to wx))
        assertEquals("tok-1", s.appToken)
        assertEquals(listOf("U1", "U2"), s.uids)
        assertTrue(s.configured)
        // Missing section / nulls degrade to NONE instead of throwing.
        assertEquals(WxPusherSettings.NONE, WxPusherSettings.fromConfig(null))
        assertEquals(WxPusherSettings.NONE, WxPusherSettings.fromConfig(obj("wxpusher" to obj("appToken" to null))))
        assertFalse(WxPusherSettings.fromConfig(obj("wxpusher" to obj("appToken" to "t"))).configured)
    }

    @Test
    fun adminConfigMasksAppTokenAndKeepsUids() = appTest { client ->
        val admin = client.register("notify-admin").accessToken

        val first = client.putConfig(
            admin,
            obj("notify" to obj("wxpusher" to obj("appToken" to "wx-test-token-123456", "uids" to JsonArray(listOf(JsonPrimitive("UID_a")))))),
        )
        assertEquals(200, first.status.value, "step1 put: " + first.bodyAsText())
        val wx = client.getConfig(admin)["notify"]!!.jsonObject["wxpusher"]!!.jsonObject
        assertEquals(AdminConfigService.maskValue("wx-test-token-123456"), wx["appToken"]!!.jsonPrimitive.content)
        assertEquals("UID_a", wx["uids"]!!.jsonArray.single().jsonPrimitive.content)

        // Omitting appToken on the next PUT keeps the stored secret (still masked in the view).
        client.putConfig(admin, obj("notify" to obj("wxpusher" to obj("uids" to JsonArray(listOf(JsonPrimitive("UID_a"), JsonPrimitive("UID_b")))))))
        assertEquals(AdminConfigService.maskValue("wx-test-token-123456"), client.getConfig(admin)["notify"]!!.jsonObject["wxpusher"]!!.jsonObject["appToken"]!!.jsonPrimitive.content)

        // "" clears it (the view renders a cleared secret as JSON null).
        client.putConfig(admin, obj("notify" to obj("wxpusher" to obj("appToken" to "", "uids" to JsonArray(listOf(JsonPrimitive("UID_a")))))))
        val cleared = client.getConfig(admin)["notify"]!!.jsonObject["wxpusher"]!!.jsonObject["appToken"]
        assertTrue(cleared == null || cleared is JsonNull, "appToken should be cleared, got $cleared")
    }

    @Test
    fun sweepPausesCanaryReleaseWithoutOkReport() {
        val key = TestReleaseKey()
        FakeGitHub(version = "1.0.2", versionCode = 10002).use { gh ->
            gh.signer = key::sign
            // manifest publishedAt is 2025 (TestClock is 2026): older than the 2 h grace, so the sweep must act.
            gh.extraFields = ""","schema":2,"packageName":"com.qimingnan.couple","rollout":"staged""""
            val dir = Files.createTempDirectory("rel").toFile()
            appTest(testConfig(githubApiBase = gh.base, releasesDir = dir.path, otaPublicKey = key.publicKeyB64)) { client ->
                val admin = client.register("sweep-admin")
                // canary device configured BEFORE sync, so the staged manifest is stored as CANARY.
                // repo must be "o/r": FakeGitHub serves /repos/o/r/releases.
                client.put("/api/v1/admin/releases/settings") {
                    bearerAuth(admin.accessToken); contentType(ContentType.Application.Json)
                    setBody(obj("repo" to "o/r", "canaryDeviceIds" to JsonArray(listOf(JsonPrimitive("canary-dev")))))
                }
                client.syncAdmin(admin.accessToken)
                val pub = client.patch("/api/v1/admin/releases/10002") {
                    bearerAuth(admin.accessToken); contentType(ContentType.Application.Json); setBody(obj("status" to "PUBLISHED"))
                }
                assertEquals(200, pub.status.value, pub.bodyAsText())

                // No health reports at all → auto-paused.
                val r = client.post("/api/v1/admin/releases/sweep") { bearerAuth(admin.accessToken) }
                assertEquals(200, r.status.value, r.bodyAsText())
                assertEquals(listOf(10002), r.json()["paused"]!!.jsonArray.map { it.jsonPrimitive.int })

                val rel = client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }
                    .let { AppJson.parseToJsonElement(it.bodyAsText()).jsonArray }
                    .map { it.jsonObject }
                    .single { it["versionCode"]!!.jsonPrimitive.int == 10002 }
                assertEquals("CANARY", rel["stage"]!!.jsonPrimitive.content)
                assertTrue(rel["pausedAt"]!!.jsonPrimitive.long > 0)
                assertTrue(rel["pauseReason"]!!.jsonPrimitive.content.contains("canary timeout"))

                // Already paused → a second sweep finds nothing (idempotent).
                val r2 = client.post("/api/v1/admin/releases/sweep") { bearerAuth(admin.accessToken) }
                assertEquals(0, r2.json()["paused"]!!.jsonArray.size)
            }
        }
    }

    @Test
    fun sweepKeepsCanaryThatReportedOk() {
        val key = TestReleaseKey()
        FakeGitHub(version = "1.0.3", versionCode = 10003).use { gh ->
            gh.signer = key::sign
            gh.extraFields = ""","schema":2,"packageName":"com.qimingnan.couple","rollout":"staged""""
            val dir = Files.createTempDirectory("rel").toFile()
            appTest(testConfig(githubApiBase = gh.base, releasesDir = dir.path, otaPublicKey = key.publicKeyB64)) { client ->
                val admin = client.register("sweep-admin2")
                client.put("/api/v1/admin/releases/settings") {
                    bearerAuth(admin.accessToken); contentType(ContentType.Application.Json)
                    setBody(obj("repo" to "o/r", "canaryDeviceIds" to JsonArray(listOf(JsonPrimitive("canary-dev")))))
                }
                client.syncAdmin(admin.accessToken)
                client.patch("/api/v1/admin/releases/10003") {
                    bearerAuth(admin.accessToken); contentType(ContentType.Application.Json); setBody(obj("status" to "PUBLISHED"))
                }
                // The canary device reports OK (also registers it in device_reports).
                val report = client.postJson(
                    "/api/v1/app/health-report",
                    obj("versionCode" to 10003, "status" to "OK", "protocolVersion" to 1, "minPeerProtocol" to 1),
                    token = admin.accessToken,
                )
                assertEquals(204, report.status.value)

                val r = client.post("/api/v1/admin/releases/sweep") { bearerAuth(admin.accessToken) }
                assertEquals(0, r.json()["paused"]!!.jsonArray.size)
                val body = client.get("/api/v1/admin/releases") { bearerAuth(admin.accessToken) }.bodyAsText()
                assertFalse(body.contains("canary timeout"))
            }
        }
    }
}
