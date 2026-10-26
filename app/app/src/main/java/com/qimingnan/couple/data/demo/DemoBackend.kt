package com.qimingnan.couple.data.demo

import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.bootstrap.ServerConfig
import com.qimingnan.couple.data.net.ChatMessage
import com.qimingnan.couple.data.net.ChatPage
import com.qimingnan.couple.data.net.ChatReadState
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.PairCode
import com.qimingnan.couple.data.net.TokenPair
import com.qimingnan.couple.data.net.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 离线调试账号（只在 debug 包可用）：一个进程内的假后端，不需要任何服务器。
 *
 * 所有发往 [HOST] 的请求都由 [DemoInterceptor] 在本机直接应答，因此 引导 → 登录 → 配对 → 主界面 → 动态 → 我的
 * 的整条 UI 流程都能跑通。MQTT 不连接，伴侣在线状态由 AppCoordinator 直接注入；动态事件走 [feedEvents]
 * 进程内流（与 MQTT 载荷同构），“TA（演示）”会在你发帖约 1.5 秒后自动点赞/评论。
 */
object DemoBackend {
    const val HOST = "demo.couple.local"
    const val BASE_URL = "https://$HOST"
    const val MQTT_URL = "wss://$HOST/mqtt"
    const val USERNAME = "demo"
    const val PASSWORD = "demo123"

    /** 调试入口只在 debug 构建出现；release 包里这个假后端永远不会被走到。 */
    val available: Boolean get() = BuildConfig.DEBUG

    fun isDemo(baseUrl: String?): Boolean = baseUrl != null && runCatching { java.net.URI(baseUrl).host == HOST }.getOrDefault(false)
    fun isDemoUrl(url: String?): Boolean = url != null && runCatching { java.net.URI(url).host == HOST }.getOrDefault(false)

    fun config(now: Long): ServerConfig = ServerConfig(
        baseUrl = BASE_URL, mqttUrl = MQTT_URL, configVersion = 1, issuedAt = now, signed = false, appliedAt = now,
    )

    val partner = User(id = "demo-partner", username = "ta", displayName = "TA（演示）", createdAt = 1_700_000_000_000)

    /** 假后端的内存状态（进程重启后回到“已配对 + 两条演示动态”）。 */
    @Volatile var paired: Boolean = true
    @Volatile var displayName: String = "我（调试）"

    fun self(): User = User(
        id = "demo-self", username = USERNAME, displayName = displayName,
        coupleId = if (paired) COUPLE_ID else null, createdAt = 1_700_000_000_000,
    )

    const val COUPLE_ID = "demo-couple"
    fun couple(): Couple = Couple(id = COUPLE_ID, members = listOf(self(), partner), pairedAt = 1_735_689_600_000)

    fun tokens(now: Long): TokenPair {
        val at = "demo-access-$now"
        val rt = "demo-refresh-$now"
        return TokenPair(accessToken = at, refreshToken = rt, accessExpiresAt = now + 24 * 3600_000L, user = self())
    }

    // ---- feed 内存状态（UI 演示） ----

    private val demoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 与 MQTT couple/{cid}/feed 载荷同构的事件流。 */
    val feedEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    data class DemoMedia(val key: String, val bytes: ByteArray, val mime: String, val w: Int, val h: Int)
    data class DemoPost(
        val id: String, val authorId: String, var text: String, val createdAt: Long,
        val media: MutableList<DemoMedia> = mutableListOf(), var likedBySelf: Boolean = false,
        var likedByPartner: Boolean = false,
        val comments: MutableList<Pair<String, String>> = mutableListOf(), // id to (authorId|text)
    )

    private fun DemoPost.likeCount() = (if (likedBySelf) 1 else 0) + (if (likedByPartner) 1 else 0)

    private val mediaStore = LinkedHashMap<String, DemoMedia>()
    private val posts = mutableListOf<DemoPost>()

    // ---- 聊天内存状态（UI 演示） ----

    private data class DemoChat(
        val id: String,
        val senderId: String,
        var text: String,
        val replyTo: String?,
        val clientTs: Long,
        val createdAt: Long,
        var recalledAt: Long?,
    )

    private val chatMessages = mutableListOf<DemoChat>()
    private var demoMyReadUpTo: String? = null
    private var demoPartnerReadUpTo: String? = null

