package com.qimingnan.couple.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutResolverTest {
    private val allTabs = listOf("tab_feed", "tab_ta", "me_entry", "tab_anniversary", "tab_ledger", "tab_period")
    private val fallback = listOf("tab_feed", "tab_ta", "me_entry", "tab_anniversary", "tab_ledger")

    @Test fun serverOrderWinsWhenUserHasNotCustomized() {
        val layout = LayoutRules.DEFAULT.copy(zones = LayoutRules.DEFAULT.zones.map { zone ->
            if (zone.id != "bottomBar") zone else zone.copy(items = listOf(
                LayoutItem("tab_ledger", 0), LayoutItem("me_entry", 3), LayoutItem("tab_feed", 1),
                LayoutItem("tab_ta", 2, visible = false), LayoutItem("tab_anniversary", 4),
            ))
        })
        assertEquals(
            listOf("tab_ledger", "tab_feed", "me_entry", "tab_anniversary"),
            LayoutResolver.resolveTabOrder(layout, fallback, false, allTabs, fallback),
        )
    }

    @Test fun localOrderWinsInsideServerVisibleSet() {
        val local = listOf("tab_ledger", "tab_feed", "tab_ta", "me_entry", "tab_anniversary")
        assertEquals(local, LayoutResolver.resolveTabOrder(LayoutRules.DEFAULT, local, true, allTabs, fallback))
    }

    @Test fun serverHiddenTabCannotBeRestoredLocally() {
        val layout = LayoutRules.DEFAULT.copy(zones = LayoutRules.DEFAULT.zones.map { zone ->
            if (zone.id != "bottomBar") zone else zone.copy(items = zone.items.filterNot { it.id == "tab_ledger" })
        })
        val resolved = LayoutResolver.resolveTabOrder(layout, fallback, true, allTabs, fallback)
        assertFalse("tab_ledger" in resolved)
    }

    @Test fun missingZoneFallsBackToDefault() {
        val layout = LayoutRules.DEFAULT.copy(zones = LayoutRules.DEFAULT.zones.filterNot { it.id == "bottomBar" })
        assertEquals(fallback, LayoutResolver.resolveTabOrder(layout, fallback, false, allTabs, fallback))
    }

    @Test fun chatEntryIsHiddenByDefault() {
        assertFalse(LayoutResolver.isVisible(LayoutRules.DEFAULT, "meSections", "chat_entry"))
        val me = LayoutResolver.visibleIds(LayoutRules.DEFAULT, "meSections", listOf("settings"))
        assertTrue("settings" in me)
        assertFalse("chat_entry" in me)
    }
}
