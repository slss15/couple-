package com.qimingnan.couple.backend.pairing

import com.qimingnan.couple.backend.auth.authUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Must be mounted inside authenticate(JWT_AUTH). */
fun Route.pairingRoutes(service: PairingService) {
    post("/pair/code") {
        call.respond(service.createCode(call.authUser().userId))
    }
    post("/pair/join") {
        val req = call.receive<JoinRequest>()
        call.respond(service.join(call.authUser().userId, req.code))
    }
    get("/couple") {
        call.respond(service.getCouple(call.authUser().userId))
    }
    post("/couple/unpair/request") {
        call.respond(service.requestUnpair(call.authUser().userId))
    }
    post("/couple/unpair/confirm") {
        val req = call.receive<UnpairConfirmRequest>()
        service.confirmUnpair(call.authUser().userId, req.requestId)
        call.respond(HttpStatusCode.NoContent)
    }
}
