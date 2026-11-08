package com.qimingnan.couple.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Surface
import com.qimingnan.couple.theme.CoupleTokens
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.ui.ledger.AccountIcon
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.qimingnan.couple.theme.CoupleTheme
import com.qimingnan.couple.ui.components.LongPressReorderableColumn
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LongPressReorderableColumnTest {
    @get:Rule val compose = createComposeRule()
    private var order = listOf("a", "b", "c", "d")
    private var saves = 0
    private var clicks = 0
    private fun show(failSaving: Boolean = false) {
        compose.setContent {
            var items by remember { mutableStateOf(order) }
            CoupleTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    LongPressReorderableColumn(items, { it }, { next, onSaved ->
                        saves++
                        if (!failSaving) { order = next; items = next }
                        onSaved(!failSaving)
                    }) { id, _ ->
                        Box(Modifier.fillMaxWidth().height(64.dp).clickable { clicks++ }) { Text(id) }
                    }
                }
            }
        }
    }
    @Test fun ordinaryTapStillOpensItemAndDoesNotSaveOrder() {
        show()
        compose.onNodeWithTag("reorder-item-a").performTouchInput { click() }
        assertEquals(1, clicks)
        assertEquals(0, saves)
    }
    @Test fun longPressDragMovesAcrossRowsAndSavesOnceWithoutClick() {
        show()
        compose.onNodeWithTag("reorder-item-a").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 200f)); up()
        }
        compose.waitForIdle()
        assertEquals(1, saves)
        assertEquals(0, clicks)
        assertNotEquals("a", order.first())
    }
    @Test fun cancelledDragDoesNotSave() {
        show()
        compose.onNodeWithTag("reorder-item-a").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 160f)); cancel()
        }
        compose.waitForIdle()
        assertEquals(0, saves)
        assertEquals(listOf("a", "b", "c", "d"), order)
    }
    @Test fun saveFailureShowsErrorAndRestoresOrder() {
        show(failSaving = true)
        compose.onNodeWithTag("reorder-item-a").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 200f)); up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("顺序保存失败，已恢复原顺序，请重试。").assertExists()
        assertEquals(listOf("a", "b", "c", "d"), order)
    }
    @Test fun holdingNearClippedEdgeAutoScrollsAndMovesPastVisibleRows() {
        order = (0 until 12).map { "item-$it" }
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            var items by remember { mutableStateOf(order) }
            CoupleTheme {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(scroll).testTag("scroll")) {
                    LongPressReorderableColumn(items, { it }, { next, onSaved ->
                        saves++; order = next; items = next; onSaved(true)
                    }, scrollState = scroll) { id, _ ->
                        Box(Modifier.fillMaxWidth().height(64.dp)) { Text(id) }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("scroll").performTouchInput {
            down(Offset(centerX, 32f)); advanceEventTime(650)
            moveTo(Offset(centerX, height - 2f))
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { assertTrue("A stationary finger at the clipped edge must scroll", scroll.value > 0) }
        compose.onNodeWithTag("scroll").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, saves)
        assertTrue("The first row must move beyond the initial visible rows", order.indexOf("item-0") >= 4)
    }
    @Test fun bankLogoKeepsNativeRedWithoutThemeBadge() {
        compose.setContent {
            CoupleTheme {
                Surface(color = CoupleTokens.DefaultLight.colors.surface) {
                    AccountIcon(LedgerAccountType.BANK, Modifier.size(64.dp).testTag("bank-icon"), "icbc")
                }
            }
        }
        val pixels = compose.onNodeWithTag("bank-icon").captureToImage().toPixelMap()
        assertEquals(CoupleTokens.DefaultLight.colors.surface, pixels[1, pixels.height / 2])
        var nativeRed = false
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.red > 0.7f && c.green < 0.25f && c.blue < 0.3f) nativeRed = true
        }
        assertTrue("Native bank logo fill must not be recoloured", nativeRed)
    }

}
