package com.qimingnan.couple.backend.setup

import com.qimingnan.couple.backend.admin.AdminConfigService
import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.AuthService
import com.qimingnan.couple.backend.common.ApiException
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.config.AppConfig
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class SetupStatus(val needed: Boolean, val suggested: SuggestedUrls? = null)

@Serializable
data class SetupRequest(
    val token: String? = null,
    val username: String? = null,
    val password: String? = null,
    /** Optional; the admin SPA sends its browser device id so the session survives refresh like a normal login. */
    val deviceId: String? = null,
    val deviceName: String? = null,
)

/**
 * First-run setup: while no admin exists, POST /setup with the setup token creates the first admin and fills empty
 * server URLs from the request. The token is SETUP_TOKEN, or a random one generated at startup (logged at WARN and
 * written to <dataDir>/setup-token.txt).
 */
class SetupService(
    private val config: AppConfig,
    private val auth: AuthService,
    private val adminConfig: AdminConfigService,
    private val audit: AuditService,
) {
    private val mutex = Mutex()

    /** Once an admin is known to exist this stays true; admins are never deleted through the API. */
    @Volatile private var completed = false

    /** The token in effect (null until [init] ran, and after setup completed). */
    @Volatile var token: String? = null
        private set

    /** True when the token was generated (SETUP_TOKEN unset). */
    @Volatile var generated = false
        private set

    private val tokenFile: File? = config.dataDir?.let { File(it, TOKEN_FILE) }

    /** Called once at startup. */
    suspend fun init() {
        if (auth.adminExists()) {
            markCompleted()
            return
        }
        val fromEnv = config.setupToken
        if (fromEnv != null) {
            token = fromEnv
            log.warn("First-run setup pending (no admin yet): open https://<host>/setup?token=<SETUP_TOKEN>")
        } else {
            val t = Crypto.b64url(Crypto.randomBytes(24))
            token = t
            generated = true
            log.warn("SETUP TOKEN: {}  → open https://<host>/setup?token={}", t, t)
            writeTokenFile(t)
        }
    }

    suspend fun needed(): Boolean {
        if (completed) return false
        if (auth.adminExists()) {
            // An admin appeared through the legacy REGISTRATION_CODE flow.
            markCompleted()
            return false
        }
        return true
    }

    suspend fun status(suggested: SuggestedUrls?): SetupStatus =
        if (needed()) SetupStatus(true, suggested) else SetupStatus(false, null)

    suspend fun setup(req: SetupRequest, ip: String?, suggested: SuggestedUrls?): TokenPair = mutex.withLock {
        if (!needed()) throw gone()
        val expected = token
        val given = req.token?.trim().orEmpty()
        if (expected == null || given.isEmpty() || !Crypto.constantTimeEquals(given, expected)) {
            audit.log(null, AuditActions.SETUP_FAILED, req.username?.take(64), mapOf("reason" to "SETUP_TOKEN_INVALID", "ip" to ip))
            throw forbidden("SETUP_TOKEN_INVALID", "Invalid setup token")
        }
        val pair = try {
            auth.createFirstAdmin(req.username.orEmpty(), req.password.orEmpty(), req.deviceId ?: "setup-web", req.deviceName ?: "Web 管理后台")
        } catch (e: ApiException) {
            audit.log(null, AuditActions.SETUP_FAILED, req.username?.take(64), mapOf("reason" to e.code, "ip" to ip))
            throw e
        }
        val filled = adminConfig.fillMissingUrls(suggested?.publicBaseUrl, suggested?.mqttUrl)
        markCompleted()
        audit.log(
            pair.user.id, AuditActions.SETUP_COMPLETED, pair.user.id,
            mapOf(
                "username" to pair.user.username, "ip" to ip, "configured" to filled.joinToString(","),
                "baseUrl" to (if ("baseUrl" in filled) suggested?.publicBaseUrl else null),
                "mqttUrl" to (if ("mqttUrl" in filled) suggested?.mqttUrl else null),
            ),
        )
        log.info("First-run setup completed: admin '{}' created; auto-configured {}", pair.user.username, filled.ifEmpty { listOf("nothing") })
        pair
    }

    private fun markCompleted() {
        completed = true
        token = null
        tokenFile?.let { f ->
            runCatching { if (f.isFile) f.delete() }
        }
    }

    private fun writeTokenFile(t: String) {
        val f = tokenFile ?: return
        try {
            val dir = f.parentFile
            if (dir == null || !dir.isDirectory) return
            f.writeText(t + System.lineSeparator(), Charsets.UTF_8)
            runCatching {
                f.setReadable(false, false); f.setReadable(true, true)
                f.setWritable(false, false); f.setWritable(true, true)
            }
            log.warn("Setup token also written to {}", f.absolutePath)
        } catch (e: Exception) {
            log.warn("Could not write setup token file {}: {}", f.absolutePath, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SetupService::class.java)
        const val TOKEN_FILE = "setup-token.txt"

        fun gone() = ApiException(HttpStatusCode.Gone, "SETUP_COMPLETED", "Setup has already been completed")
    }
}
