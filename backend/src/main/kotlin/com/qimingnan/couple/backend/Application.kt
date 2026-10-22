package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.admin.AdminConfigService
import com.qimingnan.couple.backend.admin.adminRoutes
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.AUTH_RATE_LIMIT
import com.qimingnan.couple.backend.auth.AuthService
import com.qimingnan.couple.backend.auth.JWT_AUTH
import com.qimingnan.couple.backend.auth.JwtService
import com.qimingnan.couple.backend.auth.PasswordHasher
import com.qimingnan.couple.backend.auth.authRoutes
import com.qimingnan.couple.backend.auth.clientIp
import com.qimingnan.couple.backend.bootstrap.BootstrapService
import com.qimingnan.couple.backend.bootstrap.bootstrapPublicRoutes
import com.qimingnan.couple.backend.common.ApiException
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.ErrorBody
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.health.healthRoutes
import com.qimingnan.couple.backend.mqtt.mqttHookRoutes
import com.qimingnan.couple.backend.pairing.PairingService
import com.qimingnan.couple.backend.pairing.pairingRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("com.qimingnan.couple.backend")

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    val db = Db.connect(config)
    monitor.subscribe(ApplicationStopped) { db.close() }

    val audit = AuditService(db, config.clock)
    val jwtService = JwtService(config.jwtSecret, config.accessTokenTtlMs, config.clock)
    val authService = AuthService(db, config, jwtService, PasswordHasher(), audit)
    val pairingService = PairingService(db, config, audit)
    val adminConfigService = AdminConfigService(db, config)
    val keys = runBlocking { BootstrapService.loadKeys(config, db, audit) }
    val bootstrapService = BootstrapService(keys, adminConfigService, config)
    log.info("Bootstrap Ed25519 public key: {}", keys.publicKeyBase64)

    install(ContentNegotiation) { json(AppJson) }
    install(CallLogging) {
        level = Level.INFO
        // Never log /internal hook bodies or auth payloads; CallLogging logs method/path/status only.
        filter { it.request.path().startsWith("/api/") }
    }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = config.authRateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorBody(e.code, e.message ?: e.code))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("BAD_REQUEST", "Malformed request body"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("INTERNAL", "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorBody("NOT_FOUND", "Not found"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorBody("RATE_LIMITED", "Too many requests, slow down"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorBody("METHOD_NOT_ALLOWED", "Method not allowed"))
        }
    }
    authentication {
        jwt(JWT_AUTH) {
            realm = "couple"
            verifier(jwtService.verifier)
            validate { cred -> if (cred.payload.subject.isNullOrBlank()) null else JWTPrincipal(cred.payload) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("UNAUTHORIZED", "Missing or invalid access token"))
            }
        }
    }

    routing {
        route("/api/v1") {
            healthRoutes()
            authRoutes(authService)
            bootstrapPublicRoutes(bootstrapService)
            mqttHookRoutes(config, db, jwtService, audit)
            authenticate(JWT_AUTH) {
                pairingRoutes(pairingService)
                adminRoutes(db, audit, adminConfigService, bootstrapService)
            }
        }
    }
}
