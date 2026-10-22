package com.qimingnan.couple.backend.auth

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.common.ApiException
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.Roles
import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.conflict
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.unauthorized
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.RefreshTokens
import com.qimingnan.couple.backend.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

@Serializable
data class RegisterRequest(
    val username: String? = null,
    val password: String? = null,
    val displayName: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val inviteCode: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String? = null,
    val password: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String? = null, val deviceId: String? = null)

@Serializable
data class LogoutRequest(val refreshToken: String? = null)

class AuthService(
    private val db: Db,
    private val config: AppConfig,
    private val jwt: JwtService,
    private val hasher: PasswordHasher,
    private val audit: AuditService,
) {
    private val usernameRegex = Regex("^[A-Za-z0-9_.-]{3,32}$")
    private val dummyHash by lazy { hasher.hash("dummy-password-for-timing") }

    suspend fun register(req: RegisterRequest, ip: String?): TokenPair {
        val code = config.registrationCode
        if (code.isNullOrBlank()) {
            audit.log(null, AuditActions.REGISTER_FAILED, req.username, mapOf("reason" to "REGISTRATION_DISABLED", "ip" to ip))
            throw forbidden("REGISTRATION_DISABLED", "Registration is disabled on this server")
        }
        if (req.inviteCode == null || !Crypto.constantTimeEquals(req.inviteCode, code)) {
            audit.log(null, AuditActions.REGISTER_FAILED, req.username, mapOf("reason" to "INVITE_CODE_INVALID", "ip" to ip))
            throw forbidden("INVITE_CODE_INVALID", "Invalid registration code")
        }
        val username = req.username?.trim().orEmpty()
        val password = req.password.orEmpty()
        val displayName = req.displayName?.trim().orEmpty()
        val deviceId = requireDevice(req.deviceId)
        val deviceName = req.deviceName?.trim()?.take(128).orEmpty().ifEmpty { "unknown" }
        if (!usernameRegex.matches(username)) throw badRequest("VALIDATION_ERROR", "username must be 3-32 chars of [A-Za-z0-9_.-]")
        if (password.length < 8 || password.length > 128) throw badRequest("VALIDATION_ERROR", "password must be 8-128 characters")
        if (displayName.isEmpty() || displayName.length > 64) throw badRequest("VALIDATION_ERROR", "displayName must be 1-64 characters")

        val hash = hasher.hash(password)
        val now = config.clock()
        val userId = Ids.newId()
        val role = db.tx {
            if (UserRepo.byUsername(username) != null) throw conflict("USERNAME_TAKEN", "Username already exists")
            val role = when {
                config.adminUsername != null -> if (config.adminUsername == username) Roles.ADMIN else Roles.USER
                Users.selectAll().count() == 0L -> Roles.ADMIN
                else -> Roles.USER
            }
            Users.insert {
                it[id] = userId
                it[Users.username] = username
                it[passwordHash] = hash
                it[Users.displayName] = displayName
                it[avatarUrl] = null
                it[Users.role] = role
                it[coupleId] = null
                it[createdAt] = now
            }
            role
        }
        audit.log(userId, AuditActions.REGISTER, userId, mapOf("username" to username, "role" to role, "deviceId" to deviceId, "ip" to ip))
        return issueNewFamily(userId, deviceId, deviceName)
    }

    suspend fun login(req: LoginRequest, ip: String?): TokenPair {
        val username = req.username?.trim().orEmpty()
        val password = req.password.orEmpty()
        val deviceId = requireDevice(req.deviceId)
        val deviceName = req.deviceName?.trim()?.take(128).orEmpty().ifEmpty { "unknown" }
        val row = db.tx { UserRepo.byUsername(username) }
        val ok = if (row == null) {
            hasher.verify(password, dummyHash); false
        } else hasher.verify(password, row.passwordHash)
        if (!ok || row == null) {
            audit.log(row?.user?.id, AuditActions.LOGIN_FAILED, username.take(64), mapOf("deviceId" to deviceId, "ip" to ip))
            throw unauthorized("INVALID_CREDENTIALS", "Invalid username or password")
        }
        audit.log(row.user.id, AuditActions.LOGIN, row.user.id, mapOf("deviceId" to deviceId, "deviceName" to deviceName, "ip" to ip))
        return issueNewFamily(row.user.id, deviceId, deviceName)
    }

    suspend fun refresh(req: RefreshRequest, ip: String?): TokenPair {
        val token = req.refreshToken?.takeIf { it.isNotBlank() } ?: throw badRequest("VALIDATION_ERROR", "refreshToken is required")
        val deviceId = requireDevice(req.deviceId)
        val hash = Crypto.sha256Hex(token)
        val now = config.clock()

        // Outcome computed inside the tx; errors are thrown after commit so revocations persist.
        val outcome: RefreshOutcome = db.tx {
            val r = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.forUpdate().firstOrNull()
                ?: return@tx RefreshOutcome.Fail(null, "REFRESH_INVALID")
            val userId = r[RefreshTokens.userId]
            val familyId = r[RefreshTokens.familyId]
            when {
                r[RefreshTokens.revokedAt] != null -> RefreshOutcome.Fail(userId, "REFRESH_REVOKED")
                r[RefreshTokens.usedAt] != null -> {
                    // Reuse of an already-rotated token: assume theft, revoke the whole family.
                    revokeFamily(familyId, now)
                    RefreshOutcome.Reuse(userId, familyId)
                }
                r[RefreshTokens.expiresAt] <= now -> RefreshOutcome.Fail(userId, "REFRESH_EXPIRED")
                r[RefreshTokens.deviceId] != deviceId -> RefreshOutcome.Fail(userId, "DEVICE_MISMATCH")
                else -> {
                    val updated = RefreshTokens.update({ (RefreshTokens.id eq r[RefreshTokens.id]) and RefreshTokens.usedAt.isNull() }) {
                        it[usedAt] = now
                    }
                    if (updated != 1) {
                        revokeFamily(familyId, now)
                        RefreshOutcome.Reuse(userId, familyId)
                    } else {
                        val user = UserRepo.byId(userId) ?: return@tx RefreshOutcome.Fail(userId, "REFRESH_INVALID")
                        val newToken = insertRefresh(userId, familyId, deviceId, r[RefreshTokens.deviceName], now)
                        val access = jwt.issue(userId, user.user.coupleId, deviceId, user.role)
                        RefreshOutcome.Ok(TokenPair(access.token, newToken, access.expiresAt, user.user), familyId)
                    }
                }
            }
        }
        when (outcome) {
            is RefreshOutcome.Ok -> {
                audit.log(outcome.pair.user.id, AuditActions.REFRESH, outcome.familyId, mapOf("deviceId" to deviceId, "ip" to ip))
                return outcome.pair
            }
            is RefreshOutcome.Reuse -> {
                audit.log(outcome.userId, AuditActions.REFRESH_REUSE, outcome.familyId, mapOf("deviceId" to deviceId, "ip" to ip))
                throw unauthorized("REFRESH_REUSED", "Refresh token reuse detected; session revoked")
            }
            is RefreshOutcome.Fail -> {
                audit.log(outcome.userId, AuditActions.REFRESH_FAILED, null, mapOf("reason" to outcome.code, "deviceId" to deviceId, "ip" to ip))
                throw unauthorized(outcome.code, "Refresh token rejected")
            }
        }
    }

    /** Revokes the token's whole family (this device session). Idempotent: unknown tokens still return 204. */
    suspend fun logout(req: LogoutRequest, ip: String?) {
        val token = req.refreshToken?.takeIf { it.isNotBlank() } ?: throw badRequest("VALIDATION_ERROR", "refreshToken is required")
        val hash = Crypto.sha256Hex(token)
        val now = config.clock()
        val info = db.tx {
            val r = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.firstOrNull() ?: return@tx null
            revokeFamily(r[RefreshTokens.familyId], now)
            r[RefreshTokens.userId] to r[RefreshTokens.familyId]
        }
        audit.log(info?.first, AuditActions.LOGOUT, info?.second, mapOf("known" to (info != null), "ip" to ip))
    }

    /** Issues a fresh TokenPair for the user's current DB state (used after login/register). */
    private suspend fun issueNewFamily(userId: String, deviceId: String, deviceName: String): TokenPair {
        val now = config.clock()
        return db.tx {
            // Device binding: a new login on the same device ends that device's previous sessions.
            RefreshTokens.update({ (RefreshTokens.userId eq userId) and (RefreshTokens.deviceId eq deviceId) and RefreshTokens.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
            val user = UserRepo.byId(userId) ?: throw ApiException(io.ktor.http.HttpStatusCode.InternalServerError, "INTERNAL", "user vanished")
            val refresh = insertRefresh(userId, Ids.newId(), deviceId, deviceName, now)
            val access = jwt.issue(userId, user.user.coupleId, deviceId, user.role)
            TokenPair(access.token, refresh, access.expiresAt, user.user)
        }
    }

    private fun insertRefresh(userId: String, familyId: String, deviceId: String, deviceName: String, now: Long): String {
        val raw = Crypto.b64url(Crypto.randomBytes(32))
        RefreshTokens.insert {
            it[id] = Ids.newId()
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.familyId] = familyId
            it[tokenHash] = Crypto.sha256Hex(raw)
            it[RefreshTokens.deviceId] = deviceId
            it[RefreshTokens.deviceName] = deviceName
            it[createdAt] = now
            it[expiresAt] = now + config.refreshTokenTtlMs
            it[usedAt] = null
            it[revokedAt] = null
        }
        return raw
    }

    private fun revokeFamily(familyId: String, now: Long) {
        RefreshTokens.update({ (RefreshTokens.familyId eq familyId) and RefreshTokens.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
    }

    private fun requireDevice(deviceId: String?): String {
        val d = deviceId?.trim().orEmpty()
        if (d.isEmpty() || d.length > 128) throw badRequest("VALIDATION_ERROR", "deviceId is required (max 128 chars)")
        return d
    }

    private sealed interface RefreshOutcome {
        data class Ok(val pair: TokenPair, val familyId: String) : RefreshOutcome
        data class Reuse(val userId: String, val familyId: String) : RefreshOutcome
        data class Fail(val userId: String?, val code: String) : RefreshOutcome
    }
}
