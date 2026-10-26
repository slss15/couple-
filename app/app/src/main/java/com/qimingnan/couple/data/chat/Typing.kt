package com.qimingnan.couple.data.chat

import com.qimingnan.couple.core.CoupleJson
import kotlinx.serialization.Serializable

/** couple/{cid}/typing 载荷（contract "Chat" §正在输入；QoS 0、不保留，客户端可发布/订阅）。 */
@Serializable
data class TypingEvent(val userId: String, val typing: Boolean, val ts: Long) {
    fun toJson(): String = CoupleJson.encodeToString(serializer(), this)

    companion object {
        fun parse(payload: String): TypingEvent? =
            runCatching { CoupleJson.decodeFromString(serializer(), payload) }.getOrNull()
    }
}

/**
 * 发送方节流：输入变化时最多每 [throttleMs] 发一次 typing=true；停止（发送/清空/空闲 [idleMs]）时
 * 仅在此前发过 true 的情况下发一次 typing=false。纯逻辑，便于单测。
 */
class TypingThrottle(private val throttleMs: Long = THROTTLE_MS) {
    private var lastTrueAt: Long? = null

    /** 文本变化时调用；返回 true 表示现在应发布 typing=true。 */
    fun onTextChanged(now: Long): Boolean {
        val last = lastTrueAt
        if (last != null && now - last < throttleMs) return false
        lastTrueAt = now
        return true
    }

    /** 停止输入时调用；返回 true 表示应发布 typing=false。 */
    fun onStopped(): Boolean {
        val had = lastTrueAt != null
        lastTrueAt = null
        return had
    }

    companion object {
        const val THROTTLE_MS = 3_000L
        const val IDLE_MS = 5_000L
        /** 接收方：最后一次 typing=true 超过这个时长即视为已停止（丢了 false 也不会一直显示）。 */
        const val STALE_MS = 6_000L
    }
}
