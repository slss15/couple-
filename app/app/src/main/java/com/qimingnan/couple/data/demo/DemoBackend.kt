package com.qimingnan.couple.data.demo

import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.bootstrap.ServerConfig
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

    init {
        seed()
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
        val comments = p.comments.map { (id, both) ->
            val (author, text) = both.split("|", limit = 2)
            commentJson(id, author, text, p.createdAt + 60_000)
        }
        return """{"id":"${p.id}","coupleId":"$COUPLE_ID","authorId":"${p.authorId}","text":${JsonPrimitive(p.text)},""" +
            """"media":[$media],"createdAt":${p.createdAt},"likes":{"count":${p.likeCount()},"likedByMe":${p.likedBySelf}},""" +
            """"comments":[$comments],"commentCount":${comments.size}}"""
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
    fun respond(method: String, path: String, now: Long): Pair<Int, String> {
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
            "POST feed/posts" -> {
                val body = json.parseToJsonElement(demoBody).jsonObject
                val id = body["id"]?.jsonPrimitive?.content ?: return 400 to err("VALIDATION_ERROR", "id required")
                posts.firstOrNull { it.id == id }?.let { return 200 to postJson(it) } // 幂等
                val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val keys = (body["mediaKeys"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                if (text.isBlank() && keys.isEmpty()) return 400 to err("VALIDATION_ERROR", "text or media required")
                val media = keys.mapNotNull { mediaStore[it] }
                val post = DemoPost(id, self().id, text, now, media.toMutableList())
                posts += post
                demoScope.launch { feedEvents.emit("""{"kind":"post","post":${postJson(post)}}""") }
                autoReact(id)
                200 to postJson(post)
            }
            else -> feedMutableEndpoints(method, p, now)
        }
    }

    /** 需要可变状态的 feed 子路径（like/comment/delete）。 */
    private fun feedMutableEndpoints(method: String, p: String, now: Long): Pair<Int, String> {
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
            val body = json.parseToJsonElement(demoBody).jsonObject
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
        return 404 to err("NOT_FOUND", "演示后端未实现：$method /$p")
    }

    /** 上一次 POST 的 body（respond() 是纯函数签名，multipart 之外的场景由拦截器暂存于此）。 */
    @Volatile var demoBody: String = "{}"

    /** multipart 上传：解析第一个 file part，内存存字节，返回 MediaView。 */
    fun handleMediaUpload(body: okhttp3.ResponseBody, now: Long): Pair<Int, String> {
        return try {
            val reader = MultipartReader(body)
            var bytes: ByteArray? = null
            var mime = "image/jpeg"
            reader.use { r ->
                var part = r.nextPart()
                while (part != null) {
                    if (part.headers["Content-Disposition"]?.contains("name=\"file\"") == true) {
                        mime = part.headers["Content-Type"] ?: mime
                        bytes = part.body.readByteArray()
                        break
                    }
                    part = r.nextPart()
                }
            }
            val data = bytes ?: return 400 to err("VALIDATION_ERROR", "multipart field 'file' required")
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            if (opts.outWidth <= 0) return 400 to err("VALIDATION_ERROR", "not an image")
            val key = Ulid.next(now)
            mediaStore[key] = DemoMedia(key, data, mime, opts.outWidth, opts.outHeight)
            200 to """{"storageKey":"$key","thumbKey":null,"mimeType":"$mime","width":${opts.outWidth},"height":${opts.outHeight},"size":${data.size}}"""
        } catch (e: Exception) {
            400 to err("VALIDATION_ERROR", "bad multipart: ${e.message}")
        }
    }

    fun media(key: String): DemoMedia? = mediaStore[key]

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
        // POST body 暂存给纯函数 respond()（feed 等需要读 body 的端点）
        if (req.method == "POST" || req.method == "PUT") {
            DemoBackend.demoBody = runCatching {
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                buf.readUtf8()
            }.getOrDefault("{}")
        }
        val (code, body) = DemoBackend.respond(req.method, req.url.encodedPath, now())
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
