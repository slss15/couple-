package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.setup.deriveUrls
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupTest {
    private val token = "setup-token-0123456789"

    private suspend fun HttpClient.setup(body: JsonObject, host: String = "couple.example.com"): HttpResponse = post("/api/v1/setup") {
        contentType(ContentType.Application.Json)
        header("X-Forwarded-Host", host)
        header("X-Forwarded-Proto", "https")
        setBody(body)
    }

    private suspend fun HttpClient.status(): JsonObject = get("/api/v1/setup/status") {
        header("X-Forwarded-Host", "couple.example.com")
        header("X-Forwarded-Proto", "https")
    }.json()

    @Test
    fun statusNeededUntilSetupThenSuccessPersistsDerivedConfig() = appTest(testConfig(setupToken = token)) { client ->
        val st = client.status()
        assertTrue(st["needed"]!!.jsonPrimitive.boolean)
        val sug = st["suggested"]!!.jsonObject
        assertEquals("https://couple.example.com", sug["publicBaseUrl"]!!.jsonPrimitive.content)
        assertEquals("wss://couple.example.com/mqtt", sug["mqttUrl"]!!.jsonPrimitive.content)

        // wrong / missing token
        val wrong = client.setup(obj("token" to "nope", "username" to "root", "password" to "password-123"))
        assertEquals(403, wrong.status.value)
        assertEquals("SETUP_TOKEN_INVALID", wrong.errorCode())
        assertEquals(403, client.setup(obj("username" to "root", "password" to "password-123")).status.value)
        // password policy reused
        val weak = client.setup(obj("token" to token, "username" to "root", "password" to "short"))
        assertEquals(400, weak.status.value)
        assertTrue(client.status()["needed"]!!.jsonPrimitive.boolean)

        val ok = client.setup(obj("token" to token, "username" to "root", "password" to "password-123", "deviceId" to "web-1"))
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val pair = AppJson.decodeFromString(TokenPair.serializer(), ok.bodyAsText())
        assertEquals("root", pair.user.username)

        // the new account is a real admin: admin config works and carries the derived URLs
        val cfg = client.get("/api/v1/admin/config") { bearerAuth(pair.accessToken) }
        assertEquals(200, cfg.status.value, cfg.bodyAsText())
        val c = cfg.json()
        assertEquals("https://couple.example.com", c["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("wss://couple.example.com/mqtt", c["mqttUrl"]!!.jsonPrimitive.content)
        assertEquals(1L, c["configVersion"]!!.jsonPrimitive.long)
        // QR can be generated immediately
        assertEquals(200, client.postJson("/api/v1/admin/bootstrap", token = pair.accessToken).status.value)
        // login with the chosen password works
        val login = client.postJson("/api/v1/auth/login", obj("username" to "root", "password" to "password-123", "deviceId" to "d2"))
        assertEquals(200, login.status.value)

        // second call rejected, status no longer needed
        val again = client.setup(obj("token" to token, "username" to "root2", "password" to "password-123"))
        assertEquals(410, again.status.value)
        assertEquals("SETUP_COMPLETED", again.errorCode())
        val st2 = client.status()
        assertFalse(st2["needed"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, st2["suggested"])
    }

    @Test
    fun auditLogsSetupEvents() = appTest(testConfig(setupToken = token)) { client ->
        client.setup(obj("token" to "bad", "username" to "root", "password" to "password-123"))
        val pair = AppJson.decodeFromString(
            TokenPair.serializer(),
            client.setup(obj("token" to token, "username" to "root", "password" to "password-123")).bodyAsText(),
        )
        val body = client.get("/api/v1/admin/audit?limit=200") { bearerAuth(pair.accessToken) }.bodyAsText()
        val actions = AppJson.parseToJsonElement(body).jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertTrue("setup.completed" in actions, actions.toString())
        assertTrue("setup.failed" in actions, actions.toString())
    }

    @Test
    fun notNeededWhenLegacyAdminExists() = appTest(testConfig(setupToken = token)) { client ->
        client.register("admin")
        assertFalse(client.status()["needed"]!!.jsonPrimitive.boolean)
        val res = client.setup(obj("token" to token, "username" to "root", "password" to "password-123"))
        assertEquals(410, res.status.value)
    }

    @Test
    fun setupDerivesFromForwardedHostWithPort() = appTest(testConfig(setupToken = token)) { client ->
        val ok = client.setup(obj("token" to token, "username" to "root", "password" to "password-123"), host = "Other.Example.com:8443")
        assertEquals(200, ok.status.value, ok.bodyAsText())
        val pair = AppJson.decodeFromString(TokenPair.serializer(), ok.bodyAsText())
        val c = client.get("/api/v1/admin/config") {
            bearerAuth(pair.accessToken)
            header("X-Forwarded-Host", "third.example.com")
        }.json()
        assertEquals("https://other.example.com:8443", c["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("wss://other.example.com:8443/mqtt", c["mqttUrl"]!!.jsonPrimitive.content)
        // GET /admin/config also carries the suggestion for the current request
        assertEquals("wss://third.example.com/mqtt", c["suggested"]!!.jsonObject["mqttUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun generatesTokenWhenUnsetAndWritesFile() {
        val dir = Files.createTempDirectory("data").toFile()
        val config = testConfig(dataDir = dir.absolutePath)
        var service: com.qimingnan.couple.backend.setup.SetupService? = null
        testApplication {
            application {
                module(config)
                service = attributes[SetupServiceKey]
            }
            val client = createClient { install(ContentNegotiation) { json(AppJson) } }
            // the first request starts the application
            assertTrue(client.status()["needed"]!!.jsonPrimitive.boolean)
            val svc = assertNotNull(service)
            val generated = assertNotNull(svc.token)
            assertTrue(svc.generated)
            assertTrue(generated.length >= 24)
            val file = File(dir, "setup-token.txt")
            assertEquals(generated, file.readText().trim())

            assertEquals(403, client.setup(obj("token" to "x", "username" to "root", "password" to "password-123")).status.value)
            val ok = client.setup(obj("token" to generated, "username" to "root", "password" to "password-123"))
            assertEquals(200, ok.status.value, ok.bodyAsText())
            assertFalse(file.exists(), "token file is removed after setup")
            assertNull(svc.token)
        }
    }

    @Test
    fun derivationRules() {
        assertEquals("https://a.example.com", deriveUrls("https", "http", "a.example.com", "backend:8080")!!.publicBaseUrl)
        assertEquals("wss://a.example.com/mqtt", deriveUrls("https,http", "http", "a.example.com, b", null)!!.mqttUrl)
        // no forwarded headers: request scheme + Host
        val local = deriveUrls(null, "http", null, "localhost:8080")!!
        assertEquals("http://localhost:8080", local.publicBaseUrl)
        assertEquals("wss://localhost:8080/mqtt", local.mqttUrl)
        // junk is ignored
        assertNull(deriveUrls("https", "http", "evil.com/<script>", null))
        assertNull(deriveUrls(null, "http", null, null))
        assertEquals("https://x.test", deriveUrls("gopher", "https", "x.test", null)!!.publicBaseUrl)
    }
}
