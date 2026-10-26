package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.qimingnan.couple.data.net.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * 聊天本地缓存（Room v3）。pendingState：null = 服务端确认；PENDING = 离线待发（ChatSendWorker 重试）；
 * FAILED = 服务端 4xx，点击重试。createdAt 为服务端时间（未确认为 null），sortAt 是 UI 排序键
 * （确认前用 clientTs，确认后换成 createdAt）。
 */
@Entity(tableName = "chat_messages", indices = [Index("sortAt")])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val text: String,
    val replyTo: String?,
    val clientTs: Long,
    val createdAt: Long?,
    val sortAt: Long,
    val recalledAt: Long?,
    val pendingState: String?,
    /** "text" | "image" | "sticker"（v4）。 */
    @androidx.room.ColumnInfo(defaultValue = "text") val type: String = TYPE_TEXT,
    /** 服务端媒体 key（GET /media/{mediaId}）；图片在上传前为 null。 */
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 待发图片：outbox 里已压缩的本地文件 uri（发送成功后删除）。 */
    val localUri: String? = null,
    /** 待发表情包：搜索结果 URL（服务端下载后换成 mediaId）。 */
    val stickerUrl: String? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_STICKER = "sticker"
    }
}

fun ChatMessage.toEntity(pendingState: String? = null) = ChatMessageEntity(
    id = id,
    senderId = senderId,
    text = text,
    replyTo = replyTo,
    clientTs = clientTs,
    createdAt = createdAt.takeIf { pendingState == null && it > 0 },
    sortAt = if (pendingState == null && createdAt > 0) createdAt else clientTs,
    recalledAt = recalledAt,
    pendingState = pendingState,
    type = type,
    mediaId = mediaId,
    width = width,
    height = height,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY sortAt ASC, id ASC")
    fun observeMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun message(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query(
        "UPDATE chat_messages SET text = :text, createdAt = :createdAt, sortAt = :sortAt, " +
            "recalledAt = :recalledAt, pendingState = NULL, mediaId = :mediaId, width = :width, height = :height, " +
            "localUri = NULL, stickerUrl = NULL WHERE id = :id",
    )
    suspend fun confirm(
        id: String, text: String, createdAt: Long, sortAt: Long, recalledAt: Long?,
        mediaId: String? = null, width: Int? = null, height: Int? = null,
    )

    /** 图片已上传、消息还没 POST 成功：记住 mediaId，重试时不再重复上传。 */
    @Query("UPDATE chat_messages SET mediaId = :mediaId, width = :width, height = :height WHERE id = :id")
    suspend fun setUploadedMedia(id: String, mediaId: String, width: Int?, height: Int?)

    @Query("UPDATE chat_messages SET text = '', recalledAt = :recalledAt, mediaId = NULL WHERE id = :id")
    suspend fun markRecalled(id: String, recalledAt: Long)

    @Query("UPDATE chat_messages SET pendingState = :state WHERE id = :id")
    suspend fun updatePendingState(id: String, state: String?)

    @Query("SELECT id FROM chat_messages WHERE pendingState IS NULL ORDER BY sortAt DESC, id DESC LIMIT 1")
    suspend fun newestConfirmedId(): String?

    @Query("SELECT id FROM chat_messages WHERE pendingState IS NULL ORDER BY sortAt ASC, id ASC LIMIT 1")
    suspend fun oldestConfirmedId(): String?

    @Query("SELECT COUNT(*) FROM chat_messages WHERE pendingState IS NULL")
    suspend fun confirmedCount(): Int

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

/** 列表/通知/引用里的一行预览。 */
fun chatPreview(type: String, text: String): String = when (type) {
    ChatMessageEntity.TYPE_IMAGE -> "[图片]"
    ChatMessageEntity.TYPE_STICKER -> "[表情]"
    else -> text
}

fun ChatMessageEntity.preview(): String = chatPreview(type, text)
