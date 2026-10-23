package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.admin.requireAdmin
import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile

/** Byte range selection for GET .../apk (single range only; multi-range requests get the full body). */
sealed interface ByteRangeSel {
    data object Full : ByteRangeSel
    data class Partial(val start: Long, val endInclusive: Long) : ByteRangeSel
    data object Unsatisfiable : ByteRangeSel

    companion object {
        fun parse(header: String?, size: Long): ByteRangeSel {
            if (header.isNullOrBlank()) return Full
            val h = header.trim()
            if (!h.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = h.substring(6).trim()
            if (',' in spec) return Full
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) {
                val n = b.toLongOrNull() ?: return Full
                if (n <= 0 || size == 0L) return Unsatisfiable
                return Partial(maxOf(0, size - n), size - 1)
            }
            val start = a.toLongOrNull() ?: return Full
            val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return Full)
            if (start >= size || end < start) return Unsatisfiable
            return Partial(start, minOf(end, size - 1))
        }
    }
}

private class FileRangeContent(
    private val file: File,
    private val start: Long,
    private val length: Long,
    override val status: HttpStatusCode,
) : OutgoingContent.WriteChannelContent() {
    override val contentType = ContentType("application", "vnd.android.package-archive")
    override val contentLength = length
    override suspend fun writeTo(channel: ByteWriteChannel) {
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    channel.writeFully(buf, 0, n)
                    left -= n
                }
            }
        }
    }
}

/** GET /app/update and the APK download. Must be mounted inside authenticate(JWT_AUTH): any signed-in user. */
fun Route.appUpdateRoutes(service: ReleaseService) {
    route("/app") {
        get("/update") {
            val vc = call.request.queryParameters["versionCode"]?.toIntOrNull()
                ?: throw badRequest("VALIDATION_ERROR", "versionCode is required and must be an integer")
            val deviceId = call.request.queryParameters["deviceId"]?.takeIf { it.isNotBlank() }?.take(128)
                ?: call.authUser().deviceId
            call.respond(service.checkUpdate(vc, deviceId))
        }
        get("/releases/{versionCode}/apk") {
            val vc = call.parameters["versionCode"]?.toIntOrNull() ?: throw notFound()
            val (release, file) = service.publishedFile(vc)
            call.respondApk(file, release)
        }
    }
}

private suspend fun ApplicationCall.respondApk(file: File, release: AppRelease) {
    val size = file.length()
    val etag = "\"${release.sha256}\""
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.CacheControl, "private, max-age=86400, no-transform")
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"couple-${release.versionName}.apk\"")
    val inm = request.headers[HttpHeaders.IfNoneMatch]
    if (inm != null && inm.split(',').any { it.trim().removePrefix("W/") == etag || it.trim() == "*" }) {
        respond(HttpStatusCode.NotModified)
        return
    }
    // If-Range: only honour Range when the validator still matches (otherwise send the whole new file).
    val ifRange = request.headers[HttpHeaders.IfRange]
    val rangeHeader = if (ifRange != null && ifRange.trim() != etag) null else request.headers[HttpHeaders.Range]
    when (val sel = ByteRangeSel.parse(rangeHeader, size)) {
        ByteRangeSel.Full -> respond(FileRangeContent(file, 0, size, HttpStatusCode.OK))
        is ByteRangeSel.Partial -> {
            response.header(HttpHeaders.ContentRange, "bytes ${sel.start}-${sel.endInclusive}/$size")
            respond(FileRangeContent(file, sel.start, sel.endInclusive - sel.start + 1, HttpStatusCode.PartialContent))
        }
        ByteRangeSel.Unsatisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes */$size")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable, com.qimingnan.couple.backend.common.ErrorBody("RANGE_NOT_SATISFIABLE", "Requested range not satisfiable"))
        }
    }
}

