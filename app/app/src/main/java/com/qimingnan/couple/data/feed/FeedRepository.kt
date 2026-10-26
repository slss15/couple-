package com.qimingnan.couple.data.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.db.CommentEntity
import com.qimingnan.couple.data.db.FeedDao
import com.qimingnan.couple.data.db.PostEntity
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.net.AddCommentRequest
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.CreatePostRequest
import com.qimingnan.couple.data.net.FeedEvent
import com.qimingnan.couple.di.ApplicationScope
import com.qimingnan.couple.notify.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 图片压缩参数：最长边 2048、JPEG 85（服务端还会再做 EXIF 剥离与缩略图）。 */
object ImageCompression {
    const val MAX_SIDE = 2048
    const val JPEG_QUALITY = 85
    private val uriListJson = Json

    /** 压缩为 JPEG；解码失败（坏文件/非图片）返回 null。 */
    fun compress(context: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val maxSide = maxOf(bmp.width, bmp.height)
        val scaled = if (maxSide > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
    }.getOrNull()

    fun encodeUris(uris: List<Uri>): String = uriListJson.encodeToString(ListSerializer(String.serializer()), uris.map { it.toString() })

    fun decodeUris(s: String?): List<Uri> = s?.let {
        runCatching { uriListJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty().map { Uri.parse(it) }
}

/** 发布 outbox 的本地图片：filesDir/outbox/{postId}-{i}.jpg（压缩后的 JPEG），发送成功即删。 */
object OutboxFiles {
    fun dir(context: Context) = java.io.File(context.filesDir, "outbox").apply { mkdirs() }

    /** 逐张压缩并落盘；压缩失败的跳过。 */
    fun persist(context: Context, prefix: String, uris: List<Uri>, compress: (Uri) -> ByteArray?): List<java.io.File> =
        persistTo(dir(context), prefix, uris, compress)

    fun <T> persistTo(dir: java.io.File, prefix: String, uris: List<T>, compress: (T) -> ByteArray?): List<java.io.File> =
        uris.mapIndexedNotNull { i, uri ->
            val bytes = compress(uri) ?: return@mapIndexedNotNull null
            java.io.File(dir, "$prefix-$i.jpg").apply { writeBytes(bytes) }
        }

    /** file:// 读字节；非 file 或文件不在返回 null。 */
    fun read(uri: Uri): ByteArray? = if (uri.scheme == "file") uri.path?.let { java.io.File(it) }?.takeIf { it.isFile }?.readBytes() else null

    fun delete(context: Context, prefix: String) {
        dir(context).listFiles { f -> f.name.startsWith("$prefix-") }?.forEach { it.delete() }
    }
}

/**
 * 动态仓库：Room 是 UI 的唯一数据源。事件合并（MQTT / 演示进程内流）+ 分页补拉 + 离线发布 outbox
 * （本地 PENDING 行 → [FeedPublishWorker] 压缩上传 → POST，服务端按客户端 id 幂等）。
 */
@Singleton
class FeedRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: CoupleApi,
    private val dao: FeedDao,
    private val bootstrapStore: BootstrapStore,
    private val session: SessionManager,
    private val mqtt: MqttManager,
    private val notifier: Notifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val posts = dao.observePosts()

    private var started = false

    /** AppCoordinator.start() 之后调用一次。 */
    fun start() {
        if (started) return
        started = true
        notifier.createChannels()
        scope.launch {
            // MQTT 与演示进程内流不会同时活跃；两个都收。
            launch { mqtt.feedEvents.collect { applyEvent(it) } }
            launch { DemoBackend.feedEvents.collect { applyEvent(it) } }
        }
    }

    private suspend fun applyEvent(payload: String) {
        when (val e = FeedEvent.parse(payload) ?: return) {
            is FeedEvent.Post -> {
                dao.upsertConfirmed(e.post)
                maybeNotify(e.post.authorId, "新的动态", e.post.text.ifBlank { "[图片]" })
            }
            is FeedEvent.Delete -> dao.deletePost(e.id)
            is FeedEvent.Like -> {
                val me = selfId()
                dao.postRow(e.postId)?.let { row ->
                    val likedByMe = when {
                        e.userId == me -> true
                        e.userId == row.post.authorId && row.post.authorId != me -> false
                        else -> row.post.likedByMe
                    }
                    dao.updateLikes(e.postId, e.count, likedByMe)
                }
            }
            is FeedEvent.Comment -> {
                dao.upsertComments(listOf(CommentEntity(e.comment.id, e.comment.postId, e.comment.authorId, e.comment.text, e.comment.createdAt)))
                maybeNotify(e.comment.authorId, "新的评论", e.comment.text)
            }
            is FeedEvent.CommentDelete -> dao.deleteCommentRow(e.id)
        }
    }

    private fun maybeNotify(authorId: String, title: String, text: String) {
        if (authorId == selfId()) return
        if (notifier.isScreenVisible("feed")) return
        notifier.notify(Notifier.CH_FEED, Notifier.ID_FEED, title, text)
    }

    private fun selfId(): String? = (session.state.value as? SessionState.LoggedIn)?.user?.id

    /** 首页刷新：最新一页 upsert；“本应出现在该页”却不在的已确认帖子 = 服务器已删，同步移除。 */
    suspend fun refresh() {
        _refreshing.value = true
        _error.value = null
        runCatching { api.feedPosts(before = null, limit = PAGE) }
            .onSuccess { page ->
                _hasMore.value = page.hasMore
                page.posts.forEach { dao.upsertConfirmed(it) }
                if (page.posts.isNotEmpty()) {
                    val oldest = page.posts.minOf { it.createdAt }
                    val keep = page.posts.map { it.id }.toSet()
                    dao.confirmedIdsNewerThan(oldest).filter { it !in keep }.forEach { dao.deletePost(it) }
                }
            }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
        _refreshing.value = false
    }

    /** 下一页（before = 本地最旧的已确认帖子）。 */
    suspend fun loadMore() {
        val before = dao.oldestConfirmedId() ?: return
        runCatching { api.feedPosts(before = before, limit = PAGE) }
            .onSuccess { page ->
                _hasMore.value = page.hasMore
                page.posts.forEach { dao.upsertConfirmed(it) }
            }
            .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
    }

    /**
     * 本地建 PENDING 行并交给 worker。图片在这里就压缩并落到 filesDir/outbox：相册选择器给的 content-uri
     * 只是临时授权，进程被杀/重启后 worker 再读会失败（帖子永远「发送失败」）。
     */
    fun publish(text: String, imageUris: List<Uri>) {
        val me = selfId() ?: return
        if (text.isBlank() && imageUris.isEmpty()) return
        val id = Ulid.next()
        scope.launch(Dispatchers.IO) {
            val files = OutboxFiles.persist(context, id, imageUris) { ImageCompression.compress(context, it) }
            if (files.size < imageUris.size) _error.value = "有 ${imageUris.size - files.size} 张图片无法读取，已跳过"
            if (text.isBlank() && files.isEmpty()) return@launch
            dao.upsertPost(
                PostEntity(
                    id = id, authorId = me, text = text.trim(), createdAt = System.currentTimeMillis(),
                    likeCount = 0, likedByMe = false, pendingState = PENDING,
                    pendingImages = ImageCompression.encodeUris(files.map { Uri.fromFile(it) }),
                ),
            )
            FeedPublishWorker.enqueue(context, id, requireNetwork = !isDemo())
        }
    }

    fun retryPending(postId: String) {
        scope.launch(Dispatchers.IO) {
            dao.updatePendingState(postId, PENDING)
            FeedPublishWorker.enqueue(context, postId, requireNetwork = !isDemo(), replace = true)
        }
    }

    /** 演示后端在进程内：不需要真实网络，WorkManager 不能挂网络约束（否则离线时永远「发送中」）。 */
    private fun isDemo(): Boolean = DemoBackend.isDemo(bootstrapStore.currentConfigBlocking()?.baseUrl)

    /** worker 调用：上传 + POST。网络/5xx → RETRY（保持 PENDING，WorkManager 退避）；4xx/坏图 → FAILED。 */
    suspend fun sendPending(postId: String): SendOutcome {
        val row = dao.postRow(postId) ?: return SendOutcome.DONE
        if (row.post.pendingState != PENDING) return SendOutcome.DONE
        val outcome = try {
            val keys = ImageCompression.decodeUris(row.post.pendingImages).map { uri ->
                val bytes = OutboxFiles.read(uri) ?: ImageCompression.compress(context, uri)
                if (bytes == null) {
                    dao.updatePendingState(postId, FAILED)
                    return SendOutcome.FAILED
                }
                api.uploadMedia(MultipartBody.Part.createFormData("file", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))).storageKey
            }
            dao.upsertConfirmed(api.createPost(CreatePostRequest(id = postId, text = row.post.text, mediaKeys = keys)))
            OutboxFiles.delete(context, postId)
            SendOutcome.DONE
        } catch (e: Exception) {
            classifySendError(e)
        }
        if (outcome == SendOutcome.FAILED) dao.updatePendingState(postId, FAILED)
        return outcome
    }

    suspend fun toggleLike(postId: String) {
        val row = dao.postRow(postId) ?: return
        if (row.post.pendingState != null) return
        val liked = row.post.likedByMe
        dao.updateLikes(postId, row.post.likeCount + if (liked) -1 else 1, !liked)
        runCatching { if (liked) api.unlikePost(postId) else api.likePost(postId) }
            .onFailure { r ->
                dao.postRow(postId)?.let { cur -> dao.updateLikes(postId, cur.post.likeCount, liked) }
                _error.value = r.message
            }
    }

    suspend fun addComment(postId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val me = selfId() ?: return
        val id = Ulid.next()
        dao.upsertComments(listOf(CommentEntity(id, postId, me, trimmed, System.currentTimeMillis())))
        runCatching { api.addComment(postId, AddCommentRequest(id, trimmed)) }
            .onFailure { dao.deleteCommentRow(id); _error.value = it.message }
    }

    suspend fun deleteComment(commentId: String) {
        dao.deleteCommentRow(commentId)
        runCatching { api.deleteComment(commentId) }.onFailure { _error.value = it.message }
    }

    suspend fun deletePost(postId: String) {
        dao.deletePost(postId)
        OutboxFiles.delete(context, postId)
        runCatching { api.deletePost(postId) }.onFailure { _error.value = it.message }
    }

    /** 媒体绝对地址（Coil 用 apiClient 加载，自动带 Bearer）。 */
    fun mediaUrl(storageKey: String, thumb: Boolean = false): String? {
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl?.trimEnd('/') ?: return null
        return base + CoupleApi.MEDIA_PATH + storageKey + if (thumb) "/thumb" else ""
    }

    suspend fun clearLocal() = dao.clearAll()

    enum class SendOutcome { DONE, RETRY, FAILED }

    companion object {
        /** 4xx（除 408/429）与坏响应是永久失败；5xx/408/429/网络异常可重试。 */
        fun classifySendError(e: Throwable): SendOutcome = when (e) {
            is retrofit2.HttpException -> if (e.code() in 400..499 && e.code() != 408 && e.code() != 429) SendOutcome.FAILED else SendOutcome.RETRY
            is kotlinx.serialization.SerializationException -> SendOutcome.FAILED
            else -> SendOutcome.RETRY
        }

        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
        const val PAGE = 20
    }
}
