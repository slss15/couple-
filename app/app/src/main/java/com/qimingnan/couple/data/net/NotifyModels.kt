package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable

/** contract "Chat → WxPusher reminders"：GET / PUT /me/notify。 */
@Serializable
data class NotifySettings(
    /** WxPusher UID（UID_ 开头）；null = 不提醒。 */
    val wxpusherUid: String? = null,
    /** true = 提醒里带消息内容预览；false = 只显示「TA 发来一条消息」。 */
    val preview: Boolean = false,
)

object NotifyUid {
    private val RE = Regex("^UID_[A-Za-z0-9_-]{1,60}$")

    /** 去掉首尾空白；空串 = 清除（null）。 */
    fun normalize(input: String): String? = input.trim().ifEmpty { null }

    /** 空（清除）或形如 UID_xxx 才合法，与后端校验一致。 */
    fun isValid(input: String): Boolean = normalize(input)?.let { RE.matches(it) } ?: true
}
