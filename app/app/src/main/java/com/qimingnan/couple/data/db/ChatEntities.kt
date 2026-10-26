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
)

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
            "recalledAt = :recalledAt, pendingState = NULL WHERE id = :id",
    )
    suspend fun confirm(id: String, text: String, createdAt: Long, sortAt: Long, recalledAt: Long?)

    @Query("UPDATE chat_messages SET text = '', recalledAt = :recalledAt WHERE id = :id")
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
