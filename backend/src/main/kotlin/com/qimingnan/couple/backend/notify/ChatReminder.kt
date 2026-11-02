package com.qimingnan.couple.backend.notify

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.chat.ChatMessageView
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.Users
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Who has an MQTT connection right now, and since when everybody else has been away. Fed by the embedded broker's
 * connect / disconnect / connection-lost interceptor; a user may hold several client ids (one per device).
 * A user never seen since this process started counts as offline since [startedAt].
 */
class PresenceTracker(private val clock: () -> Long) {
    val startedAt: Long = clock()
    private val clients = ConcurrentHashMap<String, MutableSet<String>>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun connected(userId: String, clientId: String) {
        clients.compute(userId) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(clientId) } }
        lastSeen[userId] = clock()
    }

    fun disconnected(userId: String, clientId: String) {
        clients.computeIfPresent(userId) { _, set -> set.remove(clientId); set.ifEmpty { null } }
        lastSeen[userId] = clock()
    }

    fun isOnline(userId: String): Boolean = clients[userId]?.isNotEmpty() == true

    /** null while online; otherwise the epoch ms of the last disconnect (or process start when never seen). */
    fun offlineSince(userId: String): Long? = if (isOnline(userId)) null else lastSeen[userId] ?: startedAt
}

/** Admin config `notify.wxpusher` as the chat reminder reads it: the appToken plus `chatOfflineMinutes` (default 5). */
data class ChatReminderSettings(val appToken: String, val offlineMinutes: Int) {
    companion object {
        const val DEFAULT_OFFLINE_MINUTES = 5

        fun fromConfig(notify: JsonObject?): ChatReminderSettings {
            val wx = notify?.get("wxpusher") as? JsonObject
            val token = (wx?.get("appToken") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim().orEmpty()
            val minutes = (wx?.get("chatOfflineMinutes") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
                ?.coerceIn(1, 1440) ?: DEFAULT_OFFLINE_MINUTES
            return ChatReminderSettings(token, minutes)
        }
    }
}

@Serializable
data class NotifySettingsView(val wxpusherUid: String? = null, val preview: Boolean = false)

@Serializable
data class NotifySettingsRequest(val wxpusherUid: String? = null, val preview: Boolean = false)

/**
 * WxPusher 消息提醒（contract "Chat → WxPusher reminders"）：a chat message to a recipient whose app has had no MQTT
 * connection for `chatOfflineMinutes` is forwarded to the recipient's own WxPusher UID, at most once per
 * [MIN_INTERVAL_MS] per recipient. Content is "TA 发来一条消息" unless the recipient opted into previews.
 * Best effort: runs off the request path in [scope]; never throws into the chat API.
 */
class ChatReminderService(
    private val db: Db,
    private val config: AppConfig,
    /** null = no presence signal (external EMQX): reminders are disabled rather than sent to users who may be online. */
    private val presence: PresenceTracker?,
    private val settingsProvider: suspend () -> ChatReminderSettings,
    private val scope: CoroutineScope,
    private val send: suspend (String, String) -> Boolean = config.wxPusherSend ?: WxPusherService::defaultSend,
) {
    private val lastSent = ConcurrentHashMap<String, Long>()

    suspend fun settings(caller: AuthUser): NotifySettingsView = db.tx {
        val row = Users.selectAll().where { Users.id eq caller.userId }.first()
        NotifySettingsView(row[Users.wxpusherUid], row[Users.notifyPreview])
    }

    suspend fun update(caller: AuthUser, req: NotifySettingsRequest): NotifySettingsView {
        val uid = req.wxpusherUid?.trim()?.ifEmpty { null }
        if (uid != null && !UID_RE.matches(uid)) throw badRequest("VALIDATION_ERROR", "wxpusherUid must look like UID_xxx")
        db.tx {
            Users.update({ Users.id eq caller.userId }) {
                it[wxpusherUid] = uid
                it[notifyPreview] = req.preview
            }
        }
        return NotifySettingsView(uid, req.preview)
    }

    /** Called after a chat message is committed and pushed (POST /chat/messages and scheduled sends). */
    fun onMessage(view: ChatMessageView) {
        if (presence == null) return
        scope.launch { runCatching { remind(view) }.onFailure { log.warn("chat reminder failed: {}", it.toString()) } }
    }

    /** The decision + send, synchronously; returns true when a WxPusher message went out. */
    suspend fun remind(view: ChatMessageView): Boolean {
        val tracker = presence ?: return false
        val (recipient, uid, preview) = db.tx {
            val couple = Couples.selectAll().where { Couples.id eq view.coupleId }.firstOrNull() ?: return@tx null
            if (couple[Couples.deletedAt] != null) return@tx null
            val other = if (couple[Couples.userA] == view.senderId) couple[Couples.userB] else couple[Couples.userA]
            val u = Users.selectAll().where { Users.id eq other }.firstOrNull() ?: return@tx null
            Triple(other, u[Users.wxpusherUid], u[Users.notifyPreview])
        } ?: return false
        if (uid.isNullOrBlank()) return false
        val s = settingsProvider()
        if (s.appToken.isBlank()) return false
        val now = config.clock()
        val since = tracker.offlineSince(recipient) ?: return false
        if (now - since < s.offlineMinutes * 60_000L) return false
        // Rate limit per recipient; the mark is set before sending (at most once per window, like operator alerts).
        val prev = lastSent[recipient]
        if (prev != null && now - prev < MIN_INTERVAL_MS) return false
        lastSent[recipient] = now
        val content = if (preview) "TA：" + previewText(view) else DEFAULT_CONTENT
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("appToken", s.appToken)
                put("content", content.take(2000))
                put("summary", content.take(100))
                put("contentType", 1)
                put("uids", JsonArray(listOf(JsonPrimitive(uid))))
            },
        )
        val ok = runCatching { send(WxPusherService.WXPUSHER_API, body) }.getOrDefault(false)
        if (!ok) log.warn("chat reminder to {} failed (next attempt after the {} min window)", recipient, MIN_INTERVAL_MS / 60_000)
        return ok
    }

    companion object {
        const val MIN_INTERVAL_MS = 10 * 60_000L
        const val DEFAULT_CONTENT = "TA 发来一条消息"
        private val UID_RE = Regex("^UID_[A-Za-z0-9_-]{1,60}$")
        private val log = LoggerFactory.getLogger(ChatReminderService::class.java)

        fun previewText(view: ChatMessageView): String = when (view.type) {
            "image" -> "[图片]"
            "sticker" -> "[表情]"
            else -> view.text.replace(Regex("\\s+"), " ").let { if (it.length > 100) it.take(100) + "…" else it }
        }
    }
}

/** GET / PUT /me/notify. Must be mounted inside authenticate(JWT_AUTH). */
fun Route.notifyRoutes(service: ChatReminderService) {
    get("/me/notify") { call.respond(service.settings(call.authUser())) }
    put("/me/notify") { call.respond(service.update(call.authUser(), call.receive<NotifySettingsRequest>())) }
}