/** Admin release management. Must be mounted inside authenticate(JWT_AUTH). */
fun Route.adminReleaseRoutes(
    db: Db,
    audit: AuditService,
    service: ReleaseService,
    mirror: ReleaseMirror,
    settings: ReleaseSettingsService,
    scope: CoroutineScope,
) {
    route("/admin") {
        get("/releases") {
            val admin = call.requireAdmin(db, audit)
            val list = service.list()
            audit.log(admin, AuditActions.ADMIN_RELEASE_LIST, "releases", mapOf("count" to list.size))
            call.respond(list)
        }
        get("/releases/sync") {
            call.requireAdmin(db, audit)
            call.respond(mirror.state)
        }
        post("/releases/sync") {
            val admin = call.requireAdmin(db, audit)
            val wait = call.request.queryParameters["wait"] == "true"
            audit.log(admin, AuditActions.ADMIN_RELEASE_SYNC, "releases", mapOf("wait" to wait))
            if (wait) {
                call.respond(mirror.syncNow(manual = true))
            } else {
                // Downloads can exceed Cloudflare's 100 s request timeout: run in the background and let the UI poll.
                if (!mirror.state.running) scope.launch { mirror.syncNow(manual = true) }
                call.respond(HttpStatusCode.Accepted, mirror.state.copy(running = true))
            }
        }
        get("/releases/settings") {
            val admin = call.requireAdmin(db, audit)
            val view = settings.view()
            audit.log(admin, AuditActions.ADMIN_RELEASE_SETTINGS_VIEW, "release.settings")
            call.respond(view)
        }
        put("/releases/settings") {
            val admin = call.requireAdmin(db, audit)
            val (view, changed) = settings.update(call.receive())
            audit.log(
                admin, AuditActions.ADMIN_RELEASE_SETTINGS_UPDATE, "release.settings",
                mapOf(
                    "repo" to view.repo, "autoPublish" to view.autoPublish, "pollMinutes" to view.pollMinutes,
                    "changedSecrets" to changed.joinToString(","),
                ),
            )
            call.respond(view)
        }
        patch("/releases/{versionCode}") {
            val admin = call.requireAdmin(db, audit)
            val vc = call.parameters["versionCode"]?.toIntOrNull() ?: throw notFound()
            val body = call.receive<ReleasePatch>()
            val (before, after) = service.patch(vc, body)
            audit.log(
                admin, AuditActions.ADMIN_RELEASE_UPDATE, "release:$vc",
                mapOf(
                    "status" to "${before.status}->${after.status}",
                    "rolloutPercent" to "${before.rolloutPercent}->${after.rolloutPercent}",
                    "minSupportedVersionCode" to "${before.minSupportedVersionCode}->${after.minSupportedVersionCode}",
                    "notesChanged" to (before.notes != after.notes),
                ),
            )
            call.respond(after)
        }
        get("/server/update-status") {
            val admin = call.requireAdmin(db, audit)
            audit.log(admin, AuditActions.ADMIN_SERVER_UPDATE_STATUS, "server")
            call.respond(service.serverUpdateStatus())
        }
    }
}

/**
 * POST /internal/updater/status from the server-updater sidecar. Same guard as the EMQX hooks
 * (X-Internal-Token == MQTT_HOOK_SECRET, no Cloudflare traffic), except the secret is mandatory here.
 */
fun Route.updaterStatusRoutes(config: AppConfig, audit: AuditService, service: ReleaseService) {
    post("/internal/updater/status") {
        if (call.request.headers["CF-Connecting-IP"] != null) throw notFound()
        val secret = config.mqttHookSecret ?: throw notFound()
        val given = call.request.headers["X-Internal-Token"] ?: throw notFound()
        if (!Crypto.constantTimeEquals(given, secret)) throw notFound()
        val text = call.receiveText()
        if (text.length > 16 * 1024) throw badRequest("VALIDATION_ERROR", "status too large")
        val body = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw badRequest("VALIDATION_ERROR", "body must be a JSON object")
        if (service.storeUpdaterStatus(body)) {
            audit.log(
                null, AuditActions.UPDATER_STATUS, "server-updater",
                mapOf("current" to body["current"], "lastResult" to body["lastResult"], "lastError" to body["lastError"]),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
