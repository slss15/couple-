package com.qimingnan.couple.chat

import com.qimingnan.couple.data.chat.ChatReadReceipts
import com.qimingnan.couple.data.db.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatReadReceiptsTest {

    private fun msg(id: String, sender: String, pending: String? = null) = ChatMessageEntity(
        id = id, senderId = sender, text = id, replyTo = null, clientTs = 0, createdAt = 0,
        sortAt = 0, recalledAt = null, pendingState = pending,
    )

    private val rows = listOf(
        msg("m1", "me"),
        msg("m2", "ta"),
        msg("m3", "me", pending = "PENDING"),
        msg("m4", "ta"),
        msg("m5", "me"),
    )

    @Test fun `last own confirmed message at or before the partner cursor`() {
        // Cursor on a partner message: the previous own message is m1 (m3 is still pending).
        assertEquals("m1", ChatReadReceipts.lastMineReadId(rows, "m4", "me"))
        // Cursor on my own message includes it.
        assertEquals("m5", ChatReadReceipts.lastMineReadId(rows, "m5", "me"))
        assertEquals("m1", ChatReadReceipts.lastMineReadId(rows, "m2", "me"))
    }

    @Test fun `no receipt without a known cursor or self`() {
        assertNull(ChatReadReceipts.lastMineReadId(rows, null, "me"))
        assertNull(ChatReadReceipts.lastMineReadId(rows, "unknown", "me"))
        assertNull(ChatReadReceipts.lastMineReadId(rows, "m5", null))
        // Cursor before my first message: nothing of mine is covered.
        val taFirst = listOf(msg("t1", "ta"), msg("m1", "me"))
        assertNull(ChatReadReceipts.lastMineReadId(taFirst, "t1", "me"))
    }
}
