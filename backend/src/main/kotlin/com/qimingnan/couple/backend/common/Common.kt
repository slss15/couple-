package com.qimingnan.couple.backend.common

import com.github.f4b6a3.ulid.UlidCreator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Error body required by the contract: {"code":"STRING_CODE","message":"..."} */
@Serializable
data class ErrorBody(val code: String, val message: String)

class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

fun badRequest(code: String, message: String) = ApiException(HttpStatusCode.BadRequest, code, message)
fun unauthorized(code: String = "UNAUTHORIZED", message: String = "Authentication required") =
    ApiException(HttpStatusCode.Unauthorized, code, message)
fun forbidden(code: String = "FORBIDDEN", message: String = "Forbidden") = ApiException(HttpStatusCode.Forbidden, code, message)
fun notFound(code: String = "NOT_FOUND", message: String = "Not found") = ApiException(HttpStatusCode.NotFound, code, message)
fun conflict(code: String, message: String) = ApiException(HttpStatusCode.Conflict, code, message)

object Ids {
    fun newId(): String = UlidCreator.getMonotonicUlid().toString()
}

object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * True when the TCP peer (not any forwarded header) is this host. Note cloudflared on the same host also connects
 * from loopback, so callers must additionally reject requests carrying CF-Connecting-IP.
 */
fun ApplicationCall.isLoopbackPeer(): Boolean {
    val addr = request.local.remoteAddress.removePrefix("[").removeSuffix("]").lowercase()
    return addr == "localhost" || addr == "::1" || addr == "0:0:0:0:0:0:0:1" || addr.startsWith("127.") ||
        addr == "::ffff:127.0.0.1"
}
