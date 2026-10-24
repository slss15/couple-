package com.qimingnan.couple.backend

import com.auth0.jwt.JWT
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SERVER_VERSION_RE = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]*$")

class AuthTest {

    @Test
    fun health() = appTest { client ->
        val res = client.get("/api/v1/health")
        assertEquals(200, res.status.value)
        val j = res.json()
        assertEquals("ok", j["status"]!!.jsonPrimitive.content)
        assertEquals(1, j["protocolVersion"]!!.jsonPrimitive.int)
        // Build version (0.1.0 locally; CI packages pass -PappVersion=X.Y.Z / edge-<sha>).
        assertEquals(com.qimingnan.couple.backend.health.SERVER_VERSION, j["version"]!!.jsonPrimitive.content)
        kotlin.test.assertTrue(SERVER_VERSION_RE.matches(j["version"]!!.jsonPrimitive.content))
    }

    @Test
    fun unknownRouteUsesErrorShape() = appTest { client ->
        val res = client.get("/api/v1/nope")
        assertEquals(404, res.status.value)
        assertEquals("NOT_FOUND", res.errorCode())
    }

    @Test
    fun registerRequiresInviteCode() = appTest { client ->
        val res = client.postJson(
            "/api/v1/auth/register",
            obj("username" to "alice", "password" to "password-123", "displayName" to "A", "deviceId" to "d1", "deviceName" to "P", "inviteCode" to "wrong"),
        )
        assertEquals(403, res.status.value)
        assertEquals("INVITE_CODE_INVALID", res.errorCode())
    }

    @Test
    fun registerLoginAndClaims() = appTest { client ->
        val a = client.register("alice")
        assertEquals("alice", a.user.username)
        assertEquals("ALICE", a.user.displayName)
        assertNull(a.user.coupleId)
        assertNull(a.user.avatarUrl)
        assertEquals(26, a.user.id.length)
        val claims = JWT.decode(a.accessToken)
        assertEquals(a.user.id, claims.subject)
        assertEquals("admin", claims.getClaim("role").asString(), "first user becomes admin")
        assertEquals("dev-alice", claims.getClaim("did").asString())
        assertTrue(claims.getClaim("cid").isNull)
        assertTrue(a.accessExpiresAt - System.currentTimeMillis() in (14 * 60_000L)..(15 * 60_000L + 1000))

        val b = client.register("bob")
        assertEquals("user", JWT.decode(b.accessToken).getClaim("role").asString())

        // explicit nulls are serialized (User.avatarUrl|null)
        val raw = client.postJson("/api/v1/auth/login", obj("username" to "alice", "password" to "password-123", "deviceId" to "d2", "deviceName" to "Tab"))
        assertEquals(200, raw.status.value)
        assertTrue(raw.bodyAsText().contains("\"avatarUrl\":null"))

        val dup = client.postJson(
            "/api/v1/auth/register",
            obj("username" to "alice", "password" to "password-123", "displayName" to "A", "deviceId" to "d1", "deviceName" to "P", "inviteCode" to INVITE),
        )
        assertEquals(409, dup.status.value)
        assertEquals("USERNAME_TAKEN", dup.errorCode())

        val bad = client.postJson("/api/v1/auth/login", obj("username" to "alice", "password" to "wrong-pass", "deviceId" to "d2", "deviceName" to "Tab"))
        assertEquals(401, bad.status.value)
        assertEquals("INVALID_CREDENTIALS", bad.errorCode())
        val nouser = client.postJson("/api/v1/auth/login", obj("username" to "nobody", "password" to "wrong-pass", "deviceId" to "d2", "deviceName" to "Tab"))
        assertEquals("INVALID_CREDENTIALS", nouser.errorCode())
    }

    @Test
    fun adminUsernameEnvOverridesFirstUser() = appTest(testConfig(adminUsername = "boss")) { client ->
        val first = client.register("alice")
        assertEquals("user", JWT.decode(first.accessToken).getClaim("role").asString())
        val boss = client.register("boss")
        assertEquals("admin", JWT.decode(boss.accessToken).getClaim("role").asString())
    }

    @Test
    fun refreshRotatesAndReuseRevokesFamily() = appTest { client ->
        val a = client.register("alice", device = "phone")
        val r1 = a.refreshToken

        val res2 = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to r1, "deviceId" to "phone"))
        assertEquals(200, res2.status.value, res2.bodyAsText())
        val p2 = AppJson.decodeFromString(TokenPair.serializer(), res2.bodyAsText())
        assertNotEquals(r1, p2.refreshToken)

        val res3 = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to p2.refreshToken, "deviceId" to "phone"))
        assertEquals(200, res3.status.value)
        val p3 = AppJson.decodeFromString(TokenPair.serializer(), res3.bodyAsText())

        // Reuse of r1 (already rotated) -> whole family revoked.
        val reuse = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to r1, "deviceId" to "phone"))
        assertEquals(401, reuse.status.value)
        assertEquals("REFRESH_REUSED", reuse.errorCode())

        // The newest token in the family is now dead too.
        val after = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to p3.refreshToken, "deviceId" to "phone"))
        assertEquals(401, after.status.value)
        assertEquals("REFRESH_REVOKED", after.errorCode())

        // Another session (different login) is unaffected.
        val other = client.postJson("/api/v1/auth/login", obj("username" to "alice", "password" to "password-123", "deviceId" to "tablet", "deviceName" to "Tab"))
        val op = AppJson.decodeFromString(TokenPair.serializer(), other.bodyAsText())
        val ok = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to op.refreshToken, "deviceId" to "tablet"))
        assertEquals(200, ok.status.value)
    }

    @Test
    fun refreshRejectsWrongDeviceAndExpiry() {
        val clock = TestClock()
        appTest(testConfig(clock = clock)) { client ->
            val a = client.register("alice", device = "phone")
            val wrong = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to a.refreshToken, "deviceId" to "evil"))
            assertEquals(401, wrong.status.value)
            assertEquals("DEVICE_MISMATCH", wrong.errorCode())

            clock.advance(31L * 24 * 3600 * 1000)
            val expired = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to a.refreshToken, "deviceId" to "phone"))
            assertEquals("REFRESH_EXPIRED", expired.errorCode())

            // Access token is also expired by now.
            val couple = client.get("/api/v1/couple") { bearerAuth(a.accessToken) }
            assertEquals(401, couple.status.value)
            assertEquals("UNAUTHORIZED", couple.errorCode())
        }
    }

    @Test
    fun logoutRevokesSession() = appTest { client ->
        val a = client.register("alice")
        val out = client.postJson("/api/v1/auth/logout", obj("refreshToken" to a.refreshToken))
        assertEquals(204, out.status.value)
        val res = client.postJson("/api/v1/auth/refresh", obj("refreshToken" to a.refreshToken, "deviceId" to "dev-alice"))
        assertEquals(401, res.status.value)
        assertEquals("REFRESH_REVOKED", res.errorCode())
        // idempotent for unknown tokens
        assertEquals(204, client.postJson("/api/v1/auth/logout", obj("refreshToken" to "garbage")).status.value)
    }

    @Test
    fun malformedBodyGivesErrorShape() = appTest { client ->
        val res = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(400, res.status.value)
        assertEquals("BAD_REQUEST", res.errorCode())
    }
}
