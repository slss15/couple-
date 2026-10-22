package com.qimingnan.couple.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.qimingnan.couple.backend.common.Ids
import java.util.Date

/**
 * Access tokens: HS256, 15 min. Claims: sub=userId, cid=coupleId|null, did=deviceId, role=user|admin.
 */
class JwtService(secret: String, private val ttlMs: Long, private val clock: () -> Long) {
    private val algorithm = Algorithm.HMAC256(secret)

    /** Uses the injectable clock so exp/iat checks follow AppConfig.clock (tests move time). */
    val verifier: JWTVerifier = (JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .acceptLeeway(5) as JWTVerifier.BaseVerification)
        .build(object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
            override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(clock())
        })

    data class Issued(val token: String, val expiresAt: Long)

    fun issue(userId: String, coupleId: String?, deviceId: String, role: String): Issued {
        val now = clock()
        val exp = now + ttlMs
        val b = JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withJWTId(Ids.newId())
            .withSubject(userId)
            .withClaim("did", deviceId)
            .withClaim("role", role)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(exp))
        if (coupleId != null) b.withClaim("cid", coupleId) else b.withNullClaim("cid")
        return Issued(b.sign(algorithm), exp)
    }

    /**
     * Verifies with the injectable clock (used by the MQTT auth hook). Returns null when invalid/expired.
     */
    fun verifyOrNull(token: String): DecodedJWT? = try {
        verifier.verify(token)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ISSUER = "couple-backend"
        const val AUDIENCE = "couple-app"
    }
}
