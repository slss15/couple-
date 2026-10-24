package com.qimingnan.couple.backend.pairing

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.UserRepo
import com.qimingnan.couple.backend.common.ApiException
import com.qimingnan.couple.backend.common.Couple
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.conflict
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.common.unauthorized
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.PairCodes
import com.qimingnan.couple.backend.db.UnpairRequests
import com.qimingnan.couple.backend.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

@Serializable
data class PairCodeResponse(val code: String, val expiresAt: Long)

@Serializable
data class JoinRequest(val code: String? = null)

@Serializable
data class UnpairRequestResponse(val requestId: String)

@Serializable
data class UnpairConfirmRequest(val requestId: String? = null)

class PairingService(
    private val db: Db,
    private val config: AppConfig,
    private val audit: AuditService,
) {
    private val codeRegex = Regex("^[0-9]{6}$")

    suspend fun createCode(userId: String): PairCodeResponse {
        val now = config.clock()
        val expiresAt = now + config.pairCodeTtlMs
        val code = db.tx {
            val me = UserRepo.byIdForUpdate(userId) ?: throw unauthorized()
            if (me.user.coupleId != null) throw conflict("ALREADY_PAIRED", "You are already paired")
            var candidate: String
            var attempts = 0
            do {
                candidate = "%06d".format(Crypto.random.nextInt(1_000_000))
                val clash = PairCodes.selectAll().where {
                    (PairCodes.code eq candidate) and (PairCodes.expiresAt greater now) and (PairCodes.userId neq userId)
                }.count() > 0
                attempts++
            } while (clash && attempts < 20)
            // Issuing a new code replaces the old one.
            PairCodes.deleteWhere { PairCodes.userId eq userId }
            PairCodes.insert {
                it[PairCodes.userId] = userId
                it[PairCodes.code] = candidate
                it[createdAt] = now
                it[PairCodes.expiresAt] = expiresAt
            }
            candidate
        }
        audit.log(userId, AuditActions.PAIR_CODE, userId, mapOf("expiresAt" to expiresAt))
        return PairCodeResponse(code, expiresAt)
    }

    suspend fun join(userId: String, rawCode: String?): Couple {
        val code = rawCode?.trim().orEmpty()
        try {
            if (!codeRegex.matches(code)) throw badRequest("CODE_INVALID", "Pairing code must be 6 digits")
            val now = config.clock()
            val couple = db.tx {
                val me = UserRepo.byIdForUpdate(userId) ?: throw unauthorized()
                if (me.user.coupleId != null) throw conflict("ALREADY_PAIRED", "You are already paired")
                val rows = PairCodes.selectAll().where { PairCodes.code eq code }.forUpdate().toList()
                if (rows.isEmpty()) throw badRequest("CODE_INVALID", "Pairing code is invalid")
                if (rows.any { it[PairCodes.userId] == userId }) throw badRequest("SELF_PAIR", "You cannot pair with yourself")
                val live = rows.firstOrNull { it[PairCodes.expiresAt] > now }
                    ?: throw badRequest("CODE_EXPIRED", "Pairing code has expired")
                val ownerId = live[PairCodes.userId]
                val owner = UserRepo.byIdForUpdate(ownerId) ?: throw badRequest("CODE_INVALID", "Pairing code is invalid")
                if (owner.user.coupleId != null) throw badRequest("CODE_INVALID", "Pairing code is invalid")
                // Single use: consume the code atomically.
                val consumed = PairCodes.deleteWhere { (PairCodes.userId eq ownerId) and (PairCodes.code eq code) }
                if (consumed != 1) throw badRequest("CODE_INVALID", "Pairing code is invalid")
                PairCodes.deleteWhere { PairCodes.userId eq userId }

                val coupleId = Ids.newId()
                Couples.insert {
                    it[id] = coupleId
                    it[userA] = ownerId
                    it[userB] = userId
                    it[pairedAt] = now
                    it[anniversary] = null
                    it[deletedAt] = null
                }
                Users.update({ (Users.id eq ownerId) or (Users.id eq userId) }) { it[Users.coupleId] = coupleId }
                loadCouple(coupleId)!!
            }
            audit.log(userId, AuditActions.PAIR_JOIN, couple.id, mapOf("partnerId" to couple.members.first { it.id != userId }.id))
            return couple
        } catch (e: ApiException) {
            audit.log(userId, AuditActions.PAIR_JOIN_FAILED, null, mapOf("reason" to e.code))
            throw e
        }
    }

    suspend fun getCouple(userId: String): Couple = db.tx {
        val me = UserRepo.byId(userId) ?: throw unauthorized()
        val cid = me.user.coupleId ?: throw notFound("NOT_PAIRED", "You are not paired")
        loadCouple(cid) ?: throw notFound("NOT_PAIRED", "You are not paired")
    }

    suspend fun requestUnpair(userId: String): UnpairRequestResponse {
        val now = config.clock()
        val (requestId, coupleId) = db.tx {
            val me = UserRepo.byId(userId) ?: throw unauthorized()
            val cid = me.user.coupleId ?: throw notFound("NOT_PAIRED", "You are not paired")
            // A new request supersedes any still-open one for this couple.
            UnpairRequests.update({
                (UnpairRequests.coupleId eq cid) and UnpairRequests.confirmedAt.isNull() and UnpairRequests.cancelledAt.isNull()
            }) { it[cancelledAt] = now }
            val id = Ids.newId()
            UnpairRequests.insert {
                it[UnpairRequests.id] = id
                it[UnpairRequests.coupleId] = cid
                it[requestedBy] = userId
                it[createdAt] = now
                it[expiresAt] = now + config.unpairRequestTtlMs
            }
            id to cid
        }
        audit.log(userId, AuditActions.UNPAIR_REQUEST, coupleId, mapOf("requestId" to requestId))
        return UnpairRequestResponse(requestId)
    }

    suspend fun confirmUnpair(userId: String, requestId: String?) {
        try {
            val explicitId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            val now = config.clock()
            val (coupleId, rid) = db.tx {
                val me = UserRepo.byIdForUpdate(userId) ?: throw unauthorized()
                val req = if (explicitId != null) {
                    UnpairRequests.selectAll().where { UnpairRequests.id eq explicitId }.forUpdate().firstOrNull()
                        ?: throw notFound("UNPAIR_REQUEST_NOT_FOUND", "Unpair request not found")
                } else {
                    // No id: the newest open, unexpired request made by the OTHER member of my couple.
                    val myCid = me.user.coupleId ?: throw notFound("UNPAIR_REQUEST_NOT_FOUND", "Unpair request not found")
                    UnpairRequests.selectAll().where {
                        (UnpairRequests.coupleId eq myCid) and
                            (UnpairRequests.requestedBy neq userId) and
                            UnpairRequests.confirmedAt.isNull() and
                            UnpairRequests.cancelledAt.isNull() and
                            (UnpairRequests.expiresAt greater now)
                    }.orderBy(UnpairRequests.createdAt, SortOrder.DESC).limit(1).forUpdate().firstOrNull()
                        ?: throw notFound("UNPAIR_REQUEST_NOT_FOUND", "Unpair request not found")
                }
                val rid = req[UnpairRequests.id]
                val cid = req[UnpairRequests.coupleId]
                if (me.user.coupleId != cid) throw notFound("UNPAIR_REQUEST_NOT_FOUND", "Unpair request not found")
                if (req[UnpairRequests.requestedBy] == userId) {
                    throw forbidden("NOT_OTHER_MEMBER", "The unpair request must be confirmed by the other member")
                }
                if (req[UnpairRequests.confirmedAt] != null || req[UnpairRequests.cancelledAt] != null) {
                    throw conflict("UNPAIR_REQUEST_CLOSED", "Unpair request is no longer open")
                }
                if (req[UnpairRequests.expiresAt] <= now) throw badRequest("UNPAIR_REQUEST_EXPIRED", "Unpair request has expired")

                UnpairRequests.update({ UnpairRequests.id eq rid }) {
                    it[confirmedBy] = userId
                    it[confirmedAt] = now
                }
                Couples.update({ Couples.id eq cid }) { it[deletedAt] = now } // soft delete
                Users.update({ Users.coupleId eq cid }) { it[Users.coupleId] = null }
                cid to rid
            }
            audit.log(userId, AuditActions.UNPAIR_CONFIRM, coupleId, mapOf("requestId" to rid))
        } catch (e: ApiException) {
            audit.log(userId, AuditActions.UNPAIR_CONFIRM_FAILED, requestId?.take(64), mapOf("reason" to e.code))
            throw e
        }
    }

    /** Inside a transaction. Returns null for unknown or soft-deleted couples. */
    private fun loadCouple(coupleId: String): Couple? {
        val c = Couples.selectAll().where { (Couples.id eq coupleId) and Couples.deletedAt.isNull() }.firstOrNull() ?: return null
        val members = listOf(c[Couples.userA], c[Couples.userB]).map { id ->
            UserRepo.byId(id)?.user ?: throw ApiException(io.ktor.http.HttpStatusCode.InternalServerError, "INTERNAL", "member missing")
        }
        return Couple(id = c[Couples.id], members = members, pairedAt = c[Couples.pairedAt], anniversary = c[Couples.anniversary])
    }
}