    /** 与 MQTT couple/{cid}/chat 载荷同构的事件流。 */
    val chatEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** 演示模式的 couple/{cid}/typing：TA 自动回复前约 1 秒显示「正在输入」。 */
    val typingEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 自动回复前 TA「正在输入」的时长。 */
    const val DEMO_TYPING_MS = 1_000L

    private fun emitTyping(typing: Boolean) {
        val now = System.currentTimeMillis()
        demoScope.launch { typingEvents.emit("""{"userId":"${partner.id}","typing":$typing,"ts":$now}""") }
    }

    init {
        seed()
        seedChat()
    }

    private fun seedChat() {
        val at = 1_736_000_200_000L
        chatMessages += DemoChat(Ulid.next(at), partner.id, "在呢～今天过得怎么样？", null, at, at, null)
    }

    /** JVM 测试用：把聊天恢复到初始状态。 */
    fun resetChat() {
        chatMessages.clear()
        demoMyReadUpTo = null
        demoPartnerReadUpTo = null
        seedChat()
    }

    private fun seed() {
        posts += DemoPost(Ulid.next(1_736_000_000_000), partner.id, "欢迎使用 Couple 演示模式 🎉\n这里展示动态页：发帖、点赞、评论都可以离线体验。", 1_736_000_000_000)
        posts += DemoPost(Ulid.next(1_736_000_100_000), partner.id, "左下角「+」可以发布你自己的动态，试试看～", 1_736_000_100_000, likedBySelf = false)
    }

    fun resetFeed() {
        mediaStore.clear()
        posts.clear()
        seed()
    }

    fun postJson(p: DemoPost): String {
        fun commentJson(id: String, authorId: String, text: String, ts: Long) =
            """{"id":"$id","postId":"${p.id}","authorId":"$authorId","text":${JsonPrimitive(text)},"createdAt":$ts}"""
        val media = p.media.joinToString(",") {
            """{"storageKey":"${it.key}","thumbKey":null,"mimeType":"${it.mime}","width":${it.w},"height":${it.h},"size":${it.bytes.size}}"""
        }
        val commentList = p.comments.map { (id, both) ->
            val (author, text) = both.split("|", limit = 2)
            commentJson(id, author, text, p.createdAt + 60_000)
        }
        val comments = commentList.joinToString(",")
        return """{"id":"${p.id}","coupleId":"$COUPLE_ID","authorId":"${p.authorId}","text":${JsonPrimitive(p.text)},""" +
            """"media":[$media],"createdAt":${p.createdAt},"likes":{"count":${p.likeCount()},"likedByMe":${p.likedBySelf}},""" +
            """"comments":[$comments],"commentCount":${commentList.size}}"""
    }

    private fun emit(payload: String) {
        demoScope.launch { feedEvents.emit(payload) }
    }

    private fun autoReact(postId: String) {
        // “TA（演示）”的自动反应：~1.5s 点赞，~3s 评论（只发事件；likedBySelf 是自己的视角，不受影响）。
        demoScope.launch {
            delay(1_500)
            val p = posts.firstOrNull { it.id == postId } ?: return@launch
            p.likedByPartner = true
            emit("""{"kind":"like","postId":"$postId","userId":"${partner.id}","count":${p.likeCount()}}""")
            delay(1_500)
            val c = "要一直在一起呀 ❤️"
            val cid = Ulid.next()
            posts.firstOrNull { it.id == postId }?.comments?.add(cid to "${partner.id}|$c")
            emit("""{"kind":"comment","postId":"$postId","comment":{"id":"$cid","postId":"$postId","authorId":"${partner.id}","text":${JsonPrimitive(c)},"createdAt":${System.currentTimeMillis()}}}""")
        }
    }

    /** 纯函数：根据方法 + 路径给出 (HTTP 状态码, JSON body)。方便 JVM 单测。 */
    fun respond(
        method: String,
        path: String,
        now: Long,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
    ): Pair<Int, String> {
        // body 由调用方显式传入：拦截器以前把它写进全局 demoBody 再读，并发 POST（打字状态、已读、健康上报、
        // 聊天发送）会互相覆盖，动态 POST 偶发读到别人的 body → 400「id required」→ 帖子「发送失败」。
        if (body != null) requestBody.set(body)
        try { return synchronized(stateLock) { respondInner(method, path, now, query) } } finally { if (body != null) requestBody.remove() }
    }

    private val requestBody = ThreadLocal<String>()
    private val stateLock = Any()

