package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.bootstrap.BootstrapKeys
import com.qimingnan.couple.backend.common.AppJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminBootstrapTest {

    private suspend fun io.ktor.client.HttpClient.putConfig(token: String, body: JsonObject) = put("/api/v1/admin/config") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun bootstrapSignatureVerifiesWithPublishedKey() = appTest { client ->
        val admin = client.register("admin")
        val user = client.register("bob")

        // pubkey is public
        val pk = client.get("/api/v1/bootstrap/pubkey").json()
        assertEquals("Ed25519", pk["alg"]!!.jsonPrimitive.content)
        val rawPub = Base64.getDecoder().decode(pk["publicKey"]!!.jsonPrimitive.content)
        assertEquals(32, rawPub.size)

        // config incomplete
        val early = client.postJson("/api/v1/admin/bootstrap", token = admin.accessToken)
        assertEquals(400, early.status.value)
        assertEquals("CONFIG_INCOMPLETE", early.errorCode())

        // non-admin forbidden
        assertEquals(403, client.postJson("/api/v1/admin/bootstrap", token = user.accessToken).status.value)
        assertEquals(403, client.get("/api/v1/admin/config") { bearerAuth(user.accessToken) }.status.value)

        val bad = client.putConfig(admin.accessToken, obj("baseUrl" to "https://c.example.com", "mqttUrl" to "mqtt://plain"))
        assertEquals(400, bad.status.value)

        val put = client.putConfig(admin.accessToken, obj("baseUrl" to "https://c.example.com", "mqttUrl" to "wss://c.example.com/mqtt"))
        assertEquals(200, put.status.value, put.bodyAsText())
        assertEquals(1L, put.json()["configVersion"]!!.jsonPrimitive.long)

        val res = client.postJson("/api/v1/admin/bootstrap", token = admin.accessToken)
        assertEquals(200, res.status.value, res.bodyAsText())
        val body = res.json()
        val uri = URI(body["uri"]!!.jsonPrimitive.content)
        assertEquals("couple", uri.scheme)
        assertEquals("bootstrap", uri.host)
        val params = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val d = Base64.getUrlDecoder().decode(params.getValue("d"))
        val s = Base64.getUrlDecoder().decode(params.getValue("s"))
        assertFalse(params.getValue("d").contains('='), "base64url without padding")

        assertTrue(BootstrapKeys.verify(rawPub, d, s), "signature must verify with the published key")
        val tampered = d.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        assertFalse(BootstrapKeys.verify(rawPub, tampered, s))
        val otherKey = BootstrapKeys.generate()
        assertFalse(BootstrapKeys.verify(otherKey.rawPublicKey, d, s))

        val payload = AppJson.parseToJsonElement(String(d, Charsets.UTF_8)).jsonObject
        assertEquals(payload, body["json"]!!.jsonObject)
        assertEquals(listOf("v", "baseUrl", "mqttUrl", "configVersion", "issuedAt"), payload.keys.toList())
        assertEquals(1L, payload["v"]!!.jsonPrimitive.long)
        assertEquals("https://c.example.com", payload["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("wss://c.example.com/mqtt", payload["mqttUrl"]!!.jsonPrimitive.content)
        assertEquals(1L, payload["configVersion"]!!.jsonPrimitive.long)
    }

    @Test
    fun envKeyIsUsedAndGeneratedKeyPersists() {
        val k = BootstrapKeys.generate()
        appTest(testConfig(bootstrapPrivateKey = k.seedBase64)) { client ->
            val pk = client.get("/api/v1/bootstrap/pubkey").json()["publicKey"]!!.jsonPrimitive.content
            assertEquals(k.publicKeyBase64, pk)
        }
        // Same DB, no env key: generated once, then reloaded.
        val db = "persist" + System.nanoTime()
        var first = ""
        appTest(testConfig(dbName = db)) { client ->
            first = client.get("/api/v1/bootstrap/pubkey").json()["publicKey"]!!.jsonPrimitive.content
        }
        appTest(testConfig(dbName = db)) { client ->
            assertEquals(first, client.get("/api/v1/bootstrap/pubkey").json()["publicKey"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun secretsAreWriteOnlyAndMasked() = appTest { client ->
        val admin = client.register("admin")
        val token = "cf-api-token-abcdefgh-1234"
        val r1 = client.putConfig(
            admin.accessToken,
            obj(
                "baseUrl" to "https://c.example.com/",
                "mqttUrl" to "wss://c.example.com/mqtt",
                "turn" to obj("provider" to "cloudflare", "cloudflare" to obj("keyId" to "kid-1", "apiToken" to token)),
                "storage" to obj("provider" to "r2", "accessKeyId" to "AKID", "secretAccessKey" to "short"),
            ),
        )
        assertEquals(200, r1.status.value, r1.bodyAsText())
        assertFalse(r1.bodyAsText().contains(token))
        assertEquals("https://c.example.com", r1.json()["baseUrl"]!!.jsonPrimitive.content, "trailing slash trimmed")

        val get = client.get("/api/v1/admin/config") { bearerAuth(admin.accessToken) }
        val text = get.bodyAsText()
        assertFalse(text.contains(token))
        assertFalse(text.contains("\"short\""))
        val j = get.json()
        val cf = j["turn"]!!.jsonObject["cloudflare"]!!.jsonObject
        assertEquals("****1234", cf["apiToken"]!!.jsonPrimitive.content)
        assertEquals("kid-1", cf["keyId"]!!.jsonPrimitive.content)
        assertEquals("********", j["storage"]!!.jsonObject["secretAccessKey"]!!.jsonPrimitive.content)
        assertEquals("AKID", j["storage"]!!.jsonObject["accessKeyId"]!!.jsonPrimitive.content)

        // Echo masked values back + omit a secret: both kept. Bad provider rejected.
        assertEquals(400, client.putConfig(admin.accessToken, obj("turn" to obj("provider" to "xirsys"))).status.value)
        val r2 = client.putConfig(
            admin.accessToken,
            obj(
                "turn" to obj("provider" to "cloudflare", "cloudflare" to obj("keyId" to "kid-2", "apiToken" to "****1234")),
                "storage" to obj("provider" to "r2", "accessKeyId" to "AKID2"),
            ),
        )
        assertEquals(200, r2.status.value)
        assertEquals(2L, r2.json()["configVersion"]!!.jsonPrimitive.long)
        assertEquals("****1234", r2.json()["turn"]!!.jsonObject["cloudflare"]!!.jsonObject["apiToken"]!!.jsonPrimitive.content)
        assertEquals("********", r2.json()["storage"]!!.jsonObject["secretAccessKey"]!!.jsonPrimitive.content)

        // "" clears -> null
        val r3 = client.putConfig(admin.accessToken, obj("storage" to obj("provider" to "r2", "secretAccessKey" to "")))
        assertEquals(JsonNull, r3.json()["storage"]!!.jsonObject["secretAccessKey"])

        // Audit trail: config changes logged with secret *names* only.
        val audit = client.get("/api/v1/admin/audit?limit=100") { bearerAuth(admin.accessToken) }
        assertEquals(200, audit.status.value)
        val auditText = audit.bodyAsText()
        assertFalse(auditText.contains(token))
        val actions = AppJson.parseToJsonElement(auditText).jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertTrue("auth.register" in actions)
        assertTrue("admin.config.update" in actions)
        assertTrue("admin.config.view" in actions)
    }

    @Test
    fun auditPaginationAndActions() = appTest { client ->
        val admin = client.register("admin")
        val b = client.register("bob")
        val code = client.postJson("/api/v1/pair/code", token = admin.accessToken).json()["code"]!!.jsonPrimitive.content
        client.postJson("/api/v1/pair/join", obj("code" to code), b.accessToken)
        client.postJson("/api/v1/auth/login", obj("username" to "bob", "password" to "nope-nope", "deviceId" to "x", "deviceName" to "x"))

        val all = AppJson.parseToJsonElement(
            client.get("/api/v1/admin/audit?limit=200") { bearerAuth(admin.accessToken) }.bodyAsText(),
        ).jsonArray
        val actions = all.map { it.jsonObject["action"]!!.jsonPrimitive.content }
        for (a in listOf("auth.register", "pair.code", "pair.join", "auth.login_failed", "bootstrap.key_generated")) {
            assertTrue(a in actions, "missing audit action $a in $actions")
        }
        // ordered newest first
        val ts = all.map { it.jsonObject["ts"]!!.jsonPrimitive.long }
        assertEquals(ts.sortedDescending(), ts)

        val page = AppJson.parseToJsonElement(
            client.get("/api/v1/admin/audit?limit=2") { bearerAuth(admin.accessToken) }.bodyAsText(),
        ).jsonArray
        assertEquals(2, page.size)
        val before = page.last().jsonObject["ts"]!!.jsonPrimitive.long
        val next = AppJson.parseToJsonElement(
            client.get("/api/v1/admin/audit?limit=200&before=$before") { bearerAuth(admin.accessToken) }.bodyAsText(),
        ).jsonArray
        assertTrue(next.all { it.jsonObject["ts"]!!.jsonPrimitive.long < before })

        assertEquals(403, client.get("/api/v1/admin/audit") { bearerAuth(b.accessToken) }.status.value)
    }
}
