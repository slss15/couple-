package com.qimingnan.couple.backend.pairing

import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.common.AppJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException

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
        // Body is optional: empty body, {} or {"requestId":null} confirm the newest open request from the partner.
        val text = call.receiveText()
        val req = if (text.isBlank()) {
            UnpairConfirmRequest()
        } else {
            try {
                AppJson.decodeFromString(UnpairConfirmRequest.serializer(), text)
            } catch (e: SerializationException) {
                throw BadRequestException("Malformed request body", e)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("Malformed request body", e)
            }
        }
        service.confirmUnpair(call.authUser().userId, req.requestId)
        call.respond(HttpStatusCode.NoContent)
    }
}
