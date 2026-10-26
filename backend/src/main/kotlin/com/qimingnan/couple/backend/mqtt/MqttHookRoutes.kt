package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
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

/**
 * Internal hooks for EMQX. Never expose through Cloudflare: requests carrying CF-Connecting-IP are rejected,
 * and when MQTT_HOOK_SECRET is set the X-Internal-Token header must match.
 * Always answers HTTP 200 with allow/deny so EMQX does not fall through to other authenticators.
 */
fun Route.mqttHookRoutes(config: AppConfig, access: MqttAccessService) {
    fun ApplicationCall.guard() {
        if (request.headers["CF-Connecting-IP"] != null) throw notFound()
        // MQTT_HOOK_SECRET is mandatory in external mode (AppConfig.fromEnv); without it the hooks stay closed.
        val secret = config.mqttHookSecret ?: throw notFound()
        val given = request.headers["X-Internal-Token"] ?: throw notFound()
        if (!Crypto.constantTimeEquals(given, secret)) throw notFound()
    }

    route("/internal/mqtt") {
        post("/auth") {
            call.guard()
            val req = call.receive<MqttAuthRequest>()
            val ok = access.authenticate(req.username, req.password, req.clientid)
            call.respond(MqttAuthResponse(if (ok) "allow" else "deny", false))
        }
        post("/acl") {
            call.guard()
            val req = call.receive<MqttAclRequest>()
            // EMQX sends action=publish|subscribe; anything else is treated as a read.
            val ok = if (req.action == "publish") {
                access.canWrite(req.username, req.topic)
            } else {
                access.canRead(req.username, req.topic)
            }
            call.respond(MqttAclResponse(if (ok) "allow" else "deny"))
        }
    }
}
