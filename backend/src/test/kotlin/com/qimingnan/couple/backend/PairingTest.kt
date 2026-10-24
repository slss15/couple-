package com.qimingnan.couple.backend

import com.auth0.jwt.JWT
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Couple
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.request.bearerAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    @Test
    fun fullPairAndUnpairFlow() {
        val clock = TestClock()
        appTest(testConfig(clock = clock)) { client ->
            val a = client.register("alice")
            val b = client.register("bob")
            val c = client.register("carol")

            // Not paired yet
            val none = client.get("/api/v1/couple") { bearerAuth(a.accessToken) }
            assertEquals(404, none.status.value)
            assertEquals("NOT_PAIRED", none.errorCode())

            // Unauthenticated
            assertEquals(401, client.postJson("/api/v1/pair/code").status.value)

            // Code issuance; a new code replaces the old one
            val first = client.postJson("/api/v1/pair/code", token = a.accessToken).json()
            val codeRes = client.postJson("/api/v1/pair/code", token = a.accessToken)
            assertEquals(200, codeRes.status.value)
            val cj = codeRes.json()
            val code = cj["code"]!!.jsonPrimitive.content
            assertTrue(Regex("^[0-9]{6}$").matches(code))
            assertEquals(clock.now() + 10 * 60_000L, cj["expiresAt"]!!.jsonPrimitive.long)
            val oldCode = first["code"]!!.jsonPrimitive.content
            if (oldCode != code) {
                val old = client.postJson("/api/v1/pair/join", obj("code" to oldCode), b.accessToken)
                assertEquals("CODE_INVALID", old.errorCode())
            }

            // Self pair
            val self = client.postJson("/api/v1/pair/join", obj("code" to code), a.accessToken)
            assertEquals(400, self.status.value)
            assertEquals("SELF_PAIR", self.errorCode())

            // Invalid code
            val invalid = client.postJson("/api/v1/pair/join", obj("code" to "abc"), b.accessToken)
            assertEquals("CODE_INVALID", invalid.errorCode())

            // Bob joins
            val join = client.postJson("/api/v1/pair/join", obj("code" to code), b.accessToken)
            assertEquals(200, join.status.value, join.bodyAsText())
            val couple = AppJson.decodeFromString(Couple.serializer(), join.bodyAsText())
            assertEquals(setOf(a.user.id, b.user.id), couple.members.map { it.id }.toSet())
            assertTrue(couple.members.all { it.coupleId == couple.id })
            assertNull(couple.anniversary)
            assertEquals(clock.now(), couple.pairedAt)

            // Single use
            val reuse = client.postJson("/api/v1/pair/join", obj("code" to code), c.accessToken)
            assertEquals("CODE_INVALID", reuse.errorCode())

            // Already paired
            val again = client.postJson("/api/v1/pair/code", token = b.accessToken)
            assertEquals(409, again.status.value)
            assertEquals("ALREADY_PAIRED", again.errorCode())
            val cCode = client.postJson("/api/v1/pair/code", token = c.accessToken).json()["code"]!!.jsonPrimitive.content
            val paired = client.postJson("/api/v1/pair/join", obj("code" to cCode), a.accessToken)
            assertEquals("ALREADY_PAIRED", paired.errorCode())

            // GET /couple for both members
            val got = client.get("/api/v1/couple") { bearerAuth(a.accessToken) }
            assertEquals(couple.id, AppJson.decodeFromString(Couple.serializer(), got.bodyAsText()).id)

            // Refreshing picks up cid in the access token
            val r = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to a.refreshToken, "deviceId" to "dev-alice"))
            val pair = AppJson.decodeFromString(TokenPair.serializer(), r.bodyAsText())
            assertEquals(couple.id, JWT.decode(pair.accessToken).getClaim("cid").asString())
            assertEquals(couple.id, pair.user.coupleId)

            // Carol (outsider) cannot see or confirm
            val req = client.postJson("/api/v1/couple/unpair/request", token = a.accessToken)
            assertEquals(200, req.status.value)
            val requestId = req.json()["requestId"]!!.jsonPrimitive.content

            val outsider = client.postJson("/api/v1/couple/unpair/confirm", obj("requestId" to requestId), c.accessToken)
            assertEquals(404, outsider.status.value)

            // Requester cannot confirm their own request
            val selfConfirm = client.postJson("/api/v1/couple/unpair/confirm", obj("requestId" to requestId), a.accessToken)
            assertEquals(403, selfConfirm.status.value)
            assertEquals("NOT_OTHER_MEMBER", selfConfirm.errorCode())

            // Still paired
            assertEquals(200, client.get("/api/v1/couple") { bearerAuth(b.accessToken) }.status.value)

            // Other member confirms
            val ok = client.postJson("/api/v1/couple/unpair/confirm", obj("requestId" to requestId), b.accessToken)
            assertEquals(204, ok.status.value, ok.bodyAsText())

            for (t in listOf(a.accessToken, b.accessToken)) {
                val res = client.get("/api/v1/couple") { bearerAuth(t) }
                assertEquals(404, res.status.value)
                assertEquals("NOT_PAIRED", res.errorCode())
            }
            // Confirming twice fails (request no longer belongs to a couple of the user)
            val twice = client.postJson("/api/v1/couple/unpair/confirm", obj("requestId" to requestId), b.accessToken)
            assertEquals(404, twice.status.value)

            // Both may pair again
            val newCode = client.postJson("/api/v1/pair/code", token = a.accessToken).json()["code"]!!.jsonPrimitive.content
            assertEquals(200, client.postJson("/api/v1/pair/join", obj("code" to newCode), b.accessToken).status.value)
        }
    }

    @Test
    fun expiredCode() {
        val clock = TestClock()
        appTest(testConfig(clock = clock)) { client ->
            val a = client.register("alice")
            val b = client.register("bob")
            val code = client.postJson("/api/v1/pair/code", token = a.accessToken).json()["code"]!!.jsonPrimitive.content
            clock.advance(10 * 60_000L + 1)
            // refresh bob so his access token is valid at the new clock (still within 15 min anyway)
            val res = client.postJson("/api/v1/pair/join", obj("code" to code), b.accessToken)
            assertEquals(400, res.status.value)
            assertEquals("CODE_EXPIRED", res.errorCode())
        }
    }

    private suspend fun HttpClient.postRaw(path: String, body: String, token: String): HttpResponse = post(path) {
        bearerAuth(token)
        setBody(TextContent(body, ContentType.Application.Json))
    }

    private data class Pair3(val a: TokenPair, val b: TokenPair, val c: TokenPair)

    /** alice + bob paired, carol unpaired outsider. */
    private suspend fun HttpClient.pairedTrio(): Pair3 {
        val a = register("alice")
        val b = register("bob")
        val c = register("carol")
        val code = postJson("/api/v1/pair/code", token = a.accessToken).json()["code"]!!.jsonPrimitive.content
        assertEquals(200, postJson("/api/v1/pair/join", obj("code" to code), b.accessToken).status.value)
        return Pair3(a, b, c)
    }

    private suspend fun HttpClient.assertPaired(token: String, paired: Boolean) {
        val res = get("/api/v1/couple") { bearerAuth(token) }
        assertEquals(if (paired) 200 else 404, res.status.value, res.bodyAsText())
    }

    @Test
    fun confirmWithoutRequestIdEmptyObject() = appTest { client ->
        val (a, b) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        val ok = client.postJson("/api/v1/couple/unpair/confirm", obj(), b.accessToken)
        assertEquals(204, ok.status.value, ok.bodyAsText())
        client.assertPaired(a.accessToken, false)
        client.assertPaired(b.accessToken, false)
    }

    @Test
    fun confirmWithNullRequestId() = appTest { client ->
        val (a, b) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        val ok = client.postRaw("/api/v1/couple/unpair/confirm", """{"requestId":null}""", b.accessToken)
        assertEquals(204, ok.status.value, ok.bodyAsText())
        client.assertPaired(a.accessToken, false)
    }

    @Test
    fun confirmWithEmptyBody() = appTest { client ->
        val (a, b) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        val ok = client.postRaw("/api/v1/couple/unpair/confirm", "", b.accessToken)
        assertEquals(204, ok.status.value, ok.bodyAsText())
        client.assertPaired(b.accessToken, false)
    }

    @Test
    fun requesterConfirmWithoutIdIsNotFound() = appTest { client ->
        val (a, b) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        val res = client.postJson("/api/v1/couple/unpair/confirm", obj(), a.accessToken)
        assertEquals(404, res.status.value)
        assertEquals("UNPAIR_REQUEST_NOT_FOUND", res.errorCode())
        client.assertPaired(b.accessToken, true)
    }

    @Test
    fun confirmWithoutIdAndNoOpenRequestIsNotFound() = appTest { client ->
        val (a, b) = client.pairedTrio()
        val res = client.postJson("/api/v1/couple/unpair/confirm", obj(), b.accessToken)
        assertEquals(404, res.status.value)
        assertEquals("UNPAIR_REQUEST_NOT_FOUND", res.errorCode())
        client.assertPaired(a.accessToken, true)
    }

    @Test
    fun confirmWithoutIdExpiredRequestIsNotFound() {
        val clock = TestClock()
        val config = testConfig(clock = clock)
        appTest(config) { client ->
            val (a, b) = client.pairedTrio()
            assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
            clock.advance(config.unpairRequestTtlMs + 1)
            // Access tokens are long expired by now; refresh bob at the new clock.
            val r = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to b.refreshToken, "deviceId" to "dev-bob"))
            assertEquals(200, r.status.value, r.bodyAsText())
            val bob = AppJson.decodeFromString(TokenPair.serializer(), r.bodyAsText())
            val res = client.postJson("/api/v1/couple/unpair/confirm", obj(), bob.accessToken)
            assertEquals(404, res.status.value)
            assertEquals("UNPAIR_REQUEST_NOT_FOUND", res.errorCode())
            client.assertPaired(bob.accessToken, true)
        }
    }

    @Test
    fun outsiderConfirmWithoutIdIsNotFound() = appTest { client ->
        val (a, b, c) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        val res = client.postJson("/api/v1/couple/unpair/confirm", obj(), c.accessToken)
        assertEquals(404, res.status.value)
        assertEquals("UNPAIR_REQUEST_NOT_FOUND", res.errorCode())
        client.assertPaired(b.accessToken, true)
    }

    @Test
    fun confirmWithMalformedJsonIsBadRequest() = appTest { client ->
        val (a, b) = client.pairedTrio()
        assertEquals(200, client.postJson("/api/v1/couple/unpair/request", token = a.accessToken).status.value)
        for (body in listOf("{not json", """{"requestId":}""", """{"requestId":[1]}""")) {
            val res = client.postRaw("/api/v1/couple/unpair/confirm", body, b.accessToken)
            assertEquals(400, res.status.value, "body=$body -> ${res.bodyAsText()}")
            assertEquals("BAD_REQUEST", res.errorCode())
        }
        client.assertPaired(b.accessToken, true)
    }
}
