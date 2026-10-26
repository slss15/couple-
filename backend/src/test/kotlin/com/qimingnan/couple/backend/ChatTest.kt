package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.chat.ChatMessageView
import com.qimingnan.couple.backend.chat.ChatPageView
import com.qimingnan.couple.backend.chat.ChatReadStateView
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P1a chat (contract "Chat"): send/idempotency, paging, replies, recall, read cursors, NOT_PAIRED. */
class ChatTest {

    private val clock = TestClock(1_761_350_400_000L)

    private fun ulid(n: Int): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var v = 1_700_000_000_000L + n * 104_729L
        val sb = StringBuilder("01")
        while (sb.length < 26) {
            sb.append(alphabet[((v % 32).toInt() + 32) % 32])
            v /= 32
        }
        return sb.toString()
    }

    private suspend fun HttpClient.pairTwo(a: String, b: String): Triple<TokenPair, TokenPair, String> {
        val alice = register(a)
        val bob = register(b)
        val code = postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        val cid = postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken).json()["id"]!!.jsonPrimitive.content
        return Triple(alice, bob, cid)
    }

    private suspend fun HttpClient.send(token: String, id: String, text: String, replyTo: String? = null): HttpResponse =
        postJson("/api/v1/chat/messages", obj("id" to id, "text" to text, "replyTo" to replyTo, "clientTs" to clock.now()), token)

    private suspend fun HttpClient.page(token: String, query: String = ""): ChatPageView {
        val r = get("/api/v1/chat/messages$query") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ChatPageView.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.state(token: String): ChatReadStateView {
        val r = get("/api/v1/chat/state") { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(ChatReadStateView.serializer(), r.bodyAsText())
    }

    private suspend fun message(r: HttpResponse): ChatMessageView =
        AppJson.decodeFromString(ChatMessageView.serializer(), r.bodyAsText())

    @Test
    fun sendIsIdempotentAndPagesBothWays() = appTest(testConfig(clock = clock)) { client ->
        val (alice, bob, _) = client.pairTwo("chat-alice", "chat-bob")
        val m1 = ulid(1)
        val m2 = ulid(2)
        val m3 = ulid(3)

        assertEquals(200, client.send(alice.accessToken, m1, " 第一句 ").status.value)
        clock.advance(1_000)
        assertEquals(200, client.send(alice.accessToken, m2, "第二句").status.value)
        clock.advance(1_000)
        assertEquals(200, client.send(alice.accessToken, m3, "第三句").status.value)

        // Replaying an id returns the stored message unchanged (no duplicate row, no error).
        val replay = client.send(alice.accessToken, m1, "改过的内容")
        assertEquals(200, replay.status.value)
        assertEquals("第一句", message(replay).text)

        // Newest page (no before/after): ascending, hasMore = older messages exist.
        val newest = client.page(alice.accessToken, "?limit=2")
        assertEquals(listOf(m2, m3), newest.messages.map { it.id })
        assertTrue(newest.hasMore)

        // Catch-up after a known id.
        val after = client.page(bob.accessToken, "?after=$m1&limit=2")
        assertEquals(listOf(m2, m3), after.messages.map { it.id })
        assertEquals(false, after.hasMore)
        val afterOne = client.page(bob.accessToken, "?after=$m2&limit=1")
        assertEquals(listOf(m3), afterOne.messages.map { it.id })
        assertEquals(false, afterOne.hasMore)

        // Older page.
        val before = client.page(alice.accessToken, "?before=$m3&limit=2")
        assertEquals(listOf(m1, m2), before.messages.map { it.id })
        assertEquals(false, before.hasMore)
        assertTrue(client.page(alice.accessToken, "?before=$m1").messages.isEmpty())
        // With a large limit the newest page is the whole history, still ascending.
        assertEquals(listOf(m1, m2, m3), client.page(alice.accessToken, "?limit=50").messages.map { it.id })

        // Only the couple sees the messages; a different couple gets its own empty conversation.
        val (carol, dave, _) = client.pairTwo("chat-carol", "chat-dave")
        assertTrue(client.page(carol.accessToken).messages.isEmpty())
        assertTrue(client.page(dave.accessToken).messages.isEmpty())

        // Invalid queries.
        assertEquals(400, client.get("/api/v1/chat/messages?after=$m1&before=$m3") { bearerAuth(alice.accessToken) }.status.value)
        assertEquals(400, client.get("/api/v1/chat/messages?limit=abc") { bearerAuth(alice.accessToken) }.status.value)
        assertEquals(404, client.get("/api/v1/chat/messages?after=${ulid(99)}") { bearerAuth(alice.accessToken) }.status.value)
    }

    @Test
    fun validationRepliesAndIdConflict() = appTest(testConfig(clock = clock)) { client ->
        val (alice, bob, _) = client.pairTwo("chat-val-alice", "chat-val-bob")
        val id = ulid(10)

        // Blank / too long / bad id.
        assertEquals(400, client.send(alice.accessToken, id, "   ").status.value)
        assertEquals(400, client.send(alice.accessToken, "not-a-ulid", "hi").status.value)
        assertEquals(400, client.send(alice.accessToken, id, "x".repeat(4001)).status.value)

        assertEquals(200, client.send(alice.accessToken, id, "原文").status.value)
        // Same id from the partner is a conflict, not an idempotent replay.
        val conflict = client.send(bob.accessToken, id, "冒用")
        assertEquals(409, conflict.status.value)
        assertEquals("ID_CONFLICT", conflict.errorCode())

        // replyTo must be a message of the same couple.
        val replyId = ulid(11)
        val reply = client.send(bob.accessToken, replyId, "回复", replyTo = id)
        assertEquals(200, reply.status.value)
        assertEquals(id, message(reply).replyTo)
        assertEquals(400, client.send(bob.accessToken, ulid(12), "乱引用", replyTo = ulid(98)).status.value)

        // A counterpart couple's message cannot be quoted.
        val (carol, dave, _) = client.pairTwo("chat-val-carol", "chat-val-dave")
        val theirId = ulid(13)
        assertEquals(200, client.send(carol.accessToken, theirId, "别人的消息").status.value)
        assertEquals(400, client.send(dave.accessToken, ulid(14), "跨couple引用", replyTo = id).status.value)
        assertEquals(400, client.send(bob.accessToken, ulid(15), "跨couple引用", replyTo = theirId).status.value)

        // Unpaired users cannot chat.
        val solo = client.register("chat-solo")
        val denied = client.send(solo.accessToken, ulid(16), "hello")
        assertEquals(403, denied.status.value)
        assertEquals("NOT_PAIRED", denied.errorCode())
        assertEquals(403, client.get("/api/v1/chat/messages") { bearerAuth(solo.accessToken) }.status.value)
        assertEquals(403, client.get("/api/v1/chat/state") { bearerAuth(solo.accessToken) }.status.value)
    }

    @Test
    fun recallRulesAndPrivacy() = appTest(testConfig(clock = clock)) { client ->
        val (alice, bob, _) = client.pairTwo("chat-recall-alice", "chat-recall-bob")
        val m1 = ulid(20)
        assertEquals(200, client.send(alice.accessToken, m1, "说错话了").status.value)

        // Only the sender may recall.
        val forbidden = client.postJson("/api/v1/chat/messages/$m1/recall", null, bob.accessToken)
        assertEquals(403, forbidden.status.value)
        assertEquals("RECALL_FORBIDDEN", forbidden.errorCode())

        clock.advance(1_000)
        val recalled = client.postJson("/api/v1/chat/messages/$m1/recall", null, alice.accessToken)
        assertEquals(200, recalled.status.value)
        val view = message(recalled)
        assertEquals("", view.text)
        assertNotNull(view.recalledAt)
        // Idempotent.
        val again = client.postJson("/api/v1/chat/messages/$m1/recall", null, alice.accessToken)
        assertEquals(200, again.status.value)
        assertEquals(view.recalledAt, message(again).recalledAt)

        // The partner sees the recalled placeholder, never the original text.
        val seen = client.page(bob.accessToken).messages.single { it.id == m1 }
        assertEquals("", seen.text)
        assertNotNull(seen.recalledAt)

        // Past the 120 s window the server refuses.
        clock.advance(121_000)
        val late = ulid(21)
        assertEquals(200, client.send(alice.accessToken, late, "太久才想撤回").status.value)
        clock.advance(121_000)
        val expired = client.postJson("/api/v1/chat/messages/$late/recall", null, alice.accessToken)
        assertEquals(409, expired.status.value)
        assertEquals("RECALL_EXPIRED", expired.errorCode())
    }

    @Test
    fun readCursorIsMonotonicPerUser() = appTest(testConfig(clock = clock)) { client ->
        val (alice, bob, _) = client.pairTwo("chat-read-alice", "chat-read-bob")
        val m1 = ulid(30)
        val m2 = ulid(31)
        val m3 = ulid(32)
        assertEquals(200, client.send(alice.accessToken, m1, "一").status.value)
        clock.advance(1_000)
        assertEquals(200, client.send(bob.accessToken, m2, "二").status.value)
        clock.advance(1_000)
        assertEquals(200, client.send(alice.accessToken, m3, "三").status.value)

        assertEquals(204, client.postJson("/api/v1/chat/read", obj("upToId" to m1), bob.accessToken).status.value)
        assertEquals(m1, client.state(bob.accessToken).myReadUpTo)
        assertEquals(null, client.state(bob.accessToken).partnerReadUpTo)
        assertEquals(m1, client.state(alice.accessToken).partnerReadUpTo)

        // Advancing works; going back is ignored.
        assertEquals(204, client.postJson("/api/v1/chat/read", obj("upToId" to m3), bob.accessToken).status.value)
        assertEquals(m3, client.state(bob.accessToken).myReadUpTo)
        assertEquals(204, client.postJson("/api/v1/chat/read", obj("upToId" to m2), bob.accessToken).status.value)
        assertEquals(m3, client.state(bob.accessToken).myReadUpTo)

        // Unknown / missing cursor.
        assertEquals(404, client.postJson("/api/v1/chat/read", obj("upToId" to ulid(97)), bob.accessToken).status.value)
        assertEquals(400, client.postJson("/api/v1/chat/read", obj(), bob.accessToken).status.value)
    }
}
