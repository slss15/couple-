package com.qimingnan.couple.chat

import com.qimingnan.couple.data.chat.ChatReadReceipts
import com.qimingnan.couple.data.chat.ChatRepository
import com.qimingnan.couple.data.chat.ReceiptStatus
import com.qimingnan.couple.data.chat.ReceiptStatus.FAILED
import com.qimingnan.couple.data.chat.ReceiptStatus.READ
import com.qimingnan.couple.data.chat.ReceiptStatus.SENDING
import com.qimingnan.couple.data.chat.ReceiptStatus.UNREAD
import com.qimingnan.couple.data.db.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadReceiptsTest {

    private fun msg(id: String, sender: String, at: Long, pending: String? = null) = ChatMessageEntity(
        id = id, senderId = sender, text = id, replyTo = null, clientTs = at,
        createdAt = if (pending == null) at else null, sortAt = at, recalledAt = null, pendingState = pending,
    )

    private val rows = listOf(
        msg("m1", "me", 100),
        msg("m2", "me", 200),
        msg("t3", "ta", 300),
        msg("m4", "me", 400),
        msg("m5", "me", 500),
        msg("m6", "me", 600, pending = ChatRepository.PENDING),
        msg("m7", "me", 700, pending = ChatRepository.FAILED),
    )

    private fun st(cursor: String?, list: List<ChatMessageEntity> = rows) = ChatReadReceipts.statuses(list, cursor, "me")

    @Test fun `every own message at or before the cursor is read, newer ones unread`() {
        assertEquals(
            mapOf("m1" to READ, "m2" to READ, "m4" to UNREAD, "m5" to UNREAD, "m6" to SENDING, "m7" to FAILED),
            st("t3"),
        )
        // Cursor on my own message includes it and everything before it, not only the newest.
        assertEquals(
            mapOf("m1" to READ, "m2" to READ, "m4" to READ, "m5" to UNREAD, "m6" to SENDING, "m7" to FAILED),
            st("m4"),
        )
    }

    @Test fun `ties on createdAt are broken by id`() {
        val tie = listOf(msg("a1", "me", 100), msg("a2", "ta", 100), msg("a3", "me", 100))
        assertEquals(mapOf("a1" to READ, "a3" to UNREAD), st("a2", tie))
    }

    @Test fun `no cursor means all confirmed own messages are unread`() {
        val s = st(null)
        assertEquals(UNREAD, s["m1"]); assertEquals(UNREAD, s["m5"])
        assertEquals(SENDING, s["m6"]); assertEquals(FAILED, s["m7"])
        assertTrue("partner messages carry no status", "t3" !in s)
    }

    @Test fun `cursor outside the loaded page falls back to ULID order`() {
        val ulids = listOf(msg("01A", "me", 100), msg("01C", "me", 300))
        assertEquals(mapOf<String, ReceiptStatus>("01A" to READ, "01C" to UNREAD), st("01B", ulids))
    }

    @Test fun `no self id gives nothing`() {
        assertTrue(ChatReadReceipts.statuses(rows, "m5", null).isEmpty())
    }
}
