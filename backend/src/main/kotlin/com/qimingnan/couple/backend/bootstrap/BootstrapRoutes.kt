package com.qimingnan.couple.backend.bootstrap

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Public (no auth). */
fun Route.bootstrapPublicRoutes(service: BootstrapService) {
    get("/bootstrap/pubkey") {
        call.respond(service.pubkey())
    }
}
