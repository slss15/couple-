package com.qimingnan.couple.backend.chat

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.conflict
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.ChatMessages
import com.qimingnan.couple.backend.db.ChatReads
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.Users
import com.qimingnan.couple.backend.mqtt.ServerPush
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

@Serializable
data class ChatMessageView(
    val id: String,
    val coupleId: String,
    val senderId: String,
    val type: String,
    val text: String,
    val replyTo: String?,
    val clientTs: Long,
    val createdAt: Long,
    val recalledAt: Long?,
    /** image / sticker: the media object (GET /media/{mediaId}); null for text and after recall. */
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ChatPageView(val messages: List<ChatMessageView>, val hasMore: Boolean)

@Serializable
data class SendMessageRequest(
    val id: String,
    val text: String = "",
    val replyTo: String? = null,
    val clientTs: Long = 0,
    /** "text" (default) | "image" | "sticker". */
    val type: String = "text",
    /** image: a storageKey from POST /media. */
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** sticker: a URL from GET /chat/stickers/search; the server downloads and stores it. */
    val stickerUrl: String? = null,
)

/** Media attached to a message at insert time. */
data class ChatMedia(val key: String, val width: Int?, val height: Int?)

@Serializable
data class ChatReadRequest(val upToId: String? = null)

@Serializable
data class ChatReadStateView(val myReadUpTo: String? = null, val partnerReadUpTo: String? = null)

/** Row helpers. Must be called inside a transaction. */
object ChatRepo {
    fun message(id: String): ResultRow? = ChatMessages.selectAll().where { ChatMessages.id eq id }.firstOrNull()

    fun read(coupleId: String, userId: String): ResultRow? =
        ChatReads.selectAll().where { (ChatReads.coupleId eq coupleId) and (ChatReads.userId eq userId) }.firstOrNull()

    fun insert(
        id: String, coupleId: String, senderId: String, text: String, replyTo: String?, clientTs: Long, now: Long,
        type: String = "text", media: ChatMedia? = null,
    ) {
        ChatMessages.insert {
            it[ChatMessages.id] = id
            it[ChatMessages.coupleId] = coupleId
            it[ChatMessages.senderId] = senderId
            it[msgType] = type
            it[mediaKey] = media?.key
            it[mediaWidth] = media?.width
            it[mediaHeight] = media?.height
            it[ChatMessages.text] = text
            it[ChatMessages.replyTo] = replyTo
            it[ChatMessages.clientTs] = clientTs
            it[ChatMessages.createdAt] = now
            it[recalledAt] = null
        }
    }

    fun recall(id: String, now: Long) {
        ChatMessages.update({ ChatMessages.id eq id }) {
            it[text] = ""
            it[recalledAt] = now
            it[mediaKey] = null
            it[mediaWidth] = null
            it[mediaHeight] = null
        }
    }

    fun upsertRead(coupleId: String, userId: String, upToId: String, now: Long) {
        if (read(coupleId, userId) == null) {
            ChatReads.insert {
                it[ChatReads.coupleId] = coupleId
                it[ChatReads.userId] = userId
                it[ChatReads.upToId] = upToId
                it[updatedAt] = now
            }
        } else {
            ChatReads.update({ (ChatReads.coupleId eq coupleId) and (ChatReads.userId eq userId) }) {
                it[ChatReads.upToId] = upToId
                it[updatedAt] = now
            }
        }
    }

    fun toView(row: ResultRow) = ChatMessageView(
        id = row[ChatMessages.id],
        coupleId = row[ChatMessages.coupleId],
        senderId = row[ChatMessages.senderId],
        type = row[ChatMessages.msgType],
        text = row[ChatMessages.text],
        replyTo = row[ChatMessages.replyTo],
        clientTs = row[ChatMessages.clientTs],
        createdAt = row[ChatMessages.createdAt],
        recalledAt = row[ChatMessages.recalledAt],
        mediaId = row[ChatMessages.mediaKey],
        width = row[ChatMessages.mediaWidth],
        height = row[ChatMessages.mediaHeight],
    )

    /** Conversation order: server createdAt, ties broken by id. */
    fun newer(a: ResultRow, b: ResultRow): Boolean {
        val ac = a[ChatMessages.createdAt]
        val bc = b[ChatMessages.createdAt]
        return ac > bc || (ac == bc && a[ChatMessages.id] > b[ChatMessages.id])
    }
}

/**
 * P1a chat (contract "Chat"): text messages, replies, recall, read cursors and `couple/{cid}/chat` push.
 * MQTT is a hint; REST catch-up (`after`) is the source of truth.
 */
class ChatService(
    private val db: Db,
    private val config: AppConfig,
    private val push: ServerPush,
    private val media: com.qimingnan.couple.backend.media.MediaService? = null,
    val stickers: StickerService? = null,
) {

    suspend fun send(caller: AuthUser, req: SendMessageRequest): ChatMessageView {
        if (!ID_RE.matches(req.id)) throw badRequest("VALIDATION_ERROR", "id must be a ULID")
        val (coupleId, _) = db.tx { requirePair(caller) }

        // Idempotent replay before content validation: the stored message wins, even if the retry's body changed.
        val existing = db.tx { ChatRepo.message(req.id) }
        if (existing != null) return replay(existing, coupleId, caller)

        val type = req.type.trim().ifEmpty { "text" }
        val text = if (type == "text") req.text.trim() else ""
        // Sticker: download + ingest before the insert transaction (network I/O outside the tx). If the insert then
        // loses a race, the unreferenced media is removed by the orphan sweep.
        val sticker: ChatMedia? = when (type) {
            "text" -> {
                if (text.isEmpty() || text.length > 4000) throw badRequest("VALIDATION_ERROR", "text must be 1..4000 chars")
                null
            }
            "image" -> {
                if (req.mediaId.isNullOrBlank()) throw badRequest("VALIDATION_ERROR", "image messages need mediaId")
                null
            }
            "sticker" -> {
                val url = req.stickerUrl?.trim().orEmpty()
                if (url.isEmpty() || url.length > 2048) throw badRequest("VALIDATION_ERROR", "sticker messages need stickerUrl")
                val svc = stickers ?: throw badRequest("VALIDATION_ERROR", "stickers are not available")
                val ms = media ?: throw badRequest("VALIDATION_ERROR", "stickers are not available")
                val fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { svc.fetch(url) }
                val v = ms.ingestSticker(coupleId, fetched.mimeType, fetched.bytes)
                ChatMedia(v.storageKey, v.width, v.height)
            }
            else -> throw badRequest("VALIDATION_ERROR", "type must be text, image or sticker")
        }
        val now = config.clock()
        val view = try {
            db.tx {
                val (cid, _) = requirePair(caller)
                req.replyTo?.let { replyId ->
                    val reply = ChatRepo.message(replyId)
                        ?: throw badRequest("VALIDATION_ERROR", "replyTo must be a message of your couple")
                    if (reply[ChatMessages.coupleId] != cid) {
                        throw badRequest("VALIDATION_ERROR", "replyTo must be a message of your couple")
                    }
                }
                val attached = when (type) {
                    "image" -> {
                        val m = com.qimingnan.couple.backend.media.MediaRepo.byKey(req.mediaId!!)
                            ?: throw badRequest("VALIDATION_ERROR", "unknown media ${req.mediaId}")
                        if (m.coupleId != cid || m.purpose != "post") throw badRequest("VALIDATION_ERROR", "media belongs to another couple")
                        if (m.usedByPost != null) throw conflict("MEDIA_ALREADY_USED", "media already used")
                        ChatMedia(m.storageKey, m.width, m.height) // real dimensions win over the client's
                    }
                    "sticker" -> sticker
                    else -> null
                }
                ChatRepo.insert(req.id, cid, caller.userId, text, req.replyTo, req.clientTs, now, type, attached)
                attached?.let { com.qimingnan.couple.backend.media.MediaRepo.markUsed(it.key, req.id) }
                ChatRepo.toView(ChatRepo.message(req.id)!!)
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // Concurrent retry of the same id: the other request won the primary key; return its row.
            val winner = db.tx { ChatRepo.message(req.id) } ?: throw e
            return replay(winner, coupleId, caller)
        }
        push.publish(coupleId, "chat", messageEvent(view))
        return view
    }

    private fun replay(existing: ResultRow, coupleId: String, caller: AuthUser): ChatMessageView {
        if (existing[ChatMessages.coupleId] != coupleId || existing[ChatMessages.senderId] != caller.userId) {
            throw conflict("ID_CONFLICT", "id already used by another message")
        }
        return ChatRepo.toView(existing)
    }

    suspend fun list(caller: AuthUser, after: String?, before: String?, limit: Int?): ChatPageView {
        if (after != null && before != null) throw badRequest("VALIDATION_ERROR", "after and before are mutually exclusive")
        val n = (limit ?: 50).coerceIn(1, 200)
        val coupleId = db.tx { requirePair(caller).first }
        return db.tx {
            when {
                after != null -> {
                    val anchor = requireMessageOf(coupleId, after)
                    val ts = anchor[ChatMessages.createdAt]
                    val id = anchor[ChatMessages.id]
                    val rows = ChatMessages.selectAll().where {
                        (ChatMessages.coupleId eq coupleId) and
                            ((ChatMessages.createdAt greater ts) or ((ChatMessages.createdAt eq ts) and (ChatMessages.id greater id)))
                    }.orderBy(ChatMessages.createdAt to SortOrder.ASC, ChatMessages.id to SortOrder.ASC)
                        .limit(n + 1).toList()
                    ChatPageView(rows.take(n).map(ChatRepo::toView), hasMore = rows.size > n)
                }
                before != null -> {
                    val anchor = requireMessageOf(coupleId, before)
                    val ts = anchor[ChatMessages.createdAt]
                    val id = anchor[ChatMessages.id]
                    val rows = ChatMessages.selectAll().where {
                        (ChatMessages.coupleId eq coupleId) and
                            ((ChatMessages.createdAt less ts) or ((ChatMessages.createdAt eq ts) and (ChatMessages.id less id)))
                    }.orderBy(ChatMessages.createdAt to SortOrder.DESC, ChatMessages.id to SortOrder.DESC)
                        .limit(n + 1).toList()
                    ChatPageView(rows.take(n).asReversed().map(ChatRepo::toView), hasMore = rows.size > n)
                }
                else -> {
                    val rows = ChatMessages.selectAll().where { ChatMessages.coupleId eq coupleId }
                        .orderBy(ChatMessages.createdAt to SortOrder.DESC, ChatMessages.id to SortOrder.DESC)
                        .limit(n + 1).toList()
                    ChatPageView(rows.take(n).asReversed().map(ChatRepo::toView), hasMore = rows.size > n)
                }
            }
        }
    }

    suspend fun recall(caller: AuthUser, messageId: String): ChatMessageView {
        val coupleId = db.tx { requirePair(caller).first }
        val now = config.clock()
        var recalledMedia: String? = null
        val (view, published) = db.tx {
            val row = requireMessageOf(coupleId, messageId)
            if (row[ChatMessages.senderId] != caller.userId) {
                throw forbidden("RECALL_FORBIDDEN", "Only the sender can recall a message")
            }
            val recalledAt = row[ChatMessages.recalledAt]
            if (recalledAt != null) return@tx ChatRepo.toView(row) to false // idempotent
            if (now - row[ChatMessages.createdAt] > RECALL_WINDOW_MS) {
                throw conflict("RECALL_EXPIRED", "Messages can only be recalled within 120 seconds")
            }
            recalledMedia = row[ChatMessages.mediaKey]
            ChatRepo.recall(messageId, now)
            ChatRepo.toView(ChatRepo.message(messageId)!!) to true
        }
        recalledMedia?.let { k -> runCatching { media?.deleteObject(k) } }
        if (published) {
            push.publish(coupleId, "chat", """{"kind":"recall","id":"$messageId","recalledAt":$now}""")
        }
        return view
    }

    suspend fun markRead(caller: AuthUser, req: ChatReadRequest) {
        val upToId = req.upToId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw badRequest("VALIDATION_ERROR", "upToId required")
        val coupleId = db.tx { requirePair(caller).first }
        val now = config.clock()
        val advanced = db.tx {
            val incoming = requireMessageOf(coupleId, upToId)
            val existing = ChatRepo.read(coupleId, caller.userId)
            if (existing != null) {
                val old = ChatRepo.message(existing[ChatReads.upToId])
                // Monotonic: an older cursor is ignored.
                if (old != null && !ChatRepo.newer(incoming, old)) return@tx null
            }
            ChatRepo.upsertRead(coupleId, caller.userId, upToId, now)
            upToId
        }
        if (advanced != null) {
            push.publish(coupleId, "chat", """{"kind":"read","userId":"${caller.userId}","upToId":"$advanced","ts":$now}""")
        }
    }

    suspend fun state(caller: AuthUser): ChatReadStateView {
        val (coupleId, partnerId) = db.tx { requirePair(caller) }
        return db.tx {
            ChatReadStateView(
                myReadUpTo = ChatRepo.read(coupleId, caller.userId)?.get(ChatReads.upToId),
                partnerReadUpTo = ChatRepo.read(coupleId, partnerId)?.get(ChatReads.upToId),
            )
        }
    }

    /** 403 NOT_PAIRED unless the caller is paired (sticker search is a paired-only feature). */
    suspend fun requirePaired(caller: AuthUser) {
        db.tx { requirePair(caller) }
    }

    /** Inside a transaction. Returns (coupleId, partnerUserId); 403 NOT_PAIRED for solo users. */
    private fun requirePair(caller: AuthUser): Pair<String, String> {
        val me = Users.selectAll().where { Users.id eq caller.userId }.firstOrNull()
            ?: throw notFound("NOT_PAIRED", "user gone")
        val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
        val couple = Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.firstOrNull()
            ?: throw forbidden("NOT_PAIRED", "You are not paired")
        val partnerId = if (couple[Couples.userA] == caller.userId) couple[Couples.userB] else couple[Couples.userA]
        return cid to partnerId
    }

    private fun requireMessageOf(coupleId: String, messageId: String): ResultRow {
        val row = ChatRepo.message(messageId) ?: throw notFound("MESSAGE_NOT_FOUND", "no message $messageId")
        if (row[ChatMessages.coupleId] != coupleId) throw notFound("MESSAGE_NOT_FOUND", "no message $messageId")
        return row
    }

    companion object {
        /** Crockford-base32 ULID. */
        val ID_RE = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

        const val RECALL_WINDOW_MS = 120_000L

        fun messageEvent(message: ChatMessageView): String =
            """{"kind":"message","message":${AppJson.encodeToString(ChatMessageView.serializer(), message)}}"""
    }
}
