package com.qimingnan.couple.data.chat

import com.qimingnan.couple.data.db.ChatMessageEntity

/** 已读回执的纯逻辑（便于 JVM 单测）：我的消息中位置 ≤ 对方已读游标的最后一条。 */
object ChatReadReceipts {
    fun lastMineReadId(rows: List<ChatMessageEntity>, partnerReadUpTo: String?, selfId: String?): String? {
        if (partnerReadUpTo == null || selfId == null) return null
        val index = rows.indexOfFirst { it.id == partnerReadUpTo }
        if (index < 0) return null
        return rows.subList(0, index + 1).lastOrNull { it.senderId == selfId && it.pendingState == null }?.id
    }
}
