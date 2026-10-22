package com.qimingnan.couple.backend.auth

import com.qimingnan.couple.backend.common.unauthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

val AUTH_RATE_LIMIT = RateLimitName("auth")

/** Name of the JWT auth provider. */
const val JWT_AUTH = "jwt"

data class AuthUser(val userId: String, val deviceId: String, val role: String, val coupleIdClaim: String?)

fun ApplicationCall.authUser(): AuthUser {
    val p = principal<JWTPrincipal>() ?: throw unauthorized()
    val sub = p.payload.subject ?: throw unauthorized()
    return AuthUser(
        userId = sub,
        deviceId = p.payload.getClaim("did").asString() ?: "",
        role = p.payload.getClaim("role").asString() ?: "user",
        coupleIdClaim = p.payload.getClaim("cid").asString(),
    )
}

/** Client IP: Cloudflare's CF-Connecting-IP first, then X-Forwarded-For, then the socket peer. */
fun ApplicationCall.clientIp(): String =
    request.headers["CF-Connecting-IP"]
        ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?: request.origin.remoteHost

fun Route.authRoutes(service: AuthService) {
    route("/auth") {
        rateLimit(AUTH_RATE_LIMIT) {
            post("/register") {
                call.respond(service.register(call.receive(), call.clientIp()))
            }
            post("/login") {
                call.respond(service.login(call.receive(), call.clientIp()))
            }
            post("/refresh") {
                call.respond(service.refresh(call.receive(), call.clientIp()))
            }
        }
        post("/logout") {
            service.logout(call.receive(), call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
