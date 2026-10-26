package com.qimingnan.couple.backend.chat

import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.common.badRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Chat P1a (contract "Chat"). Must be mounted inside authenticate(JWT_AUTH). */
fun Route.chatRoutes(service: ChatService) {
    post("/chat/messages") {
        call.respond(service.send(call.authUser(), call.receive<SendMessageRequest>()))
    }
    get("/chat/messages") {
        val limit = call.request.queryParameters["limit"]?.let {
            it.toIntOrNull() ?: throw badRequest("VALIDATION_ERROR", "limit must be an integer")
        }
        call.respond(
            service.list(
                caller = call.authUser(),
                after = call.request.queryParameters["after"],
                before = call.request.queryParameters["before"],
                limit = limit,
            ),
        )
    }
    post("/chat/messages/{id}/recall") {
        call.respond(service.recall(call.authUser(), call.parameters["id"]!!))
    }
    post("/chat/read") {
        service.markRead(call.authUser(), call.receive<ChatReadRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    get("/chat/state") {
        call.respond(service.state(call.authUser()))
    }
}
