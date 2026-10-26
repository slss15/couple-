package com.qimingnan.couple.backend.feed

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.conflict
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.MediaObjects
import com.qimingnan.couple.backend.db.PostComments
import com.qimingnan.couple.backend.db.PostLikes
import com.qimingnan.couple.backend.db.Posts
import com.qimingnan.couple.backend.db.Users
import com.qimingnan.couple.backend.media.MediaObject
import com.qimingnan.couple.backend.media.MediaRepo
import com.qimingnan.couple.backend.media.MediaView
import com.qimingnan.couple.backend.media.StorageManager
import com.qimingnan.couple.backend.mqtt.ServerPush
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class CommentView(
    val id: String,
    val postId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)

@Serializable
data class LikesView(val count: Int, val likedByMe: Boolean)

@Serializable
data class PostView(
    val id: String,
    val coupleId: String,
    val authorId: String,
    val text: String,
    val media: List<MediaView>,
    val createdAt: Long,
    val likes: LikesView,
    val comments: List<CommentView>,
    val commentCount: Int,
)

@Serializable
data class FeedPage(val posts: List<PostView>, val hasMore: Boolean)

@Serializable
data class CreatePostRequest(val id: String, val text: String = "", val mediaKeys: List<String> = emptyList())

@Serializable
data class AddCommentRequest(val id: String, val text: String)

@Serializable
data class LikeCountView(val count: Int)

/** Persistence for posts / likes / comments. Must be called inside a transaction. */
object FeedRepo {
    fun postById(id: String): ResultRow? = Posts.selectAll().where { Posts.id eq id }.firstOrNull()

    /** The newest [limit]+1 posts of a couple older than [before] (createdAt + id tiebreak), or the newest page. */
    fun postsOfCouple(coupleId: String, before: String?, limit: Int): List<ResultRow> {
        val query = Posts.selectAll().where { Posts.coupleId eq coupleId }
        val filtered = if (before != null) {
            val anchor = postById(before) ?: throw notFound("POST_NOT_FOUND", "no post $before")
            val ts = anchor[Posts.createdAt]
            val id = anchor[Posts.id]
            query.andWhere {
                (Posts.createdAt less ts) or ((Posts.createdAt eq ts) and (Posts.id less id))
            }
        } else query
        return filtered.orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC).limit(limit + 1).toList()
    }

    fun mediaOfPost(postId: String): List<MediaObject> =
        MediaObjects.selectAll().where { MediaObjects.usedByPost eq postId }
            .orderBy(MediaObjects.createdAt to SortOrder.ASC)
            .map {
                MediaObject(
                    storageKey = it[MediaObjects.storageKey], coupleId = it[MediaObjects.coupleId],
                    purpose = it[MediaObjects.purpose], mimeType = it[MediaObjects.mimeType], size = it[MediaObjects.size],
                    width = it[MediaObjects.width], height = it[MediaObjects.height],
                    thumbKey = it[MediaObjects.thumbKey], usedByPost = it[MediaObjects.usedByPost],
                    createdAt = it[MediaObjects.createdAt],
                )
            }

    fun likeCount(postId: String): Int = PostLikes.selectAll().where { PostLikes.postId eq postId }.count().toInt()

    fun likedBy(postId: String, userId: String): Boolean =
        PostLikes.selectAll().where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.any()

    fun commentsOf(postId: String): List<CommentView> =
        PostComments.selectAll().where { PostComments.postId eq postId }
            .orderBy(PostComments.createdAt to SortOrder.ASC, PostComments.id to SortOrder.ASC)
            .map { CommentView(it[PostComments.id], it[PostComments.postId], it[PostComments.authorId], it[PostComments.text], it[PostComments.createdAt]) }

    fun commentById(id: String): CommentView? =
        PostComments.selectAll().where { PostComments.id eq id }.firstOrNull()?.let {
            CommentView(it[PostComments.id], it[PostComments.postId], it[PostComments.authorId], it[PostComments.text], it[PostComments.createdAt])
        }

    fun insertLike(postId: String, userId: String, now: Long) {
        PostLikes.insert {
            it[PostLikes.postId] = postId
            it[PostLikes.userId] = userId
            it[createdAt] = now
        }
    }

    fun insertComment(c: CommentView) {
        PostComments.insert {
            it[PostComments.id] = c.id
            it[PostComments.postId] = c.postId
            it[PostComments.authorId] = c.authorId
            it[PostComments.text] = c.text
            it[createdAt] = c.createdAt
        }
    }

    fun insertPost(id: String, coupleId: String, authorId: String, text: String, now: Long) {
        Posts.insert {
            it[Posts.id] = id
            it[Posts.coupleId] = coupleId
            it[Posts.authorId] = authorId
            it[Posts.text] = text
            it[createdAt] = now
        }
    }

    fun deletePostCascade(postId: String) {
        PostComments.deleteWhere { PostComments.postId eq postId }
        PostLikes.deleteWhere { PostLikes.postId eq postId }
        Posts.deleteWhere { Posts.id eq postId }
    }
}

