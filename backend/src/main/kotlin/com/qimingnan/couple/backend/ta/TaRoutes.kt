package com.qimingnan.couple.backend.ta

import com.qimingnan.couple.backend.auth.authUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** TA page (contract "TA"). Must be mounted inside authenticate(JWT_AUTH). */
fun Route.taRoutes(service: TaService) {
    get("/ta/summary") { call.respond(service.summary(call.authUser())) }
    put("/ta/me") {
        service.updateMe(call.authUser(), call.receive<UpdateMeRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/ta/days") { call.respond(service.addDay(call.authUser(), call.receive<UpsertDayRequest>())) }
    put("/ta/days/{id}") {
        call.respond(service.updateDay(call.authUser(), call.parameters["id"]!!, call.receive<UpsertDayRequest>()))
    }
    delete("/ta/days/{id}") {
        service.deleteDay(call.authUser(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}
