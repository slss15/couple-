package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.mqtt.MqttTopics
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttHookTest {

    @Test
    fun topicRules() {
        assertTrue(MqttTopics.allowed("couple/C1/presence", "C1"))
        assertTrue(MqttTopics.allowed("couple/C1/#", "C1"))
        assertFalse(MqttTopics.allowed("couple/C2/presence", "C1"))
        assertFalse(MqttTopics.allowed("couple/+/presence", "C1"))
        assertFalse(MqttTopics.allowed("couple/#", "C1"))
        assertFalse(MqttTopics.allowed("#", "C1"))
        assertFalse(MqttTopics.allowed("\$share/g/couple/C1/presence", "C1"))
        assertFalse(MqttTopics.allowed("couple/C1/presence", null))
    }

    @Test
    fun authAndAclHooks() = appTest(testConfig(mqttHookSecret = "hook-secret")) { client ->
        val a = client.register("alice")
        val b = client.register("bob")
        val code = client.postJson("/api/v1/pair/code", token = a.accessToken).json()["code"]!!.jsonPrimitive.content
        val cid = client.postJson("/api/v1/pair/join", obj("code" to code), b.accessToken).json()["id"]!!.jsonPrimitive.content

        suspend fun hook(path: String, body: kotlinx.serialization.json.JsonObject, secret: String? = "hook-secret", viaCf: Boolean = false) =
            client.post("/api/v1/internal/mqtt/$path") {
                contentType(ContentType.Application.Json)
                if (secret != null) header("X-Internal-Token", secret)
                if (viaCf) header("CF-Connecting-IP", "1.2.3.4")
                setBody(body)
            }

        // Auth: valid token for the same user
        val ok = hook("auth", obj("username" to a.user.id, "password" to a.accessToken, "clientid" to "c1"))
        assertEquals(200, ok.status.value)
        assertEquals("allow", ok.json()["result"]!!.jsonPrimitive.content)
        // Token of another user
        assertEquals("deny", hook("auth", obj("username" to b.user.id, "password" to a.accessToken)).json()["result"]!!.jsonPrimitive.content)
        assertEquals("deny", hook("auth", obj("username" to a.user.id, "password" to "garbage")).json()["result"]!!.jsonPrimitive.content)
        // Guards
        assertEquals(404, hook("auth", obj("username" to a.user.id, "password" to a.accessToken), secret = null).status.value)
        assertEquals(404, hook("auth", obj("username" to a.user.id, "password" to a.accessToken), secret = "wrong").status.value)
        assertEquals(404, hook("auth", obj("username" to a.user.id, "password" to a.accessToken), viaCf = true).status.value)

        // ACL
        fun acl(user: String, topic: String, action: String = "subscribe") = obj("username" to user, "topic" to topic, "action" to action)
        assertEquals("allow", hook("acl", acl(a.user.id, "couple/$cid/presence", "publish")).json()["result"]!!.jsonPrimitive.content)
        assertEquals("allow", hook("acl", acl(b.user.id, "couple/$cid/presence")).json()["result"]!!.jsonPrimitive.content)
        assertEquals("deny", hook("acl", acl(a.user.id, "couple/OTHER/presence")).json()["result"]!!.jsonPrimitive.content)
        assertEquals("deny", hook("acl", acl(a.user.id, "couple/+/presence")).json()["result"]!!.jsonPrimitive.content)

        // Unpaired user gets nothing
        val c = client.register("carol")
        assertEquals("deny", hook("acl", acl(c.user.id, "couple/$cid/presence")).json()["result"]!!.jsonPrimitive.content)
    }
}
