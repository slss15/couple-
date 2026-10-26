package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable

/** contract "Media storage" / "Feed" 的模型（与后端 V5 对齐）。 */

@Serializable
data class MediaView(
    val storageKey: String,
    val thumbKey: String? = null,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
)

@Serializable
data class LikesView(val count: Int = 0, val likedByMe: Boolean = false)

@Serializable
data class CommentView(
    val id: String,
    val postId: String,
    val authorId: String,
    val text: String,
    val createdAt: Long,
)

@Serializable
data class PostView(
    val id: String,
    val coupleId: String,
    val authorId: String,
    val text: String,
    val media: List<MediaView> = emptyList(),
    val createdAt: Long,
    val likes: LikesView = LikesView(),
    val comments: List<CommentView> = emptyList(),
    val commentCount: Int = 0,
)

@Serializable
data class FeedPage(val posts: List<PostView> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class CreatePostRequest(val id: String, val text: String = "", val mediaKeys: List<String> = emptyList())

@Serializable
data class AddCommentRequest(val id: String, val text: String)

@Serializable
data class LikeCountView(val count: Int = 0)

@Serializable
data class AvatarRequest(val storageKey: String)

/** couple/{cid}/feed 的服务端推送事件（MQTT 或演示进程内流）。 */
sealed interface FeedEvent {
    @Serializable
    data class Post(val post: PostView) : FeedEvent

    @Serializable
    data class Delete(val id: String) : FeedEvent

    @Serializable
    data class Like(val postId: String, val userId: String, val count: Int) : FeedEvent

    @Serializable
    data class Comment(val postId: String, val comment: CommentView) : FeedEvent

    @Serializable
    data class CommentDelete(val postId: String, val id: String) : FeedEvent

    companion object {
        private val json = com.qimingnan.couple.core.CoupleJson

        private fun kotlinx.serialization.json.JsonElement?.str(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)?.content

        /** kind 分发；解析失败返回 null（推送只是提示，坏载荷静默丢弃，补拉兜底）。 */
        fun parse(payload: String): FeedEvent? = runCatching {
            val obj = json.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject ?: return null
            when (obj["kind"].str()) {
                "post" -> obj["post"]?.toString()?.let { Post(json.decodeFromString(PostView.serializer(), it)) }
                "delete" -> obj["id"].str()?.let(::Delete)
                "like" -> {
                    val postId = obj["postId"].str() ?: return null
                    Like(postId, obj["userId"].str() ?: "", obj["count"]?.str()?.toIntOrNull() ?: 0)
                }
                "comment" -> {
                    val postId = obj["postId"].str() ?: return null
                    val c = obj["comment"]?.toString() ?: return null
                    Comment(postId, json.decodeFromString(CommentView.serializer(), c))
                }
                "comment_delete" -> {
                    val postId = obj["postId"].str() ?: return null
                    val id = obj["id"].str() ?: return null
                    CommentDelete(postId, id)
                }
                else -> null
            }
        }.getOrNull()
    }
}
