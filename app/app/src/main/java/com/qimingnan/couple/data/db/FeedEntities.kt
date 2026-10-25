package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.qimingnan.couple.data.net.CommentView
import com.qimingnan.couple.data.net.LikesView
import com.qimingnan.couple.data.net.MediaView
import com.qimingnan.couple.data.net.PostView
import kotlinx.coroutines.flow.Flow

/**
 * Feed 本地缓存（Room v2）。pendingState：null = 服务端确认；PENDING = 离线待发（FeedPublishWorker 重试）；
 * FAILED = 服务端 4xx，点击重试。pendingImages 只在 PENDING 期间存本地 content-uri 列表（JSON 数组字符串）。
 */
@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
    val likeCount: Int,
    val likedByMe: Boolean,
    val pendingState: String?,
    val pendingImages: String?,
)

@Entity(tableName = "post_media", primaryKeys = ["postId", "sortOrder"])
data class PostMediaEntity(
    val postId: String,
    val sortOrder: Int,
    val storageKey: String,
    val thumbKey: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
)

@Entity(
    tableName = "post_comments",
    foreignKeys = [ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("postId")],
)
data class CommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)

/** UI 渲染的一行：帖子 + 图片 + 评论（时间正序）。 */
data class PostRow(
    @Embedded val post: PostEntity,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val media: List<PostMediaEntity>,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val comments: List<CommentEntity>,
)

fun PostView.toEntity() = PostEntity(
    id = id,
    authorId = authorId,
    text = text,
    createdAt = createdAt,
    likeCount = likes.count,
    likedByMe = likes.likedByMe,
    pendingState = null,
    pendingImages = null,
)

fun PostView.toMediaEntities() = media.mapIndexed { i, m ->
    PostMediaEntity(id, i, m.storageKey, m.thumbKey, m.mimeType, m.width, m.height, m.size)
}

fun PostView.toCommentEntities() = comments.map { CommentEntity(it.id, it.postId, it.authorId, it.text, it.createdAt) }

fun PostRow.displayMedia(): List<MediaView> = media.sortedBy { it.sortOrder }.map {
    MediaView(it.storageKey, it.thumbKey, it.mimeType, it.width, it.height, it.size)
}

fun PostRow.likesView() = LikesView(post.likeCount, post.likedByMe)

@Dao
interface FeedDao {
    @Query("SELECT * FROM posts ORDER BY createdAt DESC, id DESC")
    fun observePosts(): Flow<List<PostRow>>

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun postRow(id: String): PostRow?

    @Query("SELECT * FROM posts WHERE pendingState = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestPending(): PostRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPost(post: PostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(media: List<PostMediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComments(comments: List<CommentEntity>)

    @Query("DELETE FROM post_media WHERE postId = :postId")
    suspend fun clearMedia(postId: String)

    @Query("DELETE FROM post_comments WHERE postId = :postId")
    suspend fun clearComments(postId: String)

    @Transaction
    suspend fun upsertConfirmed(view: PostView) {
        upsertPost(view.toEntity())
        clearMedia(view.id)
        clearComments(view.id)
        upsertMedia(view.toMediaEntities())
        upsertComments(view.toCommentEntities())
    }

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun deletePost(id: String)

    @Query("UPDATE posts SET likeCount = :count, likedByMe = :likedByMe WHERE id = :id")
    suspend fun updateLikes(id: String, count: Int, likedByMe: Boolean)

    @Query("UPDATE posts SET pendingState = :state WHERE id = :id")
    suspend fun updatePendingState(id: String, state: String?)

    @Query("DELETE FROM post_comments WHERE id = :id")
    suspend fun deleteCommentRow(id: String)

    @Query("SELECT COUNT(*) FROM posts")
    suspend fun count(): Int

    /** 已确认（pendingState IS NULL）且比 [ts] 新的帖子 id——刷新时用来剔除服务器已删的。 */
    @Query("SELECT id FROM posts WHERE pendingState IS NULL AND createdAt >= :ts")
    suspend fun confirmedIdsNewerThan(ts: Long): List<String>

    @Query("SELECT id FROM posts WHERE pendingState IS NULL ORDER BY createdAt DESC, id DESC")
    suspend fun confirmedIds(): List<String>

    @Query("SELECT id FROM posts WHERE pendingState IS NULL ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun oldestConfirmedId(): String?

    @Query("DELETE FROM posts")
    suspend fun clearAll()
}
