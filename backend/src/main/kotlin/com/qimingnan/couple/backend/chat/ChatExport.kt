package com.qimingnan.couple.backend.chat

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.ErrorBody
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.ChatMessages
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.Users
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** One exported message (contract "Chat → Export"): the Message fields plus resolvable media URLs. */
@Serializable
data class ChatExportItem(
    val id: String,
    val senderId: String,
    /** true when the exporting user sent it ("我"), false for the partner ("TA"). */
    val mine: Boolean,
    val type: String,
    val text: String,
    val replyTo: String?,
    val clientTs: Long,
    val createdAt: Long,
    val recalledAt: Long?,
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Relative to the server base URL (Bearer, couple ACL), like avatarUrl. */
    val mediaUrl: String? = null,
    val thumbUrl: String? = null,
)

/**
 * GET /chat/export?format=json|txt[&tz=Asia/Shanghai]: streams all of the couple's messages, oldest first, in
 * keyset pages so a long history never sits in memory. Either member may export; 1 export per user per minute.
 */
class ChatExportService(private val db: Db, private val config: AppConfig) {
    private val lastExport = ConcurrentHashMap<String, Long>()

    data class Scope(val coupleId: String, val userId: String)

    /** Validates pairing and takes the caller's rate-limit slot. Returns the seconds to wait when limited. */
    suspend fun begin(caller: AuthUser): Pair<Scope?, Long> {
        val cid = db.tx {
            val me = Users.selectAll().where { Users.id eq caller.userId }.firstOrNull()
                ?: throw notFound("NOT_PAIRED", "user gone")
            val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
            Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.firstOrNull()
                ?: throw forbidden("NOT_PAIRED", "You are not paired")
            cid
        }
        val now = config.clock()
        var wait = 0L
        lastExport.compute(caller.userId) { _, last ->
            if (last != null && now - last < RATE_WINDOW_MS) { wait = (RATE_WINDOW_MS - (now - last) + 999) / 1000; last } else now
        }
        return if (wait > 0) null to wait else Scope(cid, caller.userId) to 0L
    }

    /** Calls [sink] for every message of the couple in conversation order (createdAt, id). */
    suspend fun forEach(coupleId: String, sink: (ChatMessageView) -> Unit) {
        var cursor: Pair<Long, String>? = null
        while (true) {
            val c = cursor
            val rows = db.tx {
                ChatMessages.selectAll().where {
                    if (c == null) ChatMessages.coupleId eq coupleId
                    else (ChatMessages.coupleId eq coupleId) and
                        ((ChatMessages.createdAt greater c.first) or ((ChatMessages.createdAt eq c.first) and (ChatMessages.id greater c.second)))
                }.orderBy(ChatMessages.createdAt to SortOrder.ASC, ChatMessages.id to SortOrder.ASC)
                    .limit(PAGE).toList()
            }
            rows.forEach { sink(ChatRepo.toView(it)) }
            if (rows.size < PAGE) return
            val last = rows.last()
            cursor = last[ChatMessages.createdAt] to last[ChatMessages.id]
        }
    }

    companion object {
        const val RATE_WINDOW_MS = 60_000L
        private const val PAGE = 500
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun item(m: ChatMessageView, me: String) = ChatExportItem(
            id = m.id, senderId = m.senderId, mine = m.senderId == me, type = m.type, text = m.text,
            replyTo = m.replyTo, clientTs = m.clientTs, createdAt = m.createdAt, recalledAt = m.recalledAt,
            mediaId = m.mediaId, width = m.width, height = m.height,
            mediaUrl = m.mediaId?.let { "/api/v1/media/$it" },
            thumbUrl = m.mediaId?.takeIf { m.type == "image" }?.let { "/api/v1/media/$it/thumb" },
        )

        /**
         * "2026-10-01 12:00 我: text". Recalled → [已撤回], image → [图片], sticker → [表情]; continuation lines of a
         * multi-line text are indented with a tab so every line that starts with a timestamp is one message.
         */
        fun txtLine(m: ChatMessageView, me: String, zone: ZoneId): String {
            val who = if (m.senderId == me) "我" else "TA"
            val body = when {
                m.recalledAt != null -> "[已撤回]"
                m.type == "image" -> "[图片]"
                m.type == "sticker" -> "[表情]"
                else -> m.text.replace("\r\n", "\n").replace("\n", "\n\t")
            }
            return "${TS.format(Instant.ofEpochMilli(m.createdAt).atZone(zone))} $who: $body"
        }
    }
}

fun Route.chatExportRoutes(service: ChatExportService, config: AppConfig) {
    get("/chat/export") {
        val format = call.request.queryParameters["format"]?.lowercase() ?: "json"
        if (format != "json" && format != "txt") throw badRequest("VALIDATION_ERROR", "format must be json or txt")
        val zone = ScheduleMath.zone(call.request.queryParameters["tz"])
        val (scope, wait) = service.begin(call.authUser())
        if (scope == null) {
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            call.respond(HttpStatusCode.TooManyRequests, ErrorBody("RATE_LIMITED", "One export per minute; retry in ${wait}s"))
            return@get
        }
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(config.clock()).atZone(zone))
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "couple-chat-$day.$format").toString(),
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val type = if (format == "json") ContentType.Application.Json else ContentType.Text.Plain
        call.respondTextWriter(type.withCharset(Charsets.UTF_8)) {
            if (format == "json") writeJson(this, service, scope, config.clock()) else {
                service.forEach(scope.coupleId) { write(ChatExportService.txtLine(it, scope.userId, zone)); write("\n") }
            }
        }
    }
}

private suspend fun writeJson(w: Writer, service: ChatExportService, scope: ChatExportService.Scope, now: Long) {
    w.write("""{"format":"couple-chat-export","version":1,"coupleId":"${scope.coupleId}","userId":"${scope.userId}","exportedAt":$now,"messages":[""")
    var first = true
    service.forEach(scope.coupleId) {
        if (!first) w.write(",")
        first = false
        w.write("\n")
        w.write(AppJson.encodeToString(ChatExportItem.serializer(), ChatExportService.item(it, scope.userId)))
    }
    w.write("\n]}\n")
}
