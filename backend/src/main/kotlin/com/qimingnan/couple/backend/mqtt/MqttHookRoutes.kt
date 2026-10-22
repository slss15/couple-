package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.JwtService
import com.qimingnan.couple.backend.auth.UserRepo
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** Body EMQX is configured to send (see docker-compose.yml). */
@Serializable
data class MqttAuthRequest(val username: String? = null, val password: String? = null, val clientid: String? = null)

@Serializable
data class MqttAclRequest(val username: String? = null, val topic: String? = null, val action: String? = null, val clientid: String? = null)

/** EMQX 5 HTTP authn response. */
@Serializable
data class MqttAuthResponse(val result: String, val is_superuser: Boolean = false)

/** EMQX 5 HTTP authz response. */
@Serializable
data class MqttAclResponse(val result: String)

object MqttTopics {
    /**
     * A client may only publish/subscribe under couple/{its own coupleId}/... .
     * Rejects shared/queue subscriptions, wildcards in the couple segment, and anything outside the root.
     */
    fun allowed(topic: String, coupleId: String?): Boolean {
        if (coupleId == null) return false
        if (topic.startsWith("\$")) return false // $share/..., $queue/..., $SYS/...
        val levels = topic.split('/')
        if (levels.size < 3) return false
        if (levels[0] != "couple" || levels[1] != coupleId) return false
        return true
    }
}

/**
 * Internal hooks for EMQX. Never expose through Cloudflare: requests carrying CF-Connecting-IP are rejected,
 * and when MQTT_HOOK_SECRET is set the X-Internal-Token header must match.
 * Always answers HTTP 200 with allow/deny so EMQX does not fall through to other authenticators.
 */
fun Route.mqttHookRoutes(config: AppConfig, db: Db, jwt: JwtService, audit: AuditService) {
    fun ApplicationCall.guard() {
        if (request.headers["CF-Connecting-IP"] != null) throw notFound()
        val secret = config.mqttHookSecret ?: return
        val given = request.headers["X-Internal-Token"] ?: throw notFound()
        if (!Crypto.constantTimeEquals(given, secret)) throw notFound()
    }

    route("/internal/mqtt") {
        post("/auth") {
            call.guard()
            val req = call.receive<MqttAuthRequest>()
            val username = req.username.orEmpty()
            val decoded = req.password?.let { jwt.verifyOrNull(it) }
            val ok = decoded != null && decoded.subject == username &&
                db.tx { UserRepo.byId(username) } != null
            if (!ok) {
                audit.log(null, AuditActions.MQTT_AUTH_DENIED, username.take(64), mapOf("clientid" to req.clientid?.take(64)))
            }
            call.respond(MqttAuthResponse(if (ok) "allow" else "deny", false))
        }
        post("/acl") {
            call.guard()
            val req = call.receive<MqttAclRequest>()
            val coupleId = req.username?.let { u -> db.tx { UserRepo.byId(u)?.user?.coupleId } }
            val ok = req.topic != null && MqttTopics.allowed(req.topic, coupleId)
            call.respond(MqttAclResponse(if (ok) "allow" else "deny"))
        }
    }
}