class FeedService(
    private val db: Db,
    private val config: AppConfig,
    private val storage: StorageManager,
    private val push: ServerPush,
) {
    suspend fun coupleIdOf(userId: String): String? =
        db.tx { Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.coupleId) }

    private suspend fun requireCouple(caller: AuthUser): String =
        coupleIdOf(caller.userId) ?: throw notFound("NOT_PAIRED", "You are not paired")

    fun toView(row: ResultRow, me: String): PostView {
        val id = row[Posts.id]
        val media = FeedRepo.mediaOfPost(id).map {
            MediaView(it.storageKey, it.thumbKey, it.mimeType, it.width, it.height, it.size)
        }
        val comments = FeedRepo.commentsOf(id)
        return PostView(
            id = id,
            coupleId = row[Posts.coupleId],
            authorId = row[Posts.authorId],
            text = row[Posts.text],
            media = media,
            createdAt = row[Posts.createdAt],
            likes = LikesView(FeedRepo.likeCount(id), FeedRepo.likedBy(id, me)),
            comments = comments,
            commentCount = comments.size,
        )
    }

    /** POST /feed/posts. Idempotent on the client id (contract "Feed" §Model). */
    suspend fun createPost(caller: AuthUser, req: CreatePostRequest): PostView {
        if (!ID_RE.matches(req.id)) throw badRequest("VALIDATION_ERROR", "id must be a ULID")
        val text = req.text.trim()
        if (text.isEmpty() && req.mediaKeys.isEmpty()) throw badRequest("VALIDATION_ERROR", "text or media required")
        if (text.length > 4000) throw badRequest("VALIDATION_ERROR", "text too long (max 4000)")
        if (req.mediaKeys.size > 9) throw badRequest("VALIDATION_ERROR", "at most 9 images per post")
        val cid = requireCouple(caller)
        val now = config.clock()
        val existing = db.tx { FeedRepo.postById(req.id) }
        if (existing != null) {
            if (existing[Posts.coupleId] != cid) throw conflict("ID_CONFLICT", "id already used by another couple")
            return db.tx { toView(existing, caller.userId) }
        }
        val view = db.tx {
            for (k in req.mediaKeys) {
                val m = MediaRepo.byKey(k) ?: throw badRequest("VALIDATION_ERROR", "unknown media $k")
                if (m.coupleId != cid) throw badRequest("VALIDATION_ERROR", "media $k belongs to another couple")
                if (m.usedByPost != null) throw conflict("MEDIA_ALREADY_USED", "media $k already belongs to a post")
            }
            FeedRepo.insertPost(req.id, cid, caller.userId, text, now)
            for (k in req.mediaKeys) MediaRepo.markUsed(k, req.id)
            toView(FeedRepo.postById(req.id)!!, caller.userId)
        }
        push.publish(cid, "feed", postEvent("post", view))
        return view
    }

    suspend fun list(caller: AuthUser, before: String?, limit: Int?): FeedPage {
        val cid = requireCouple(caller)
        val n = (limit ?: 20).coerceIn(1, 50)
        return db.tx {
            val rows = FeedRepo.postsOfCouple(cid, before, n)
            val hasMore = rows.size > n
            FeedPage(rows.take(n).map { toView(it, caller.userId) }, hasMore)
        }
    }

    suspend fun deletePost(caller: AuthUser, postId: String) {
        val cid = requireCouple(caller)
        val mediaKeys = db.tx {
            val row = FeedRepo.postById(postId) ?: throw notFound("POST_NOT_FOUND", "no post $postId")
            if (row[Posts.coupleId] != cid) throw forbidden("MEDIA_FORBIDDEN", "not your couple's post")
            if (row[Posts.authorId] != caller.userId) throw forbidden("NOT_AUTHOR", "only the author can delete a post")
            val keys = FeedRepo.mediaOfPost(postId).map { it.storageKey to it.thumbKey }
            FeedRepo.deletePostCascade(postId)
            for ((k, _) in keys) MediaRepo.deleteRow(k)
            keys
        }
        for ((k, thumb) in mediaKeys) {
            runCatching {
                storage.delete(k)
                thumb?.let { storage.delete(it) }
            }
        }
        push.publish(cid, "feed", """{"kind":"delete","id":"$postId"}""")
    }

    suspend fun like(caller: AuthUser, postId: String): LikeCountView {
        val cid = requireCouple(caller)
        val (count, inserted) = db.tx {
            requireOwnPost(postId, cid)
            val exists = FeedRepo.likedBy(postId, caller.userId)
            if (!exists) FeedRepo.insertLike(postId, caller.userId, config.clock())
            FeedRepo.likeCount(postId) to !exists
        }
        if (inserted) push.publish(cid, "feed", """{"kind":"like","postId":"$postId","userId":"${caller.userId}","count":$count}""")
        return LikeCountView(count)
    }

    suspend fun unlike(caller: AuthUser, postId: String): LikeCountView {
        val cid = requireCouple(caller)
        val (count, removed) = db.tx {
            requireOwnPost(postId, cid)
            val exists = FeedRepo.likedBy(postId, caller.userId)
            if (exists) PostLikes.deleteWhere { (PostLikes.postId eq postId) and (PostLikes.userId eq caller.userId) }
            FeedRepo.likeCount(postId) to exists
        }
        if (removed) push.publish(cid, "feed", """{"kind":"like","postId":"$postId","userId":"${caller.userId}","count":$count}""")
        return LikeCountView(count)
    }

    suspend fun addComment(caller: AuthUser, postId: String, req: AddCommentRequest): CommentView {
        if (!ID_RE.matches(req.id)) throw badRequest("VALIDATION_ERROR", "id must be a ULID")
        val text = req.text.trim()
        if (text.isEmpty() || text.length > 1000) throw badRequest("VALIDATION_ERROR", "comment must be 1..1000 chars")
        val cid = requireCouple(caller)
        val existing = db.tx { FeedRepo.commentById(req.id) }
        if (existing != null) {
            val sameCouple = db.tx { FeedRepo.postById(existing.postId)?.get(Posts.coupleId) == cid }
            if (!sameCouple) throw conflict("ID_CONFLICT", "id already used by another couple")
            return existing
        }
        val now = config.clock()
        val comment = db.tx {
            requireOwnPost(postId, cid)
            val c = CommentView(req.id, postId, caller.userId, text, now)
            FeedRepo.insertComment(c)
            c
        }
        push.publish(cid, "feed", commentEvent(postId, comment))
        return comment
    }

    suspend fun deleteComment(caller: AuthUser, commentId: String) {
        val cid = requireCouple(caller)
        var publishedPostId: String? = null
        db.tx {
            val c = FeedRepo.commentById(commentId) ?: throw notFound("COMMENT_NOT_FOUND", "no comment $commentId")
            val post = FeedRepo.postById(c.postId) ?: throw notFound("POST_NOT_FOUND", "no post ${c.postId}")
            if (post[Posts.coupleId] != cid) throw forbidden("MEDIA_FORBIDDEN", "not your couple's comment")
            if (c.authorId != caller.userId) throw forbidden("NOT_AUTHOR", "only the author can delete a comment")
            PostComments.deleteWhere { PostComments.id eq commentId }
            publishedPostId = c.postId
        }
        push.publish(cid, "feed", """{"kind":"comment_delete","postId":"$publishedPostId","id":"$commentId"}""")
    }

    private fun requireOwnPost(postId: String, cid: String): ResultRow {
        val row = FeedRepo.postById(postId) ?: throw notFound("POST_NOT_FOUND", "no post $postId")
        if (row[Posts.coupleId] != cid) throw forbidden("MEDIA_FORBIDDEN", "not your couple's post")
        return row
    }

    companion object {
        /** Crockford-base32 ULID. */
        val ID_RE = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

        fun postEvent(kind: String, post: PostView): String {
            val body = AppJson.encodeToString(PostView.serializer(), post)
            return """{"kind":"$kind","post":$body}"""
        }

        fun commentEvent(postId: String, comment: CommentView): String {
            val body = AppJson.encodeToString(CommentView.serializer(), comment)
            return """{"kind":"comment","postId":"$postId","comment":$body}"""
        }
    }
}
