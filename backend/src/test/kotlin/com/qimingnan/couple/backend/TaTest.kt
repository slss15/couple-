package com.qimingnan.couple.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1b TA page (contract "TA"): partner card fields, birthday privacy, shared important days. */
class TaTest {

    @Test
    fun summaryShowsPartnerAndMe() = appTest { client ->
        val (alice, bob) = Pair(client.register("ta-alice"), client.register("ta-bob"))
        val code = client.postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        client.postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken)

        // alice fills her profile; bob should see it.
        assertEquals(204, client.put("/api/v1/ta/me") {
            bearerAuth(alice.accessToken); contentType(ContentType.Application.Json)
            setBody(obj("bio" to "爱喝奶茶", "birthday" to "08-18", "birthYear" to 1999))
        }.status.value)

        val forBob = client.get("/api/v1/ta/summary") { bearerAuth(bob.accessToken) }.json()
        val partner = forBob["partner"]!!.jsonObject
        assertEquals("爱喝奶茶", partner["bio"]!!.jsonPrimitive.content)
        assertEquals("08-18", partner["birthday"]!!.jsonPrimitive.content)
        assertEquals(1999, partner["birthYear"]!!.jsonPrimitive.content.toInt())
        assertTrue(partner["birthdayVisible"]!!.jsonPrimitive.content.toBoolean())
        // `me` 是 bob 自己：注册时 displayName = 用户名大写。
        assertEquals("TA-BOB", forBob["me"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)

        val forAlice = client.get("/api/v1/ta/summary") { bearerAuth(alice.accessToken) }.json()
        assertEquals("ta-bob", forAlice["partner"]!!.jsonObject["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun birthdayPrivacyHidesFromPartner() = appTest { client ->
        val (alice, bob) = Pair(client.register("tp-alice"), client.register("tp-bob"))
        val code = client.postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        client.postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken)
        client.put("/api/v1/ta/me") {
            bearerAuth(alice.accessToken); contentType(ContentType.Application.Json)
            setBody(obj("birthday" to "08-18", "birthYear" to 1999, "showBirthday" to false))
        }
        val forBob = client.get("/api/v1/ta/summary") { bearerAuth(bob.accessToken) }.json()["partner"]!!.jsonObject
        val hidden = forBob["birthday"]
        assertTrue(hidden == null || hidden.jsonPrimitive.content == "null", "birthday must be hidden, got $hidden")
        assertEquals(false, forBob["birthdayVisible"]!!.jsonPrimitive.content.toBoolean())
        // But alice herself still sees her own birthday in `me`.
        val mine = client.get("/api/v1/ta/summary") { bearerAuth(alice.accessToken) }.json()["me"]!!.jsonObject
        assertEquals("08-18", mine["birthday"]!!.jsonPrimitive.content)
    }

    @Test
    fun importantDaysCrudSharedForBothMembers() = appTest { client ->
        val (alice, bob) = Pair(client.register("td-alice"), client.register("td-bob"))
        val code = client.postJson("/api/v1/pair/code", null, alice.accessToken).json()["code"]!!.jsonPrimitive.content
        client.postJson("/api/v1/pair/join", obj("code" to code), bob.accessToken)

        val add = client.postJson("/api/v1/ta/days", obj("name" to "第一次见面", "month" to 3, "day" to 14, "year" to 2024), alice.accessToken)
        assertEquals(200, add.status.value, add.bodyAsText())
        val dayId = add.json()["id"]!!.jsonPrimitive.content

        // Validation
        assertEquals(400, client.postJson("/api/v1/ta/days", obj("name" to "x", "month" to 13, "day" to 1), alice.accessToken).status.value)
        assertEquals(400, client.postJson("/api/v1/ta/days", obj("name" to "", "month" to 1, "day" to 1), alice.accessToken).status.value)

        // Partner can update and delete shared days.
        val upd = client.put("/api/v1/ta/days/$dayId") {
            bearerAuth(bob.accessToken); contentType(ContentType.Application.Json); setBody(obj("name" to "初次见面"))
        }
        assertEquals(200, upd.status.value, upd.bodyAsText())
        assertEquals("初次见面", upd.json()["name"]!!.jsonPrimitive.content)

        val summary = client.get("/api/v1/ta/summary") { bearerAuth(bob.accessToken) }.json()
        assertEquals(1, summary["days"]!!.jsonArray.size)

        // Outsider cannot touch it (unpaired outsiders get NOT_PAIRED 403; paired outsiders get 404 below).
        val outsider = client.register("td-outsider")
        assertEquals(403, client.delete("/api/v1/ta/days/$dayId") { bearerAuth(outsider.accessToken) }.status.value)

        assertEquals(204, client.delete("/api/v1/ta/days/$dayId") { bearerAuth(bob.accessToken) }.status.value)
        assertEquals(404, client.put("/api/v1/ta/days/$dayId") {
            bearerAuth(alice.accessToken); contentType(ContentType.Application.Json); setBody(obj("name" to "ghost"))
        }.status.value)
    }

    @Test
    fun unpairedCannotUseTa() = appTest { client ->
        val solo = client.register("ta-solo")
        val r = client.get("/api/v1/ta/summary") { bearerAuth(solo.accessToken) }
        assertEquals(403, r.status.value)
        assertEquals("NOT_PAIRED", r.errorCode())
    }
}
