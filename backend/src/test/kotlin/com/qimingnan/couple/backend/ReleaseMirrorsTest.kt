package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.release.DownloadMirrors
import com.qimingnan.couple.backend.release.ReleaseManifest
import com.qimingnan.couple.backend.release.ReleaseSettings
import com.qimingnan.couple.backend.release.ReleaseSettingsService
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** contract "Auto-update → mirrors": admin mirrors + GitHub accelerators offered in GET /app/update latest.mirrors. */
class ReleaseMirrorsTest {
    private val gh = "https://github.com/o/r/releases/download/v1.0.1/couple-1.0.1.apk"

    @Test
    fun mirrorListOrderDomesticThenAcceleratedThenGitHub() {
        val s = ReleaseSettings(repo = "o/r", mirrors = listOf("https://oss.example.cn/couple"), ghAccelerators = listOf("https://ghfast.top/"))
        assertEquals(
            listOf("https://oss.example.cn/couple/couple-1.0.1.apk", "https://ghfast.top/$gh", gh),
            DownloadMirrors.forRelease(s, "1.0.1"),
        )
        // Defaults: the curl-verified accelerators + GitHub direct.
        val d = DownloadMirrors.forRelease(ReleaseSettings(repo = "o/r"), "1.0.1")
        assertEquals(ReleaseSettings.DEFAULT_GH_ACCELERATORS.size + 1, d.size)
        assertEquals(gh, d.last())
        assertEquals(listOf(gh), DownloadMirrors.forRelease(ReleaseSettings(repo = "o/r", ghAccelerators = emptyList()), "1.0.1"))
    }

    @Test
    fun httpsListValidation() {
        assertEquals(listOf("https://a.cn/x"), ReleaseSettingsService.httpsList("mirrors", listOf(" https://a.cn/x/ ", ""), trailingSlash = false))
        assertEquals(listOf("https://ghfast.top/"), ReleaseSettingsService.httpsList("ghAccelerators", listOf("https://ghfast.top"), trailingSlash = true))
        assertFails { ReleaseSettingsService.httpsList("mirrors", listOf("http://a.cn/"), false) }
        assertFails { ReleaseSettingsService.httpsList("mirrors", listOf("https://a.cn/?x=1"), false) }
        assertFails { ReleaseSettingsService.httpsList("mirrors", listOf("https://u:p@a.cn/"), false) }
        assertFails { ReleaseSettingsService.httpsList("mirrors", listOf("ftp://a.cn"), false) }
        assertFails { ReleaseSettingsService.httpsList("mirrors", (1..6).map { "https://a$it.cn/" }, false) }
    }

    @Test
    fun signedManifestMirrorsParse() {
        val m = AppJson.decodeFromString(
            ReleaseManifest.serializer(),
            """{"versionName":"1.0.1","versionCode":10001,"sha256":"${"a".repeat(64)}","size":1,"signingCertSha256":"${"b".repeat(64)}","mirrors":["$gh"]}""",
        )
        assertEquals(listOf(gh), m.mirrors)
    }

    private suspend fun HttpClient.putSettings(token: String, body: JsonObject) = put("/api/v1/admin/releases/settings") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun updateOffersConfiguredMirrors() {
        FakeGitHub().use { fake ->
            val dir = Files.createTempDirectory("rel").toFile()
            appTest(testConfig(githubApiBase = fake.base, releasesDir = dir.path)) { client ->
                val admin = client.register("admin")
                val user = client.register("bob")
                val bad = client.putSettings(admin.accessToken, obj("mirrors" to JsonArray(listOf(JsonPrimitive("http://oss.example.cn")))))
                assertEquals(400, bad.status.value, bad.bodyAsText())
                val ok = client.putSettings(admin.accessToken, obj(
                    "repo" to "o/r",
                    "mirrors" to JsonArray(listOf(JsonPrimitive("https://oss.example.cn/couple/"))),
                    "ghAccelerators" to JsonArray(listOf(JsonPrimitive("https://gh-proxy.com"))),
                ))
                assertEquals(200, ok.status.value, ok.bodyAsText())
                val view = AppJson.parseToJsonElement(ok.bodyAsText()).jsonObject
                assertEquals(listOf("https://oss.example.cn/couple"), view["mirrors"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals(listOf("https://gh-proxy.com/"), view["ghAccelerators"]!!.jsonArray.map { it.jsonPrimitive.content })

                assertEquals(200, client.post("/api/v1/admin/releases/sync?wait=true") { bearerAuth(admin.accessToken) }.status.value)
                val p = client.patch("/api/v1/admin/releases/10001") {
                    bearerAuth(admin.accessToken); contentType(ContentType.Application.Json); setBody(obj("status" to "PUBLISHED"))
                }
                assertEquals(200, p.status.value, p.bodyAsText())
                val r = client.get("/api/v1/app/update?versionCode=100&deviceId=d1") { bearerAuth(user.accessToken) }
                val latest = AppJson.parseToJsonElement(r.bodyAsText()).jsonObject["latest"]!!.jsonObject
                assertEquals("/api/v1/app/releases/10001/apk", latest["downloadUrl"]!!.jsonPrimitive.content)
                assertEquals(
                    listOf("https://oss.example.cn/couple/couple-1.0.1.apk", "https://gh-proxy.com/$gh", gh),
                    latest["mirrors"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
                val audit = client.get("/api/v1/admin/audit?limit=200") { bearerAuth(admin.accessToken) }.bodyAsText()
                assertTrue(audit.contains("mirrors"), audit)
            }
        }
    }
}
