package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.JwtService
import com.qimingnan.couple.backend.auth.UserRepo
import com.qimingnan.couple.backend.db.Db
import java.util.concurrent.ConcurrentHashMap

object MqttTopics {
    /**
     * Subscribe side: a client may read any topic under couple/{its own coupleId}/... .
     * Rejects shared/queue subscriptions, wildcards in the couple segment, and anything outside the root.
     */
    fun allowedRead(topic: String, coupleId: String?): Boolean {
        if (coupleId == null) return false
        if (topic.startsWith("\$")) return false // $share/..., $queue/..., $SYS/...
        val levels = topic.split('/')
        if (levels.size < 3) return false
        if (levels[0] != "couple" || levels[1] != coupleId) return false
        return true
    }

    /**
     * Publish side: clients only write their own presence. Feed/chat/plugin events are server-pushed
     * (contract "Feed"/"Chat": client publishes to those topics are denied by ACL).
     */
    fun allowedWrite(topic: String, coupleId: String?): Boolean {
        if (!allowedRead(topic, coupleId)) return false
        val levels = topic.split('/')
        return levels.size == 3 && levels[2] == "presence"
    }
}

/**
 * Single source of truth for MQTT authentication and topic authorization. Used by the EMQX HTTP hooks
 * (external mode) and by the embedded Moquette broker (embedded mode).
 *
 * @param coupleCacheTtlMs how long a user's coupleId lookup is cached for ACL checks (0 = always hit the DB).
 *   The embedded broker checks every PUBLISH, so it caches briefly; the HTTP hooks keep the uncached behaviour
 *   (EMQX has its own authz cache).
 */
class MqttAccessService(
    private val db: Db,
    private val jwt: JwtService,
    private val audit: AuditService,
    private val coupleCacheTtlMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Cached(val coupleId: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** username must be the userId, password a valid (unexpired) access JWT whose sub is that user, who must exist. */
    suspend fun authenticate(username: String?, password: String?, clientId: String?): Boolean {
        val user = username.orEmpty()
        val decoded = password?.let { jwt.verifyOrNull(it) }
        val ok = decoded != null && decoded.subject == user && db.tx { UserRepo.byId(user) } != null
        if (!ok) deny(user, clientId, "bad_credentials")
        return ok
    }

    /**
     * Embedded broker only: persistent sessions (cleanSession=false) are keyed by client id, so a client id must
     * belong to the authenticated user, otherwise one user could take over another user's queued messages.
     * Accepted: `{userId}`, `{userId}-...`, and the app's `a-{userId}-...`.
     */
    fun clientIdAllowed(username: String, clientId: String?): Boolean {
        if (clientId.isNullOrEmpty() || username.isEmpty()) return false
        return clientId == username || clientId.startsWith("$username-") || clientId.startsWith("a-$username-")
    }

    suspend fun recordDenied(username: String?, clientId: String?, reason: String) = deny(username.orEmpty(), clientId, reason)

    private suspend fun deny(user: String, clientId: String?, reason: String) {
        audit.log(null, AuditActions.MQTT_AUTH_DENIED, user.take(64), mapOf("clientid" to clientId?.take(64), "reason" to reason))
    }

    suspend fun coupleIdOf(username: String): String? {
        if (coupleCacheTtlMs > 0) {
            val hit = cache[username]
            if (hit != null && clock() - hit.at < coupleCacheTtlMs) return hit.coupleId
        }
        val cid = db.tx { UserRepo.byId(username)?.user?.coupleId }
        if (coupleCacheTtlMs > 0) cache[username] = Cached(cid, clock())
        return cid
    }

    /** Subscribe ACL (see [MqttTopics.allowedRead]). */
    suspend fun canRead(username: String?, topic: String?): Boolean {
        if (username.isNullOrEmpty() || topic == null) return false
        return MqttTopics.allowedRead(topic, coupleIdOf(username))
    }

    /** Publish ACL (see [MqttTopics.allowedWrite]). Server pushes use internalPublish, not this check. */
    suspend fun canWrite(username: String?, topic: String?): Boolean {
        if (username.isNullOrEmpty() || topic == null) return false
        return MqttTopics.allowedWrite(topic, coupleIdOf(username))
    }

    /** True when either direction is allowed (used by the Last Will guard, which only writes presence). */
    suspend fun canAccess(username: String?, topic: String?): Boolean = canWrite(username, topic)
}
