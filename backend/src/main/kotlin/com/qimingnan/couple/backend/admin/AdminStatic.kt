package com.qimingnan.couple.backend.admin

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.ErrorBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Serves the admin SPA from ADMIN_STATIC_DIR (Windows-native deployment has no nginx/Caddy), mirroring
 * admin/docker/nginx.conf:
 * - `/config.js`: runtime config, same content as admin/docker/40-couple-config.sh (`window.__COUPLE_CONFIG__`);
 * - `/assets/...`: immutable long cache; everything else `no-cache`;
 * - unknown paths fall back to index.html (client-side routing), except API, MQTT and deploy paths, which get 404.
 * Mounted last so that every real API route wins.
 */
fun Route.adminStaticRoutes(dir: String, apiBase: String, version: String) {
    val root = File(dir).canonicalFile
    val index = File(root, "index.html")
    val configJs = buildString {
        append("window.__COUPLE_CONFIG__ = {\n")
        append("  apiBase: ").append(AppJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(apiBase))).append(",\n")
        append("  version: ").append(AppJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(version))).append("\n")
        append("};\n")
    }

    get("/config.js") {
        call.securityHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(configJs, ContentType.Application.JavaScript)
    }
    get("/") { call.serveSpa(root, index, "/") }
    get("{path...}") { call.serveSpa(root, index, call.request.path()) }
}

private val NEVER_SPA = listOf("/api/", "/api", "/mqtt", "/_deploy/", "/_deploy")

private fun isReserved(path: String): Boolean =
    NEVER_SPA.any { path == it || (it.endsWith("/") && path.startsWith(it)) } || path.startsWith("/mqtt/")

private suspend fun ApplicationCall.serveSpa(root: File, index: File, path: String) {
    if (isReserved(path)) {
        respond(HttpStatusCode.NotFound, ErrorBody("NOT_FOUND", "Not found"))
        return
    }
    val rel = path.trimStart('/')
    val target = if (rel.isEmpty()) index else File(root, rel).canonicalFile
    // Path traversal guard: the resolved file must stay inside the static root.
    val inside = target.path == root.path || target.path.startsWith(root.path + File.separator)
    securityHeaders()
    if (inside && target.isFile) {
        val cache = if (rel.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache"
        response.header(HttpHeaders.CacheControl, cache)
        respondFile(target)
        return
    }
    // Missing asset files are real 404s; anything else is a client-side route.
    if (rel.startsWith("assets/") || !index.isFile) {
        respond(HttpStatusCode.NotFound, ErrorBody("NOT_FOUND", "Not found"))
        return
    }
    response.header(HttpHeaders.CacheControl, "no-cache")
    respondFile(index)
}

private fun ApplicationCall.securityHeaders() {
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "same-origin")
    response.header("X-Frame-Options", "DENY")
}
