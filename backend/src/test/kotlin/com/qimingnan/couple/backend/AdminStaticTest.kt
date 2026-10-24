package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.config.EnvFile
import com.qimingnan.couple.backend.config.MqttBrokerMode
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminStaticTest {

    private fun dist(): File = Files.createTempDirectory("admin-dist").toFile().apply {
        File(this, "index.html").writeText("<html><script src=\"/config.js\"></script>INDEX</html>")
        File(this, "assets").mkdirs()
        File(this, "assets/app-123.js").writeText("console.log('app')")
        File(this, "favicon.ico").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun servesSpaWithFallbackButNeverForReservedPaths() {
        val dir = dist()
        File(dir.parentFile, "secret-outside.txt").writeText("nope")
        appTest(testConfig(adminStaticDir = dir.path)) { client ->
            val index = client.get("/")
            assertEquals(200, index.status.value)
            assertTrue(index.bodyAsText().contains("INDEX"))
            assertEquals("no-cache", index.headers[HttpHeaders.CacheControl])

            val asset = client.get("/assets/app-123.js")
            assertEquals(200, asset.status.value)
            assertTrue(asset.headers[HttpHeaders.CacheControl]!!.contains("immutable"))
            assertTrue(asset.headers[HttpHeaders.ContentType]!!.contains("javascript"))

            assertEquals(200, client.get("/favicon.ico").status.value)
            // client-side routes fall back to index.html
            val deep = client.get("/releases/123")
            assertEquals(200, deep.status.value)
            assertTrue(deep.bodyAsText().contains("INDEX"))
            // ... but never for API / MQTT / deploy paths or missing assets
            assertEquals(404, client.get("/assets/missing.js").status.value)
            assertEquals(404, client.get("/api/v1/does-not-exist").status.value)
            assertEquals(404, client.get("/api").status.value)
            assertEquals(404, client.get("/mqtt").status.value)
            assertEquals(404, client.get("/_deploy/status").status.value)
            assertEquals(404, client.get("/api/v1/internal/mqtt/auth").status.value)
            // traversal never escapes the root
            assertTrue(!client.get("/../secret-outside.txt").bodyAsText().contains("nope"))
            assertTrue(!client.get("/%2e%2e/secret-outside.txt").bodyAsText().contains("nope"))
            // real API still wins
            assertEquals(200, client.get("/api/v1/health").status.value)

            val cfg = client.get("/config.js")
            assertEquals(200, cfg.status.value)
            assertEquals("no-store", cfg.headers[HttpHeaders.CacheControl])
            val js = cfg.bodyAsText()
            assertTrue(js.startsWith("window.__COUPLE_CONFIG__ = {"), js)
            assertTrue(js.contains("apiBase: \"https://api.example.test\""), js)
            assertTrue(js.contains("version: \""), js)
        }
    }

    @Test
    fun withoutStaticDirNothingExtraIsServed() = appTest { client ->
        assertEquals(404, client.get("/").status.value)
        assertEquals(404, client.get("/config.js").status.value)
    }

    @Test
    fun envFileAndBrokerModeConfig() {
        val parsed = EnvFile.parse(
            "\uFEFF# comment\nJWT_SECRET=abc\nexport A = \"q u o t e d\"\nB='x'\n\nBAD LINE\nEMPTY=\n1X=no\n",
        )
        assertEquals(mapOf("JWT_SECRET" to "abc", "A" to "q u o t e d", "B" to "x", "EMPTY" to ""), parsed)

        val base = mapOf(
            "DB_URL" to "jdbc:h2:mem:x", "DB_USER" to "sa", "JWT_SECRET" to "x".repeat(40),
        )
        val embedded = AppConfig.fromEnv { (base + ("MQTT_WS_PORT" to "18083"))[it] }
        assertEquals(MqttBrokerMode.EMBEDDED, embedded.mqttBrokerMode)
        assertEquals(18083, embedded.mqttWsPort)
        assertEquals("127.0.0.1", embedded.mqttBindHost)
        // External (EMQX) mode refuses to start without MQTT_HOOK_SECRET.
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv { (base + ("MQTT_BROKER_MODE" to "external"))[it] } }
        val ext = AppConfig.fromEnv { (base + ("MQTT_BROKER_MODE" to "external") + ("MQTT_HOOK_SECRET" to "s"))[it] }
        assertEquals(MqttBrokerMode.EXTERNAL, ext.mqttBrokerMode)
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv { (base + ("MQTT_BROKER_MODE" to "bogus"))[it] } }
    }
}
