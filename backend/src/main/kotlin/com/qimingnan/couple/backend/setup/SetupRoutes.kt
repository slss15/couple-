package com.qimingnan.couple.backend.setup

import com.qimingnan.couple.backend.auth.AUTH_RATE_LIMIT
import com.qimingnan.couple.backend.auth.clientIp
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Public (no auth). Rate-limited like /auth. */
fun Route.setupRoutes(service: SetupService, mqttPath: String) {
    route("/setup") {
        rateLimit(AUTH_RATE_LIMIT) {
            get("/status") {
                call.respond(service.status(call.suggestedUrls(mqttPath)))
            }
            post {
                if (!service.needed()) throw SetupService.gone()
                val req = call.receive<SetupRequest>()
                call.respond(service.setup(req, call.clientIp(), call.suggestedUrls(mqttPath)))
            }
        }
    }
}
