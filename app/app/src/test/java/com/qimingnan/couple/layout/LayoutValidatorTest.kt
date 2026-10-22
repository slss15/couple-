package com.qimingnan.couple.layout

import com.qimingnan.couple.core.CoupleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutValidatorTest {
    private fun codes(spec: LayoutSpec) =
        (LayoutValidator.validate(spec) as? LayoutValidation.Invalid)?.errors?.map { it.code }.orEmpty()

    private fun LayoutSpec.mapZone(id: String, f: (List<LayoutItem>) -> List<LayoutItem>) =
        copy(zones = zones.map { if (it.id == id) it.copy(items = f(it.items)) else it })

    @Test fun defaultLayoutIsValid() {
        assertTrue(LayoutValidator.validate(LayoutRules.DEFAULT) is LayoutValidation.Valid)
    }

    @Test fun defaultRoundTripsThroughJson() {
        val json = CoupleJson.encodeToString(LayoutSpec.serializer(), LayoutRules.DEFAULT)
        assertTrue(json.contains("\"size\":\"M\""))
        assertTrue(LayoutValidator.validateJson(json) is LayoutValidation.Valid)
    }

    @Test fun lockedIdsMatchPlan() {
        assertEquals(setOf("send", "back", "hangup", "sharing_indicator", "privacy_toggle", "me_entry"), LayoutRules.LOCKED_IDS)
    }

    @Test fun lockedItemCannotBeHidden() {
        val spec = LayoutRules.DEFAULT.mapZone("bottomBar") { items -> items.map { if (it.id == "me_entry") it.copy(visible = false) else it } }
        assertEquals(listOf("LOCKED_HIDDEN"), codes(spec))
    }

    @Test fun lockedItemCannotBeRemoved() {
        val spec = LayoutRules.DEFAULT.mapZone("chatToolbar") { items -> items.filterNot { it.id == "send" } }
        assertEquals(listOf("LOCKED_MISSING"), codes(spec))
    }

    @Test fun lockedItemCannotMoveZoneOrOrder() {
        val moved = LayoutRules.DEFAULT
            .mapZone("bottomBar") { items -> items.filterNot { it.id == "me_entry" } }
            .mapZone("topBar") { items -> items + LayoutItem("me_entry", 5, size = ItemSize.S) }
        assertTrue(codes(moved).contains("LOCKED_MOVED"))

        val reordered = LayoutRules.DEFAULT.mapZone("bottomBar") { items ->
            items.map { when (it.id) { "me_entry" -> it.copy(order = 0); "tab_chat" -> it.copy(order = 3); else -> it } }
        }
        assertEquals(listOf("LOCKED_MOVED"), codes(reordered))
    }

    @Test fun duplicateOrderAndItemDetected() {
        val spec = LayoutRules.DEFAULT.mapZone("bottomBar") { items -> items + LayoutItem("tab_chat", 1) }
        val c = codes(spec)
        assertTrue(c.contains("DUPLICATE_ORDER"))
        assertTrue(c.contains("DUPLICATE_ITEM"))
    }

    @Test fun capacityAndGridWidthEnforced() {
        val tooMany = LayoutRules.DEFAULT.mapZone("bottomBar") { items -> items + (4..6).map { LayoutItem("extra_$it", it, size = ItemSize.S) } }
        assertTrue(codes(tooMany).contains("ZONE_OVER_CAPACITY"))
        val tooWide = LayoutRules.DEFAULT.mapZone("bottomBar") { items -> items + LayoutItem("big", 4, size = ItemSize.L) }
        assertTrue(codes(tooWide).contains("ZONE_OVER_WIDTH"))
        // 隐藏的条目不占网格宽度
        val hidden = LayoutRules.DEFAULT.mapZone("bottomBar") { items -> items + LayoutItem("big", 4, visible = false, size = ItemSize.L) }
        assertTrue(LayoutValidator.validate(hidden) is LayoutValidation.Valid)
    }

    @Test fun unknownZoneAndBadIdRejected() {
        val spec = LayoutRules.DEFAULT.copy(zones = LayoutRules.DEFAULT.zones + LayoutZone("sidebar", listOf(LayoutItem("Bad ID", 0))))
        val c = codes(spec)
        assertTrue(c.contains("UNKNOWN_ZONE"))
        assertTrue(c.contains("INVALID_ITEM_ID"))
    }

    @Test fun duplicateZoneRejected() {
        val spec = LayoutRules.DEFAULT.copy(zones = LayoutRules.DEFAULT.zones + LayoutZone("feedHeader", emptyList()))
        assertTrue(codes(spec).contains("DUPLICATE_ZONE"))
    }

    @Test fun badJsonAndUnsupportedVersion() {
        val bad = LayoutValidator.validateJson("{not json")
        assertEquals("INVALID_JSON", (bad as LayoutValidation.Invalid).errors.single().code)
        val badSize = LayoutValidator.validateJson("""{"version":1,"zones":[{"id":"topBar","items":[{"id":"x","order":0,"size":"XL"}]}]}""")
        assertTrue(badSize is LayoutValidation.Invalid)
        assertTrue(codes(LayoutRules.DEFAULT.copy(version = 99)).contains("UNSUPPORTED_VERSION"))
    }

    @Test fun invalidFallsBackToDefault() {
        assertEquals(LayoutRules.DEFAULT, LayoutValidator.orDefault("""{"version":1,"zones":[]}"""))
        assertEquals(LayoutRules.DEFAULT, LayoutValidator.orDefault(null))
    }
}
