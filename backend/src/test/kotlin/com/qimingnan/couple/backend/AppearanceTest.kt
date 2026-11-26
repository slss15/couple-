package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.layout.AppAppearance
import com.qimingnan.couple.backend.layout.AppearanceUpdate
import com.qimingnan.couple.backend.layout.LayoutItem
import com.qimingnan.couple.backend.layout.LayoutRules
import com.qimingnan.couple.backend.layout.RemoteTheme
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceTest {
    private fun layoutWithLedgerHidden() = LayoutRules.DEFAULT.copy(
        zones = LayoutRules.DEFAULT.zones.map { zone ->
            if (zone.id != "bottomBar") zone
            else zone.copy(items = zone.items.map { if (it.id == "tab_ledger") it.copy(visible = false) else it })
        },
    )

    private suspend fun io.ktor.client.HttpClient.putAppearance(token: String, body: AppearanceUpdate) = put("/api/v1/admin/appearance") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(AppJson.encodeToString(AppearanceUpdate.serializer(), body))
    }

    @Test
    fun defaultAppearanceAndAdminUpdate() = appTest(testConfig(adminUsername = "admin")) { client ->
        val admin = client.register("admin")
        val user = client.register("bob")

        val initial = client.get("/api/v1/app/appearance") { bearerAuth(user.accessToken) }
        assertEquals(200, initial.status.value, initial.bodyAsText())
        val initialView = AppJson.decodeFromString(AppAppearance.serializer(), initial.bodyAsText())
        assertEquals(0L, initialView.appearanceVersion)
        assertEquals(LayoutRules.DEFAULT, initialView.layout)
        assertEquals("linepuppy", initialView.theme.seed)
        assertTrue(initialView.catalog.any { it.id == "bottomBar" })
        assertFalse(initialView.layout.zones.first { it.id == "meSections" }.items.first { it.id == "chat_entry" }.visible)

        assertEquals(403, client.get("/api/v1/admin/appearance") { bearerAuth(user.accessToken) }.status.value)
        val adminView = client.get("/api/v1/admin/appearance") { bearerAuth(admin.accessToken) }
        assertEquals(200, adminView.status.value, adminView.bodyAsText())

        val nextLayout = layoutWithLedgerHidden()
        val nextTheme = RemoteTheme(seed = "blue", darkMode = "dark", fontSize = "large", allowUserOverride = false)
        val update = client.putAppearance(admin.accessToken, AppearanceUpdate(layout = nextLayout, theme = nextTheme))
        assertEquals(200, update.status.value, update.bodyAsText())
        val updated = AppJson.decodeFromString(AppAppearance.serializer(), update.bodyAsText())
        assertEquals(1L, updated.appearanceVersion)
        assertEquals(nextTheme, updated.theme)
        assertFalse(updated.layout.zones.first { it.id == "bottomBar" }.items.first { it.id == "tab_ledger" }.visible)

        val visible = client.get("/api/v1/app/appearance") { bearerAuth(user.accessToken) }
        assertEquals(200, visible.status.value)
        val visibleView = AppJson.decodeFromString(AppAppearance.serializer(), visible.bodyAsText())
        assertEquals(1L, visibleView.appearanceVersion)
        assertEquals("blue", visibleView.theme.seed)

        val audit = client.get("/api/v1/admin/audit?limit=100") { bearerAuth(admin.accessToken) }
        val actions = AppJson.parseToJsonElement(audit.bodyAsText()).jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertTrue("admin.appearance.view" in actions)
        assertTrue("admin.appearance.update" in actions)
    }

    @Test
    fun invalidThemeAndLockedLayoutRejected() = appTest(testConfig(adminUsername = "admin")) { client ->
        val admin = client.register("admin")
        val badTheme = client.putAppearance(admin.accessToken, AppearanceUpdate(theme = RemoteTheme(seed = "neon")))
        assertEquals(400, badTheme.status.value)
        assertEquals("VALIDATION_ERROR", badTheme.errorCode())

        val hiddenMe = LayoutRules.DEFAULT.copy(
            zones = LayoutRules.DEFAULT.zones.map { zone ->
                if (zone.id != "bottomBar") zone
                else zone.copy(items = zone.items.map { if (it.id == "me_entry") it.copy(visible = false) else it })
            },
        )
        val badLayout = client.putAppearance(admin.accessToken, AppearanceUpdate(layout = hiddenMe))
        assertEquals(400, badLayout.status.value)
        assertEquals("VALIDATION_ERROR", badLayout.errorCode())

        val tooMany = LayoutRules.DEFAULT.copy(
            zones = LayoutRules.DEFAULT.zones.map { zone ->
                if (zone.id != "bottomBar") zone
                else zone.copy(
                    items = zone.items + listOf(
                        LayoutItem("tab_extra", 99, visible = false),
                        LayoutItem("tab_extra2", 100, visible = false),
                    ),
                )
            },
        )
        assertEquals(400, client.putAppearance(admin.accessToken, AppearanceUpdate(layout = tooMany)).status.value)
    }
}
