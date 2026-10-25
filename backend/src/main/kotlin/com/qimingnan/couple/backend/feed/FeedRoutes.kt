package com.qimingnan.couple.backend.feed

import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.admin.requireAdmin
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.media.MediaService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import java.security.MessageDigest

private fun sha256Hex(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun mimeTypeOf(o: com.qimingnan.couple.backend.media.MediaObject): ContentType =
    ContentType.parse(o.mimeType)

/** Media upload/download + avatar (contract "Media storage"). Must be mounted inside authenticate(JWT_AUTH). */
fun Route.mediaRoutes(service: MediaService) {
    post("/media") {
        val caller = call.authUser()
        var bytes: ByteArray? = null
        var fileName: String? = null
        var contentType: String? = null
        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (bytes == null) {
                        bytes = part.provider().readRemaining().readByteArray()
                        fileName = part.originalFileName
                        contentType = part.contentType?.toString()
                    }
                }
                else -> {}
            }
            part.dispose()
        }
        val data = bytes ?: throw badRequest("VALIDATION_ERROR", "multipart field 'file' required")
        val view = service.upload(caller, service.coupleIdOf(caller.userId), fileName, contentType, data)
        call.respond(view)
    }
    get("/media/{key}") { serveMedia(call, service, thumb = false) }
    get("/media/{key}/thumb") { serveMedia(call, service, thumb = true) }
    post("/users/me/avatar") {
        val caller = call.authUser()
        val req = try {
            call.receive<AvatarRequest>()
        } catch (e: SerializationException) {
            throw badRequest("VALIDATION_ERROR", "body must be JSON with storageKey")
        }
        service.setAvatar(caller, req.storageKey)
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun serveMedia(call: ApplicationCall, service: MediaService, thumb: Boolean) {
    val caller = call.authUser()
    val key = call.parameters["key"] ?: throw notFound()
    val (o, bytes) = service.bytesForReading(caller, key, thumb)
    val etag = "\"${sha256Hex(bytes)}\""
    call.response.header(io.ktor.http.HttpHeaders.ETag, etag)
    call.response.header(io.ktor.http.HttpHeaders.CacheControl, "private, max-age=86400, no-transform")
    val inm = call.request.headers[io.ktor.http.HttpHeaders.IfNoneMatch]
    if (inm != null && inm.split(',').any { it.trim().removePrefix("W/") == etag || it.trim() == "*" }) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    call.respondBytes(bytes, mimeTypeOf(o))
}

/** Feed (contract "Feed"). Must be mounted inside authenticate(JWT_AUTH). */
fun Route.feedRoutes(service: FeedService) {
    post("/feed/posts") {
        val req = call.receive<CreatePostRequest>()
        call.respond(service.createPost(call.authUser(), req))
    }
    get("/feed/posts") {
        val before = call.request.queryParameters["before"]
        val limit = call.request.queryParameters["limit"]?.let {
            it.toIntOrNull() ?: throw badRequest("VALIDATION_ERROR", "limit must be an integer")
        }
        call.respond(service.list(call.authUser(), before, limit))
    }
    delete("/feed/posts/{id}") {
        service.deletePost(call.authUser(), call.parameters["id"] ?: throw notFound())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/feed/posts/{id}/like") {
        call.respond(service.like(call.authUser(), call.parameters["id"] ?: throw notFound()))
    }
    delete("/feed/posts/{id}/like") {
        call.respond(service.unlike(call.authUser(), call.parameters["id"] ?: throw notFound()))
    }
    post("/feed/posts/{id}/comments") {
        val req = call.receive<AddCommentRequest>()
        call.respond(service.addComment(call.authUser(), call.parameters["id"] ?: throw notFound(), req))
    }
    delete("/feed/comments/{id}") {
        service.deleteComment(call.authUser(), call.parameters["id"] ?: throw notFound())
        call.respond(HttpStatusCode.NoContent)
    }
}

@kotlinx.serialization.Serializable
private data class AvatarRequest(val storageKey: String)

/** Admin: orphan sweep — deletes uploads unreferenced for 24 h (contract "Media storage"). */
fun Route.adminMediaRoutes(db: com.qimingnan.couple.backend.db.Db, audit: com.qimingnan.couple.backend.audit.AuditService, service: MediaService) {
    post("/admin/media/sweep") {
        val admin = call.requireAdmin(db, audit)
        val deleted = service.orphanSweep()
        audit.log(admin, com.qimingnan.couple.backend.audit.AuditActions.ADMIN_MEDIA_SWEEP, "media", mapOf("deleted" to deleted.size))
        call.respond(mapOf("deleted" to deleted))
    }
}
