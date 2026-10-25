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
import com.qimingnan.couple.backend.mqtt.EmbeddedMqttBroker
import com.qimingnan.couple.backend.mqtt.MqttAccessService
import com.qimingnan.couple.backend.mqtt.mqttHookRoutes
import com.qimingnan.couple.backend.admin.adminStaticRoutes
import com.qimingnan.couple.backend.config.MqttBrokerMode
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.health.SERVER_VERSION
import org.jetbrains.exposed.sql.selectAll
import com.qimingnan.couple.backend.pairing.PairingService
import com.qimingnan.couple.backend.setup.SetupService
import com.qimingnan.couple.backend.setup.setupRoutes
import com.qimingnan.couple.backend.pairing.pairingRoutes
import com.qimingnan.couple.backend.release.ReleaseMirror
import com.qimingnan.couple.backend.release.ReleaseService
import com.qimingnan.couple.backend.release.ReleaseSettingsService
import com.qimingnan.couple.backend.release.adminReleaseRoutes
import com.qimingnan.couple.backend.release.appUpdateRoutes
import com.qimingnan.couple.backend.release.updaterStatusRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
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

/** Lets tests reach the first-run setup service (generated token). */
val SetupServiceKey = io.ktor.util.AttributeKey<SetupService>("SetupService")

/** Lets tests reach the running mirror instance. */
val ReleaseMirrorKey = io.ktor.util.AttributeKey<ReleaseMirror>("ReleaseMirror")

private val log = LoggerFactory.getLogger("com.qimingnan.couple.backend")

fun main() {
    val config = AppConfig.fromEnv()
    // Ktor's EmbeddedServer registers a JVM shutdown hook on start: a Windows service stop (WinSW sends Ctrl+C)
    // or docker stop runs ApplicationStopping/Stopped (embedded broker, DB pool) before the process exits.
    // BIND_HOST: listen address (default 0.0.0.0 for Docker; the Windows service sets 127.0.0.1, cloudflared is local).
    val bindHost = AppConfig.systemEnv()("BIND_HOST")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0.0"
    embeddedServer(Netty, port = config.port, host = bindHost) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    val db = Db.connect(config)
    monitor.subscribe(ApplicationStopped) { db.close() }

    val audit = AuditService(db, config.clock)
    val jwtService = JwtService(config.jwtSecret, config.accessTokenTtlMs, config.clock)
    val mqttAccess = MqttAccessService(db, jwtService, audit)
    if (config.mqttBrokerMode == MqttBrokerMode.EMBEDDED) {
        val broker = EmbeddedMqttBroker(
            config,
            MqttAccessService(db, jwtService, audit, coupleCacheTtlMs = 10_000),
            activeCoupleIds = { db.tx { Couples.selectAll().where { Couples.deletedAt.isNull() }.map { it[Couples.id] } } },
        )
        broker.start()
        // Stop the broker (clean disconnects, persistent store flushed) before the DB pool closes.
        monitor.subscribe(ApplicationStopping) { broker.stop() }
    }
    val authService = AuthService(db, config, jwtService, PasswordHasher(), audit)
    val pairingService = PairingService(db, config, audit)
    val adminConfigService = AdminConfigService(db, config)
    val setupService = SetupService(config, authService, adminConfigService, audit)
    runBlocking { setupService.init() }
    attributes.put(SetupServiceKey, setupService)
    val keys = runBlocking { BootstrapService.loadKeys(config, db, audit) }
    val bootstrapService = BootstrapService(keys, adminConfigService, config)
    log.info("Bootstrap Ed25519 public key: {}", keys.publicKeyBase64)
    val releaseSettings = ReleaseSettingsService(db, config.clock)
    val releaseService = ReleaseService(db, config, releaseSettings, audit)
    val releaseMirror = ReleaseMirror(db, config, releaseSettings, audit)
    attributes.put(ReleaseMirrorKey, releaseMirror)
    if (config.releasePollEnabled) releaseMirror.startScheduler(this)

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
            setupRoutes(setupService, config.mqttWsPath)
            bootstrapPublicRoutes(bootstrapService)
            // EMQX hooks exist only in external mode; in embedded mode /internal/mqtt/* is simply 404.
            if (config.mqttBrokerMode == MqttBrokerMode.EXTERNAL) mqttHookRoutes(config, mqttAccess)
            updaterStatusRoutes(config, audit, releaseService)
            authenticate(JWT_AUTH) {
                pairingRoutes(pairingService)
                adminRoutes(db, audit, adminConfigService, bootstrapService, config.mqttWsPath)
                appUpdateRoutes(releaseService)
                adminReleaseRoutes(db, audit, releaseService, releaseMirror, releaseSettings, this@module)
            }
        }
        config.adminStaticDir?.let { dir ->
            adminStaticRoutes(dir, config.adminApiBase, config.appVersion ?: SERVER_VERSION)
        }
    }
}