    /** 当前请求的 body：优先本线程显式传入的，其次（单测用的）[demoBody]。 */
    private val currentBody: String get() = requestBody.get() ?: demoBody

    private fun respondInner(
        method: String,
        path: String,
        now: Long,
        query: Map<String, String?>,
    ): Pair<Int, String> {
        val p = path.removePrefix("/api/v1/").removePrefix("/api/v1").trimEnd('/')
        fun <T> ok(s: KSerializer<T>, v: T) = 200 to CoupleJson.encodeToString(s, v)
        return when ("$method $p") {
            "GET health" -> 200 to buildJsonObject { put("status", "ok"); put("version", "demo"); put("protocolVersion", 1) }.toString()
            "GET bootstrap/pubkey" -> 404 to err("NOT_FOUND", "演示模式没有签名公钥")
            "POST auth/login", "POST auth/register", "POST auth/refresh" -> ok(TokenPair.serializer(), tokens(now))
            "POST auth/logout" -> 204 to ""
            "GET couple" -> if (paired) ok(Couple.serializer(), couple()) else 404 to err("NOT_PAIRED", "还没有配对")
            "POST pair/code" -> ok(PairCode.serializer(), PairCode("520131", now + 10 * 60_000L))
            "POST pair/join" -> { paired = true; ok(Couple.serializer(), couple()) }
            "POST couple/unpair/request" -> 200 to """{"requestId":"demo-unpair"}"""
            "POST couple/unpair/confirm" -> { paired = false; 204 to "" }
            "GET feed/posts" -> {
                val list = posts.sortedByDescending { it.createdAt }.joinToString(",") { postJson(it) }
                200 to """{"posts":[$list],"hasMore":false}"""
            }
            "GET app/update" -> 200 to NO_UPDATE
            "POST app/health-report" -> 204 to ""
            "GET ta/summary" -> {
                if (!paired) return 403 to err("NOT_PAIRED", "You are not paired")
                200 to taSummaryJson(now)
            }
            "GET chat/messages" -> chatPage(query)
            "GET chat/state" -> ok(ChatReadState.serializer(), ChatReadState(demoMyReadUpTo, demoPartnerReadUpTo))
            "POST chat/messages" -> sendChat(now)
            "POST feed/posts" -> {
                val body = json.parseToJsonElement(currentBody).jsonObject
                val id = body["id"]?.jsonPrimitive?.content ?: return 400 to err("VALIDATION_ERROR", "id required")
                posts.firstOrNull { it.id == id }?.let { return 200 to postJson(it) } // 幂等
                val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val keys = (body["mediaKeys"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                if (text.isBlank() && keys.isEmpty()) return 400 to err("VALIDATION_ERROR", "text or media required")
                val media = synchronized(mediaStore) { keys.map { mediaStore[it] } }
                if (media.any { it == null }) return 400 to err("VALIDATION_ERROR", "unknown media key")
                val post = DemoPost(id, self().id, text, now, media.filterNotNull().toMutableList())
                posts += post
                demoScope.launch { feedEvents.emit("""{"kind":"post","post":${postJson(post)}}""") }
                autoReact(id)
                200 to postJson(post)
            }
            else -> feedMutableEndpoints(method, p, now)
        }
    }

    private fun DemoChat.toModel() = ChatMessage(
        id = id, coupleId = COUPLE_ID, senderId = senderId, type = "text", text = text,
        replyTo = replyTo, clientTs = clientTs, createdAt = createdAt, recalledAt = recalledAt,
    )

    private fun emitChat(payload: String) {
        demoScope.launch { chatEvents.emit(payload) }
    }

    private fun emitChatMessage(m: DemoChat) {
        emitChat("""{"kind":"message","message":${CoupleJson.encodeToString(ChatMessage.serializer(), m.toModel())}}""")
    }

    private fun chatPage(query: Map<String, String?>): Pair<Int, String> {
        if (!paired) return 403 to err("NOT_PAIRED", "You are not paired")
        val after = query["after"]?.trim()?.takeIf { it.isNotEmpty() }
        val before = query["before"]?.trim()?.takeIf { it.isNotEmpty() }
        if (after != null && before != null) return 400 to err("VALIDATION_ERROR", "after and before are mutually exclusive")
        val limit = (query["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val ordered = chatMessages.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val page: List<DemoChat>
        val hasMore: Boolean
        when {
            after != null -> {
                val index = ordered.indexOfFirst { it.id == after }
                if (index < 0) return 404 to err("MESSAGE_NOT_FOUND", "no message $after")
                val rest = ordered.drop(index + 1)
                page = rest.take(limit)
                hasMore = rest.size > limit
            }
            before != null -> {
                val index = ordered.indexOfFirst { it.id == before }
                if (index < 0) return 404 to err("MESSAGE_NOT_FOUND", "no message $before")
                val older = ordered.take(index)
                page = older.takeLast(limit)
                hasMore = older.size > limit
            }
            else -> {
                page = ordered.takeLast(limit)
                hasMore = ordered.size > limit
            }
        }
        return 200 to CoupleJson.encodeToString(ChatPage.serializer(), ChatPage(page.map { it.toModel() }, hasMore))
    }

    private fun sendChat(now: Long): Pair<Int, String> {
        if (!paired) return 403 to err("NOT_PAIRED", "You are not paired")
        val body = json.parseToJsonElement(currentBody).jsonObject
        val id = body["id"]?.jsonPrimitive?.content ?: return 400 to err("VALIDATION_ERROR", "id required")
        if (!Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(id)) return 400 to err("VALIDATION_ERROR", "id must be a ULID")
        chatMessages.firstOrNull { it.id == id }?.let { existing ->
            if (existing.senderId != self().id) return 409 to err("ID_CONFLICT", "id already used by another message")
            return 200 to CoupleJson.encodeToString(ChatMessage.serializer(), existing.toModel())
        }
        val text = body["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        if (text.isEmpty() || text.length > 4000) return 400 to err("VALIDATION_ERROR", "text must be 1..4000 chars")
        val replyTo = body["replyTo"]?.jsonPrimitive?.contentOrNull
        if (replyTo != null && chatMessages.none { it.id == replyTo }) {
            return 400 to err("VALIDATION_ERROR", "replyTo must be a message of your couple")
        }
        val clientTs = body["clientTs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now
        val msg = DemoChat(id, self().id, text, replyTo, clientTs, now, null)
        chatMessages += msg
        emitChatMessage(msg)
        scheduleDemoReply()
        return 200 to CoupleJson.encodeToString(ChatMessage.serializer(), msg.toModel())
    }

    private fun scheduleDemoReply() {
        val replies = listOf("我在呢，怎么啦？", "刚看到～想你了", "好呀，听你的", "哈哈哈哈，真的吗？")
        demoScope.launch {
            delay(500)
            emitTyping(true)
            delay(DEMO_TYPING_MS)
            emitTyping(false)
            val id = Ulid.next()
            val at = System.currentTimeMillis()
            val reply = DemoChat(id, partner.id, replies[(chatMessages.size - 1) % replies.size], null, at, at, null)
            chatMessages += reply
            emitChatMessage(reply)
            // 演示 TA 已读了我方最后一条消息。
            chatMessages.lastOrNull { it.senderId == self().id }?.let { mine ->
                demoPartnerReadUpTo = mine.id
                emitChat("""{"kind":"read","userId":"${partner.id}","upToId":"${mine.id}","ts":$at}""")
            }
        }
    }

    /** 需要可变状态的 feed 子路径（like/comment/delete）与 chat 子路径（recall/read）。 */
    private fun feedMutableEndpoints(method: String, p: String, now: Long): Pair<Int, String> {
        val recallMatch = Regex("^chat/messages/([^/]+)/recall$").find(p)
        if (recallMatch != null && method == "POST") {
            val msg = chatMessages.firstOrNull { it.id == recallMatch.groupValues[1] }
                ?: return 404 to err("MESSAGE_NOT_FOUND", "no message")
            if (msg.senderId != self().id) return 403 to err("RECALL_FORBIDDEN", "Only the sender can recall a message")
            if (msg.recalledAt != null) {
                return 200 to CoupleJson.encodeToString(ChatMessage.serializer(), msg.toModel())
            }
            if (now - msg.createdAt > 120_000) return 409 to err("RECALL_EXPIRED", "Messages can only be recalled within 120 seconds")
            msg.text = ""
            msg.recalledAt = now
            emitChat("""{"kind":"recall","id":"${msg.id}","recalledAt":$now}""")
            return 200 to CoupleJson.encodeToString(ChatMessage.serializer(), msg.toModel())
        }
        if (p == "chat/read" && method == "POST") {
            if (!paired) return 403 to err("NOT_PAIRED", "You are not paired")
            val body = json.parseToJsonElement(currentBody).jsonObject
            val upToId = body["upToId"]?.jsonPrimitive?.contentOrNull
                ?: return 400 to err("VALIDATION_ERROR", "upToId required")
            val ordered = chatMessages.sortedWith(compareBy({ it.createdAt }, { it.id }))
            val incoming = ordered.indexOfFirst { it.id == upToId }
            if (incoming < 0) return 404 to err("MESSAGE_NOT_FOUND", "no message $upToId")
            val current = demoMyReadUpTo?.let { id -> ordered.indexOfFirst { it.id == id } } ?: -1
            if (incoming > current) {
                demoMyReadUpTo = upToId
                emitChat("""{"kind":"read","userId":"${self().id}","upToId":"$upToId","ts":$now}""")
            }
            return 204 to ""
        }
        val likeMatch = Regex("^feed/posts/([^/]+)/like$").find(p)
        if (likeMatch != null) {
            val post = posts.firstOrNull { it.id == likeMatch.groupValues[1] } ?: return 404 to err("POST_NOT_FOUND", "no post")
            return if (method == "POST") {
                post.likedBySelf = true; 200 to """{"count":1}"""
            } else {
                post.likedBySelf = false; 200 to """{"count":0}"""
            }
        }
        val commentMatch = Regex("^feed/posts/([^/]+)/comments$").find(p)
        if (commentMatch != null && method == "POST") {
            val post = posts.firstOrNull { it.id == commentMatch.groupValues[1] } ?: return 404 to err("POST_NOT_FOUND", "no post")
            val body = json.parseToJsonElement(currentBody).jsonObject
            val id = body["id"]?.jsonPrimitive?.content ?: return 400 to err("VALIDATION_ERROR", "id required")
            post.comments.firstOrNull { it.first == id }?.let { (_, both) ->
                val (author, text) = both.split("|", limit = 2)
                return 200 to """{"id":"$id","postId":"${post.id}","authorId":"$author","text":${JsonPrimitive(text)},"createdAt":$now}"""
            }
            val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
            if (text.isBlank()) return 400 to err("VALIDATION_ERROR", "text required")
            post.comments += id to "${self().id}|$text"
            return 200 to """{"id":"$id","postId":"${post.id}","authorId":"${self().id}","text":${JsonPrimitive(text)},"createdAt":$now}"""
        }
        val deleteComment = Regex("^feed/comments/([^/]+)$").find(p)
        if (deleteComment != null && method == "DELETE") {
            val id = deleteComment.groupValues[1]
            var removed = false
            for (post in posts) removed = post.comments.removeAll { it.first == id } || removed
            return if (removed) 204 to "" else 404 to err("COMMENT_NOT_FOUND", "no comment")
        }
        val deletePost = Regex("^feed/posts/([^/]+)$").find(p)
        if (deletePost != null && method == "DELETE") {
            val id = deletePost.groupValues[1]
            val removed = posts.removeAll { it.id == id }
            return if (removed) 204 to "" else 404 to err("POST_NOT_FOUND", "no post")
        }
        if (p == "ta/me" && method == "PUT") {
            val body = json.parseToJsonElement(currentBody).jsonObject
            (body["bio"] as? JsonPrimitive)?.contentOrNull?.let { demoBio = it.take(200) }
            (body["birthday"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("^\\d{2}-\\d{2}$")) }?.let { demoBirthday = it }
            (body["birthYear"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { demoBirthYear = it }
            (body["showBirthday"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()?.let { demoShowBirthday = it }
            return 204 to ""
        }
        if (p == "ta/days" && method == "POST") {
            if (!paired) return 403 to err("NOT_PAIRED", "You are not paired")
            val body = json.parseToJsonElement(currentBody).jsonObject
            val name = body["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return 400 to err("VALIDATION_ERROR", "name required")
            if (name.isEmpty() || name.length > 50) return 400 to err("VALIDATION_ERROR", "name 1..50")
            val month = body["month"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return 400 to err("VALIDATION_ERROR", "month required")
            val day = body["day"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return 400 to err("VALIDATION_ERROR", "day required")
            if (month !in 1..12 || day !in 1..31) return 400 to err("VALIDATION_ERROR", "month 1..12, day 1..31")
            val id = Ulid.next(now)
            demoDays += Triple(id, name, "%02d-%02d".format(month, day))
            return 200 to """{"id":"$id","name":${JsonPrimitive(name)},"month":$month,"day":$day,"year":null,"createdBy":"${self().id}"}"""
        }
        val delDay = Regex("^ta/days/([^/]+)$").find(p)
        if (delDay != null && method == "DELETE") {
            demoDays.removeAll { it.first == delDay.groupValues[1] }
            return 204 to ""
        }
        return 404 to err("NOT_FOUND", "演示后端未实现：$method /$p")
    }

    // ---- TA 演示数据（me = 我的档案可编辑；partner = TA 固定档案） ----
    @Volatile private var demoBio: String = "喜欢在傍晚散步"
    @Volatile private var demoBirthday: String? = "06-20"
    @Volatile private var demoBirthYear: Int? = 2000
    @Volatile private var demoShowBirthday: Boolean = true
    private val demoDays = mutableListOf(Triple("01DEMODAY0000000000000000A", "第一次见面", "03-14"))

    private fun taSummaryJson(now: Long): String {
        val me = self()
        val days = demoDays.joinToString(",") { (id, name, md) ->
            val (m, d) = md.split('-')
            """{"id":"$id","name":${JsonPrimitive(name)},"month":${m.toInt()},"day":${d.toInt()},"year":null,"createdBy":"${partner.id}"}"""
        }
        return """{"me":{"displayName":${JsonPrimitive(me.displayName)},"bio":${JsonPrimitive(demoBio)},""" +
            """"birthday":${JsonPrimitive(demoBirthday)},"birthYear":${demoBirthYear ?: "null"},"showBirthday":$demoShowBirthday},""" +
            """"partner":{"id":"${partner.id}","displayName":${JsonPrimitive(partner.displayName)},"username":"${partner.username}",""" +
            """"avatarUrl":null,"bio":"喜欢在傍晚散步","birthday":"06-20","birthYear":2000,"birthdayVisible":true},""" +
            """"anniversary":1735689600000,"pairedAt":1735689600000,"days":[$days]}"""
    }

    /** 上一次 POST 的 body（respond() 是纯函数签名，multipart 之外的场景由拦截器暂存于此）。 */
    @Volatile var demoBody: String = "{}"

    /** multipart 上传：解析第一个 file part，内存存字节，返回 MediaView。 */
    fun handleMediaUpload(body: okhttp3.ResponseBody, now: Long): Pair<Int, String> {
        return try {
            val reader = MultipartReader(body)
            var bytes: ByteArray? = null
            reader.use { r ->
                var part = r.nextPart()
                while (part != null) {
                    if (part.headers["Content-Disposition"]?.contains("name=\"file\"") == true) {
                        bytes = part.body.readByteArray()
                        break
                    }
                    part = r.nextPart()
                }
            }
            val data = bytes ?: return 400 to err("VALIDATION_ERROR", "multipart field 'file' required")
            // 纯 Kotlin 读头部尺寸（BitmapFactory 在 JVM 单测里是空桩，且 mime 以实际字节为准）
            val info = com.qimingnan.couple.core.ImageSize.read(data) ?: return 400 to err("VALIDATION_ERROR", "not an image")
            if (data.size > 10 * 1024 * 1024) return 400 to err("VALIDATION_ERROR", "too large")
            val key = Ulid.next(now)
            synchronized(mediaStore) { mediaStore[key] = DemoMedia(key, data, info.mime, info.width, info.height) }
            200 to """{"storageKey":"$key","thumbKey":null,"mimeType":"${info.mime}","width":${info.width},"height":${info.height},"size":${data.size}}"""
        } catch (e: Exception) {
            400 to err("VALIDATION_ERROR", "bad multipart: ${e.message}")
        }
    }

    fun media(key: String): DemoMedia? = synchronized(mediaStore) { mediaStore[key] }

    const val NO_UPDATE = """{"latest":null,"force":false}"""

    /** 只代理本仓库 GitHub Release 上的 APK，别的地址一律拒绝。 */
    const val APK_PREFIX = "https://github.com/slss15/couple-/releases/download/"
    const val APK_PROXY_PATH = "app/demo-apk"

    /**
     * 调试 OTA 通道：CI 每次推 main 都把 debug APK 和 update.json 发到 `app-debug-edge` 预发布。这里把
     * update.json 的 downloadUrl 改写成演示后端同源的代理地址（App 只允许同源下载），返回给 UpdateRepository。
     */
    fun rewriteUpdate(updateJson: String): String {
        val root = CoupleJson.parseToJsonElement(updateJson).jsonObject
        val latest = root["latest"] as? JsonObject ?: return NO_UPDATE
        val url = latest["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: return NO_UPDATE
        if (!url.startsWith(APK_PREFIX)) return NO_UPDATE
        val proxied = "/api/v1/$APK_PROXY_PATH?u=" + java.net.URLEncoder.encode(url, "UTF-8")
        val newLatest = JsonObject(latest + ("downloadUrl" to JsonPrimitive(proxied)))
        return JsonObject(root + ("latest" to newLatest)).toString()
    }

    private fun err(code: String, message: String) = buildJsonObject { put("code", code); put("message", message) }.toString()
}

/** 放在 OkHttp 链最前面：只拦截 [DemoBackend.HOST]，其余请求原样放行。 */
class DemoInterceptor(private val now: () -> Long = System::currentTimeMillis) : Interceptor {
    /** 不经过任何拦截器的上游客户端，只用于调试 OTA 通道访问 GitHub。 */
    private val upstream by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req: Request = chain.request()
        if (!DemoBackend.available || req.url.host != DemoBackend.HOST) return chain.proceed(req)
        val path = req.url.encodedPath.removePrefix("/api/v1/")
        if (req.method == "GET" && path == "app/update") return json(req, 200, fetchUpdate())
        if (req.method == "GET" && path == DemoBackend.APK_PROXY_PATH) return proxyApk(req)
        if (req.method == "POST" && path == "media") {
            val body = req.body ?: return json(req, 400, """{"code":"VALIDATION_ERROR","message":"empty body"}""")
            val (code, resp) = runCatching {
                val buf = okio.Buffer()
                body.writeTo(buf)
                // MultipartReader 要从 ResponseBody 的 contentType 里读 boundary，这里原样保留请求的 multipart 头
                val rb = buf.readByteArray().toResponseBody(body.contentType())
                DemoBackend.handleMediaUpload(rb, now())
            }.getOrElse { e -> 400 to """{"code":"VALIDATION_ERROR","message":"bad multipart: ${e.message}"}""" }
            return json(req, code, resp)
        }
        val mediaMatch = Regex("^media/([^/]+)(/thumb)?$").find(path)
        if (req.method == "GET" && mediaMatch != null) {
            val m = DemoBackend.media(mediaMatch.groupValues[1]) ?: return json(req, 404, """{"code":"MEDIA_NOT_FOUND","message":"no such media"}""")
            return Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(m.bytes.toResponseBody(m.mime.toMediaType()))
                .build()
        }
        // POST/PUT body 显式交给 respond()（不再写全局变量，避免并发请求串 body）
        val reqBody = if (req.method == "POST" || req.method == "PUT") {
            runCatching {
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                buf.readUtf8()
            }.getOrDefault("{}").ifBlank { "{}" }
        } else null
        val (code, body) = DemoBackend.respond(
            req.method,
            req.url.encodedPath,
            now(),
            mapOf(
                "after" to req.url.queryParameter("after"),
                "before" to req.url.queryParameter("before"),
                "limit" to req.url.queryParameter("limit"),
            ),
            body = reqBody,
        )
        return json(req, code, body)
    }

    /** 从 GitHub 拉调试通道的 update.json；拉不到就当没有更新。 */
    private fun fetchUpdate(): String {
        val src = BuildConfig.DEMO_UPDATE_URL.takeIf { it.isNotBlank() } ?: return DemoBackend.NO_UPDATE
        return runCatching {
            upstream.newCall(Request.Builder().url(src).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) DemoBackend.NO_UPDATE else DemoBackend.rewriteUpdate(r.body?.string().orEmpty())
            }
        }.getOrDefault(DemoBackend.NO_UPDATE)
    }

    private fun proxyApk(req: Request): Response {
        val target = req.url.queryParameter("u")
        if (target == null || !target.startsWith(DemoBackend.APK_PREFIX)) return json(req, 403, """{"code":"FORBIDDEN","message":"只允许本仓库的 Release"}""")
        // 不带 Authorization（演示 token 不能发给 GitHub）；Range 透传以便断点续传
        val up = Request.Builder().url(target).apply { req.header("Range")?.let { header("Range", it) } }.build()
        val resp = upstream.newCall(up).execute()
        return resp.newBuilder().request(req).build()
    }

    private fun json(req: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Demo")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
