package com.qimingnan.couple.data.chat

import com.qimingnan.couple.data.db.ChatMessageEntity

/** 我方消息气泡下的状态。 */
enum class ReceiptStatus { SENDING, FAILED, READ, UNREAD }

/**
 * 已读回执的纯逻辑（便于 JVM 单测）。对方已读游标 partnerReadUpTo 是一条消息 id；我的每条已确认消息
 * 只要 (createdAt, id) ≤ 游标消息的 (createdAt, id) 就是「已读」，更新的为「未读」。
 * 未确认的显示「发送中」/「失败」，不参与已读判断。
 */
object ChatReadReceipts {
    /** 我方消息 id → 状态（对方消息不在结果中）。 */
    fun statuses(rows: List<ChatMessageEntity>, partnerReadUpTo: String?, selfId: String?): Map<String, ReceiptStatus> {
        if (selfId == null) return emptyMap()
        val cursorRow = partnerReadUpTo?.let { id -> rows.firstOrNull { it.id == id } }
        val out = HashMap<String, ReceiptStatus>()
        for (row in rows) {
            if (row.senderId != selfId) continue
            out[row.id] = when (row.pendingState) {
                ChatRepository.PENDING -> ReceiptStatus.SENDING
                ChatRepository.FAILED -> ReceiptStatus.FAILED
                null -> if (isRead(row, partnerReadUpTo, cursorRow)) ReceiptStatus.READ else ReceiptStatus.UNREAD
                else -> ReceiptStatus.SENDING
            }
        }
        return out
    }

    private fun isRead(row: ChatMessageEntity, cursorId: String?, cursorRow: ChatMessageEntity?): Boolean {
        if (cursorId == null) return false
        val rowAt = row.createdAt ?: row.sortAt
        if (cursorRow == null) {
            // 游标消息不在本地已加载范围：消息 id 是 ULID（按时间可排序），退回按 id 比较。
            return row.id <= cursorId
        }
        val cursorAt = cursorRow.createdAt ?: cursorRow.sortAt
        return rowAt < cursorAt || (rowAt == cursorAt && row.id <= cursorRow.id)
    }
}
