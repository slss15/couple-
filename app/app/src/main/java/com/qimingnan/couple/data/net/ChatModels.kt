package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** contract "Chat" 的模型（与后端 V7 对齐）。 */

@Serializable
data class ChatMessage(
    val id: String,
    val coupleId: String = "",
    val senderId: String,
    val type: String = "text",
    val text: String,
    val replyTo: String? = null,
    val clientTs: Long = 0,
    val createdAt: Long = 0,
    val recalledAt: Long? = null,
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** GET /chat/stickers/search 的一项。 */
@Serializable
data class StickerResult(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class ChatPage(val messages: List<ChatMessage> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class SendMessageRequest(
    val id: String,
    val text: String,
    val replyTo: String? = null,
    val clientTs: Long,
    val type: String = "text",
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val stickerUrl: String? = null,
)

@Serializable
data class ChatReadRequest(val upToId: String)

@Serializable
data class ChatReadState(val myReadUpTo: String? = null, val partnerReadUpTo: String? = null)

/** couple/{cid}/chat 的服务端推送事件（MQTT 或演示进程内流）。 */
sealed interface ChatEvent {
    data class Message(val message: ChatMessage) : ChatEvent

    data class Recall(val id: String, val recalledAt: Long) : ChatEvent

    data class Read(val userId: String, val upToId: String, val ts: Long) : ChatEvent

    companion object {
        private val json = com.qimingnan.couple.core.CoupleJson

        private fun kotlinx.serialization.json.JsonElement?.str(): String? = (this as? JsonPrimitive)?.content

        private fun kotlinx.serialization.json.JsonElement?.long(): Long? = (this as? JsonPrimitive)?.content?.toLongOrNull()

        /** 解析失败返回 null（推送只是提示，坏载荷静默丢弃，补拉兜底）。 */
        fun parse(payload: String): ChatEvent? = runCatching {
            val obj = json.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject ?: return null
            when (obj["kind"].str()) {
                "message" -> obj["message"]?.toString()
                    ?.let { Message(json.decodeFromString(ChatMessage.serializer(), it)) }
                "recall" -> {
                    val id = obj["id"].str() ?: return null
                    Recall(id, obj["recalledAt"].long() ?: 0)
                }
                "read" -> {
                    val userId = obj["userId"].str() ?: return null
                    val upToId = obj["upToId"].str() ?: return null
                    Read(userId, upToId, obj["ts"].long() ?: 0)
                }
                else -> null
            }
        }.getOrNull()
    }
}
