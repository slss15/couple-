package com.qimingnan.couple.chat

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.net.ChatEvent
import com.qimingnan.couple.data.net.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEventTest {

    @Test fun `parses message recall and read events`() {
        val message = ChatMessage(
            id = "m1", coupleId = "c1", senderId = "ta", text = "hi", clientTs = 1, createdAt = 2,
        )
        val msgPayload = """{"kind":"message","message":${CoupleJson.encodeToString(ChatMessage.serializer(), message)}}"""
        val parsed = ChatEvent.parse(msgPayload) as ChatEvent.Message
        assertEquals("m1", parsed.message.id)
        assertEquals("hi", parsed.message.text)

        val recall = ChatEvent.parse("""{"kind":"recall","id":"m1","recalledAt":123}""") as ChatEvent.Recall
        assertEquals("m1", recall.id)
        assertEquals(123, recall.recalledAt)

        val read = ChatEvent.parse("""{"kind":"read","userId":"ta","upToId":"m1","ts":99}""") as ChatEvent.Read
        assertEquals("ta", read.userId)
        assertEquals("m1", read.upToId)
        assertEquals(99, read.ts)
    }

    @Test fun `bad payloads are ignored`() {
        assertNull(ChatEvent.parse("not json"))
        assertNull(ChatEvent.parse("""{"kind":"unknown"}"""))
        assertNull(ChatEvent.parse("""{"kind":"message"}"""))
        assertNull(ChatEvent.parse("""{"kind":"read","userId":"ta"}"""))
        assertTrue(ChatEvent.parse("""{"kind":"recall","id":"m1"}""") is ChatEvent.Recall)
    }
}
