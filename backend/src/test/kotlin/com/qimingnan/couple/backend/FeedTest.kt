package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headers
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1 Feed (contract "Feed" + "Media storage"): upload, posts, likes, comments, ACL, paging, orphan sweep. */
class FeedTest {

    private suspend fun HttpClient.pairTwo(a: String, b: String): Pair<TokenPair, TokenPair> {
        val ta = register(a)
        val tb = register(b)
        val code = postJson("/api/v1/pair/code", null, ta.accessToken).json()["code"]!!.jsonPrimitive.content
        postJson("/api/v1/pair/join", obj("code" to code), tb.accessToken)
        return ta to tb
    }

    private suspend fun HttpClient.upload(token: String, name: String, mime: String, bytes: ByteArray): JsonObject =
        post("/api/v1/media") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData {
                append("file", bytes, headers {
                    append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                    append(HttpHeaders.ContentType, mime)
                })
            }))
        }.json()

    private suspend fun HttpClient.loginAgain(username: String): TokenPair {
        val r = postJson(
            "/api/v1/auth/login",
            obj("username" to username, "password" to "password-123", "deviceId" to "dev-$username", "deviceName" to "T"),
        )
        assertEquals(200, r.status.value, r.bodyAsText())
        return AppJson.decodeFromString(TokenPair.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.getMedia(token: String, key: String, thumb: Boolean = false): HttpResponse =
        get("/api/v1/media/$key" + if (thumb) "/thumb" else "") { bearerAuth(token) }

    private suspend fun HttpClient.createPost(token: String, id: String, text: String, mediaKeys: List<String> = emptyList()): HttpResponse =
        postJson(
            "/api/v1/feed/posts",
            obj("id" to id, "text" to text, "mediaKeys" to kotlinx.serialization.json.JsonArray(mediaKeys.map { JsonPrimitive(it) })),
            token,
        )

    private suspend fun HttpClient.listPosts(token: String, before: String? = null, limit: Int? = null): JsonObject {
        val params = mutableListOf<String>()
        if (before != null) params += "before=$before"
        if (limit != null) params += "limit=$limit"
        val q = "/api/v1/feed/posts" + (if (params.isEmpty()) "" else "?" + params.joinToString("&"))
        val r = get(q) { bearerAuth(token) }
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.json()
    }

    /** Solid RGB image, decodable by ImageIO. */
    private fun imageBytes(format: String, w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 31 + y * 7) and 0xFFFFFF)
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, format, out), "ImageIO.write $format failed")
        return out.toByteArray()
    }

    private fun ulid(n: Int): String {
        // Unique ULID-shaped id per call (Crockford-base32).
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var v = System.nanoTime() + n * 7919L
        val sb = StringBuilder("01")
        while (sb.length < 26) { sb.append(alphabet[(((v % 32).toInt()) + 32) % 32]); v /= 32 }
        return sb.toString()
    }

    private fun realString(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    // ---------------- upload validation ----------------

    @Test
    fun uploadRequiresPairAndValidates() = appTest { client ->
        val solo = client.register("solo-feed")
        val unpaired = client.post("/api/v1/media") {
            bearerAuth(solo.accessToken)
            setBody(MultiPartFormDataContent(formData {
                append("file", imageBytes("png", 10, 10), headers { append(HttpHeaders.ContentDisposition, "filename=\"a.png\"") })
            }))
        }
        assertEquals(403, unpaired.status.value, unpaired.bodyAsText())
        assertEquals("NOT_PAIRED", unpaired.errorCode())

        val (alice, _) = client.pairTwo("feed-alice", "feed-bob")
        val badMime = client.post("/api/v1/media") {
            bearerAuth(alice.accessToken)
            setBody(MultiPartFormDataContent(formData {
                append("file", "hello".toByteArray(), headers {
                    append(HttpHeaders.ContentDisposition, "filename=\"x.txt\"")
                    append(HttpHeaders.ContentType, "text/plain")
                })
            }))
        }
        assertEquals(400, badMime.status.value, badMime.bodyAsText())
        assertEquals("VALIDATION_ERROR", badMime.errorCode())

        val garbage = client.post("/api/v1/media") {
            bearerAuth(alice.accessToken)
            setBody(MultiPartFormDataContent(formData {
                append("file", "not an image".toByteArray(), headers {
                    append(HttpHeaders.ContentDisposition, "filename=\"y.png\"")
                    append(HttpHeaders.ContentType, "image/png")
                })
            }))
        }
        assertEquals(400, garbage.status.value, garbage.bodyAsText())
    }

    // ---------------- post lifecycle + ACL ----------------

    @Test
    fun postLifecycleLikesCommentsDelete() = appTest { client ->
        val (alice, bob) = client.pairTwo("lc-alice", "lc-bob")
        val m1 = client.upload(alice.accessToken, "a.png", "image/png", imageBytes("png", 64, 48))
        val m2 = client.upload(alice.accessToken, "b.png", "image/png", imageBytes("png", 48, 64))
        assertEquals(64, m1["width"]!!.jsonPrimitive.int)
        assertEquals(48, m1["height"]!!.jsonPrimitive.int)
        assertTrue(m1["thumbKey"] is JsonNull, "small image needs no thumbnail")

        val id = ulid(1)
        val created = client.createPost(
            alice.accessToken, id, "第一张",
            listOf(realString(m1, "storageKey")!!, realString(m2, "storageKey")!!),
        )
        assertEquals(200, created.status.value, created.bodyAsText())
        val post = created.json()
        assertEquals(2, post["media"]!!.jsonArray.size)
        assertEquals(0, post["likes"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(0, post["commentCount"]!!.jsonPrimitive.int)

        // Idempotent re-POST returns the stored post (200).
        val again = client.createPost(alice.accessToken, id, "第一张", listOf(realString(m1, "storageKey")!!))
        assertEquals(200, again.status.value, again.bodyAsText())

        // Partner sees it in the list.
        val page = client.listPosts(bob.accessToken)
        assertEquals(1, page["posts"]!!.jsonArray.size)
        val viewed = page["posts"]!!.jsonArray[0].jsonObject
        assertEquals(alice.user.id, viewed["authorId"]!!.jsonPrimitive.content)
        assertEquals(false, viewed["likes"]!!.jsonObject["likedByMe"]!!.jsonPrimitive.boolean)

        // Likes: bob, alice, bob again (no-op), alice unlikes.
        assertEquals(1, client.postJson("/api/v1/feed/posts/$id/like", null, bob.accessToken).json()["count"]!!.jsonPrimitive.int)
        assertEquals(2, client.postJson("/api/v1/feed/posts/$id/like", null, alice.accessToken).json()["count"]!!.jsonPrimitive.int)
        assertEquals(2, client.postJson("/api/v1/feed/posts/$id/like", null, bob.accessToken).json()["count"]!!.jsonPrimitive.int)
        assertEquals(1, client.delete("/api/v1/feed/posts/$id/like") { bearerAuth(alice.accessToken) }.json()["count"]!!.jsonPrimitive.int)

        // Comments: bob comments (idempotent on client id), then deletes his own.
        val cid = ulid(2)
        val c1 = client.postJson("/api/v1/feed/posts/$id/comments", obj("id" to cid, "text" to " 好看！ "), bob.accessToken)
        assertEquals(200, c1.status.value, c1.bodyAsText())
        assertEquals("好看！", c1.json()["text"]!!.jsonPrimitive.content)
        assertEquals(
            "好看！",
            client.postJson("/api/v1/feed/posts/$id/comments", obj("id" to cid, "text" to "好看！"), bob.accessToken).json()["text"]!!.jsonPrimitive.content,
        )
        assertEquals(1, client.listPosts(alice.accessToken)["posts"]!!.jsonArray[0].jsonObject["commentCount"]!!.jsonPrimitive.int)

        // Alice cannot delete bob's comment; bob can.
        assertEquals(403, client.delete("/api/v1/feed/comments/$cid") { bearerAuth(alice.accessToken) }.status.value)
        assertEquals(204, client.delete("/api/v1/feed/comments/$cid") { bearerAuth(bob.accessToken) }.status.value)

        // Media reads: partner yes.
        val key = realString(m1, "storageKey")!!
        assertEquals(200, client.getMedia(bob.accessToken, key).status.value)

        // Deletion: partner (non-author) is refused, author succeeds, media disappears.
        assertEquals(403, client.delete("/api/v1/feed/posts/$id") { bearerAuth(bob.accessToken) }.status.value)
        assertEquals(204, client.delete("/api/v1/feed/posts/$id") { bearerAuth(alice.accessToken) }.status.value)
        assertEquals(0, client.listPosts(bob.accessToken)["posts"]!!.jsonArray.size)
        assertEquals(404, client.getMedia(bob.accessToken, key).status.value)
    }

    // ---------------- cross-couple validation ----------------

    @Test
    fun postValidationsAcrossCouples() = appTest { client ->
        val (alice, _) = client.pairTwo("v-alice", "v-bob")
        val (carol, _) = client.pairTwo("v-carol", "v-dave")
        val m = client.upload(alice.accessToken, "a.png", "image/png", imageBytes("png", 40, 40))
        val key = realString(m, "storageKey")!!

        assertEquals("VALIDATION_ERROR", client.createPost(alice.accessToken, ulid(10), "", emptyList()).errorCode())
        assertEquals("VALIDATION_ERROR", client.createPost(alice.accessToken, "not-a-ulid", "hi", emptyList()).errorCode())
        assertEquals("VALIDATION_ERROR", client.createPost(alice.accessToken, ulid(11), "x", listOf("0".repeat(26))).errorCode())
        assertEquals("VALIDATION_ERROR", client.createPost(carol.accessToken, ulid(12), "steal", listOf(key)).errorCode())

        // A different couple reusing an existing post id → 409 ID_CONFLICT.
        val alicePost = ulid(13)
        assertEquals(200, client.createPost(alice.accessToken, alicePost, "mine").status.value)
        assertEquals(409, client.createPost(carol.accessToken, alicePost, "other couple same id").status.value)

        // Media can be used by only one post ever.
        assertEquals(200, client.createPost(alice.accessToken, ulid(14), "first", listOf(key)).status.value)
        assertEquals("MEDIA_ALREADY_USED", client.createPost(alice.accessToken, ulid(15), "second", listOf(key)).errorCode())
    }

    @Test
    fun nineMediaLimit() = appTest { client ->
        val (alice, _) = client.pairTwo("nine-alice", "nine-bob")
        val keys = (1..10).map {
            realString(client.upload(alice.accessToken, "a.png", "image/png", imageBytes("png", 20, 20)), "storageKey")!!
        }
        assertEquals("VALIDATION_ERROR", client.createPost(alice.accessToken, ulid(20), "ten", keys).errorCode())
        assertEquals(200, client.createPost(alice.accessToken, ulid(21), "nine", keys.take(9)).status.value)
    }

    // ---------------- thumbnails + avatar + ACL ----------------

    @Test
    fun thumbnailGeneratedForLargeImages() = appTest { client ->
        val (alice, bob) = client.pairTwo("thumb-alice", "thumb-bob")
        val big = client.upload(alice.accessToken, "big.jpg", "image/jpeg", imageBytes("jpg", 800, 600))
        val thumbKey = realString(big, "thumbKey")
        assertTrue(!thumbKey.isNullOrEmpty(), "800x600 must get a thumbnail")
        assertEquals(200, client.getMedia(bob.accessToken, realString(big, "storageKey")!!).status.value)
        val thumbResp = client.getMedia(bob.accessToken, realString(big, "storageKey")!!, thumb = true)
        assertEquals(200, thumbResp.status.value, thumbResp.bodyAsText())
        // The thumbnail's own key is also accepted as the path parameter.
        assertEquals(200, client.getMedia(bob.accessToken, thumbKey, thumb = true).status.value, "thumbKey as path param works too")

        val small = client.upload(bob.accessToken, "small.png", "image/png", imageBytes("png", 100, 80))
        assertTrue(small["thumbKey"] is JsonNull, "small image needs no thumbnail")
    }

    @Test
    fun avatarUploadReadAndReplace() = appTest { client ->
        val (alice, bob) = client.pairTwo("av-alice", "av-bob")
        val (carol, _) = client.pairTwo("av-carol", "av-dave")

        val a1 = realString(client.upload(alice.accessToken, "me.png", "image/png", imageBytes("png", 32, 32)), "storageKey")!!
        assertEquals(204, client.postJson("/api/v1/users/me/avatar", obj("storageKey" to a1), alice.accessToken).status.value)

        val couple = client.get("/api/v1/couple") { bearerAuth(bob.accessToken) }.json()
        val aliceMember = couple["members"]!!.jsonArray.map { it.jsonObject }.first { it["username"]!!.jsonPrimitive.content == "av-alice" }
        assertEquals("/api/v1/media/$a1", aliceMember["avatarUrl"]!!.jsonPrimitive.content)
        assertEquals(200, client.getMedia(bob.accessToken, a1).status.value, "partner reads the avatar")
        assertEquals(403, client.getMedia(carol.accessToken, a1).status.value, "outsider cannot read the avatar")

        // Replacing deletes the previous avatar object.
        val a2 = realString(client.upload(alice.accessToken, "me2.png", "image/png", imageBytes("png", 33, 33)), "storageKey")!!
        assertEquals(204, client.postJson("/api/v1/users/me/avatar", obj("storageKey" to a2), alice.accessToken).status.value)
        assertEquals(404, client.getMedia(bob.accessToken, a1).status.value)
        assertEquals(200, client.getMedia(bob.accessToken, a2).status.value)

        // Carol cannot make alice's upload her avatar.
        assertEquals(403, client.postJson("/api/v1/users/me/avatar", obj("storageKey" to a2), carol.accessToken).status.value)
    }

    // ---------------- paging + orphan sweep ----------------

    @Test
    fun pagingBeforeLimit() = appTest { client ->
        val (alice, _) = client.pairTwo("pg-alice", "pg-bob")
        // (createdAt, id) of every created post, from the server — the sort key the list uses.
        val created = (1..3).map { n ->
            val id = ulid(30 + n)
            val r = client.createPost(alice.accessToken, id, "p$n")
            assertEquals(200, r.status.value, "post $n")
            val body = r.json()
            body["createdAt"]!!.jsonPrimitive.long to body["id"]!!.jsonPrimitive.content
        }.sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenByDescending { it.second })

        val page1 = client.listPosts(alice.accessToken, limit = 2)
        assertEquals(2, page1["posts"]!!.jsonArray.size)
        assertEquals(true, page1["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(created[0].second, page1["posts"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content, "newest first")

        val page2 = client.listPosts(
            alice.accessToken,
            before = page1["posts"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content,
            limit = 2,
        )
        assertEquals(1, page2["posts"]!!.jsonArray.size)
        assertEquals(false, page2["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(created[2].second, page2["posts"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun orphanSweepDeletesOnlyUnreferencedOldUploads() {
        val clock = TestClock()
        appTest(testConfig(clock = clock)) { client ->
            val admin = client.register("sweep-admin3")
            val (alice, _) = client.pairTwo("sw-alice", "sw-bob")

            // Uploaded "now": referenced by a post (kept) and unreferenced (candidate).
            val used = realString(client.upload(alice.accessToken, "a.png", "image/png", imageBytes("png", 20, 20)), "storageKey")!!
            val orphan = realString(client.upload(alice.accessToken, "b.png", "image/png", imageBytes("png", 20, 20)), "storageKey")!!
            assertEquals(200, client.createPost(alice.accessToken, ulid(40), "with media", listOf(used)).status.value)

            clock.advance(25 * 60 * 60_000L)
            // The 15-minute access tokens are long expired on the (test) clock — log in again.
            val alice2 = client.loginAgain("sw-alice").accessToken
            val admin2 = client.loginAgain("sweep-admin3").accessToken
            val fresh = realString(client.upload(alice2, "c.png", "image/png", imageBytes("png", 20, 20)), "storageKey")!!

            val r = client.postJson("/api/v1/admin/media/sweep", null, admin2)
            assertEquals(200, r.status.value, r.bodyAsText())
            val deleted = r.json()["deleted"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(orphan), deleted)

            assertEquals(200, client.getMedia(alice2, used).status.value, "referenced upload survives")
            assertEquals(200, client.getMedia(alice2, fresh).status.value, "fresh upload survives")
            assertEquals(404, client.getMedia(alice2, orphan).status.value, "orphan is gone")
        }
    }
}
