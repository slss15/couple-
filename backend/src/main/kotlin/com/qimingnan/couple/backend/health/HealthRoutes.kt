package com.qimingnan.couple.backend.health

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.util.Properties

@Serializable
data class HealthResponse(val status: String, val version: String, val protocolVersion: Int)

const val PROTOCOL_VERSION = 1

val SERVER_VERSION: String by lazy {
    Properties().apply {
        HealthResponse::class.java.getResourceAsStream("/version.properties")?.use { load(it) }
    }.getProperty("version") ?: "0.0.0"
}

fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse("ok", SERVER_VERSION, PROTOCOL_VERSION))
    }
}
