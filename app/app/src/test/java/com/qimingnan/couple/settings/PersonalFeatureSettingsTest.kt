package com.qimingnan.couple.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.data.settings.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalFeatureSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") } }
    private val repo by lazy { SettingsRepository(store, scope) }
    @After fun tearDown() { scope.cancel() }
    private fun SettingsRepository.await(predicate: (AppSettings) -> Boolean) = runBlocking { withTimeout(5000) { settings.first(predicate) } }

    @Test fun defaultsToFeedAndCreamTheme() {
        assertEquals("tab_feed", AppSettings().homeTab)
        assertEquals("linepuppy", AppSettings().themeSeed)
        assertTrue(AppSettings().retainRecentTask)
        assertEquals("tab_feed", SettingsRepository.decode(androidx.datastore.preferences.core.emptyPreferences()).homeTab)
    }
    @Test fun startupHonorsPersistedSelectionInsteadOfInitialStateFlowDefault() = runBlocking {
        repo.setHomeTab("tab_ledger")
        assertEquals("tab_ledger", repo.initialSettings().homeTab)
        repo.setHomeTab("home_page")
        assertEquals("home_page", repo.initialSettings().homeTab)
        repo.setHomeTab("unknown")
        assertEquals("home_page", repo.initialSettings().homeTab)
    }
    @Test fun hiddenOrDisabledStartFallsBackToVisibleFeedOrMe() {
        assertEquals("tab_feed", StartupPage.resolve("tab_ledger", listOf("tab_feed", "me_entry")))
        assertEquals("me_entry", StartupPage.resolve("tab_feed", listOf("me_entry")))
        assertEquals("home_page", StartupPage.resolve("home_page", listOf("me_entry")))
    }
    @Test fun consecutiveTabReordersAreNotLost() = runBlocking {
        repo.moveTabOrder("tab_ledger", -1)
        repo.moveTabOrder("tab_ledger", -1)
        repo.moveTabOrder("tab_ledger", -1)
        assertEquals(listOf("tab_feed", "tab_ledger", "tab_ta", "me_entry", "tab_anniversary"), repo.initialSettings().tabOrder)
        repo.moveTabOrder("tab_feed", -1)
        assertEquals("tab_feed", repo.initialSettings().tabOrder.first())
    }
    @Test fun accountReorderRemovesDeletedIdsAndAddsNewAccounts() = runBlocking {
        repo.moveAccountOrder("c", -1, listOf("a", "b", "c"))
        repo.moveAccountOrder("c", -1, listOf("a", "b", "c"))
        assertEquals(listOf("c", "a", "b"), repo.initialSettings().accountOrder)
        repo.moveAccountOrder("d", -1, listOf("a", "c", "d"))
        assertEquals(listOf("c", "d", "a"), repo.initialSettings().accountOrder)
    }
    @Test fun completedTabDropsPersistFullOrdersAndNormalizeIds() = runBlocking {
        repo.setTabHidden("tab_ta", true)
        repo.setHomeTab("tab_ledger")
        repo.setTabOrder(listOf("tab_ledger", "tab_feed", "tab_ta", "me_entry", "tab_anniversary"))
        repo.setTabOrder(listOf("tab_ta", "tab_ta", "unknown", "tab_ledger"))
        val state = repo.initialSettings()
        assertEquals(listOf("tab_ta", "tab_ledger", "tab_feed", "me_entry", "tab_anniversary"), state.tabOrder)
        assertEquals("tab_ledger", state.homeTab)
        assertTrue("tab_ta" in state.hiddenTabs)
    }
    @Test fun completedAccountDropsPreserveAllCurrentAccountsWithoutDuplicatesOrDeletedIds() = runBlocking {
        repo.setAccountOrder(listOf("c", "a", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("c", "a", "b"), repo.initialSettings().accountOrder)
        repo.setAccountOrder(listOf("d", "a", "a", "deleted"), listOf("a", "c", "d", "new"))
        assertEquals(listOf("d", "a", "c", "new"), repo.initialSettings().accountOrder)
        repo.setAccountOrder(emptyList(), emptyList())
        assertTrue(repo.initialSettings().accountOrder.isEmpty())
    }
    @Test fun taskVisibilityAndTravelPlanPersistAndCanBeCleared() = runBlocking {
        repo.setRetainRecentTask(false)
        repo.setTravel("a", 120000, "  去看海  ")
        val state = repo.initialSettings()
        assertFalse(state.retainRecentTask)
        assertEquals("去看海", state.travelName)
        assertEquals("a", state.travelAccountId)
        assertEquals(120000L, state.travelTargetCents)
        repo.setTravel(null, 0)
        assertNull(repo.initialSettings().travelAccountId)
        assertEquals("", repo.initialSettings().travelName)
    }
    @Test fun duplicateCheckInCountsOnceAndSurpriseIsClamped() = runBlocking {
        repo.addCheckIn("a", "2026-11-07")
        repo.addCheckIn("a", "2026-11-07")
        repo.setSurpriseProgress("a", 200)
        assertEquals(setOf("a:2026-11-07"), repo.initialSettings().checkins)
        assertEquals(setOf("a:100"), repo.initialSettings().surpriseProgress)
    }
    @Test fun readsLegacyLoanAndPreservesOldRepaidAmount() = runBlocking {
        store.edit { it[stringSetPreferencesKey("loan_records")] = setOf("legacy|BORROWED|a|10000|2000|旧记录") }
        assertTrue(repo.repayLoan("legacy", 3000, "2026-11-07"))
        val loan = repo.initialSettings().loans.mapNotNull(LoanRecord::decode).single()
        assertEquals(5000L, loan.repaidCents)
        assertEquals(1, loan.repayments.size)
        assertEquals(3000L, loan.repayments.single().amountCents)
        repo.deleteLoan("legacy")
        assertTrue(repo.initialSettings().loans.isEmpty())
    }
    @Test fun concurrentRepaymentsAreSerializedAndOverpaymentIsRejected() = runBlocking {
        repo.addLoan(LoanRecord("a", LoanKind.BORROWED, null, 10000))
        coroutineScope { repeat(20) { launch(Dispatchers.Default) { assertTrue(repo.repayLoan("a", 100, "2026-11-07")) } } }
        val loan = repo.initialSettings().loans.mapNotNull(LoanRecord::decode).single()
        assertEquals(2000L, loan.repaidCents)
        assertEquals(20, loan.repayments.size)
        assertFalse(repo.repayLoan("a", -100))
        assertFalse(repo.repayLoan("a", 9000))
        assertEquals(2000L, repo.initialSettings().loans.mapNotNull(LoanRecord::decode).single().repaidCents)
    }
    @Test fun editingMetadataDoesNotEraseARepaymentMadeWhileEditorWasOpen() = runBlocking {
        val initial = LoanRecord("a", LoanKind.LENT, null, 10000)
        repo.addLoan(initial)
        repo.repayLoan("a", 2000, "2026-11-07")
        repo.addLoan(initial.copy(counterparty = "朋友"))
        val loan = repo.initialSettings().loans.mapNotNull(LoanRecord::decode).single()
        assertEquals(2000L, loan.repaidCents)
        assertEquals("朋友", loan.counterparty)
        assertEquals(1, repo.initialSettings().loans.size)
    }
    @Test fun addingAnotherLoanImmediatelyBeforeRepaymentDoesNotLoseIt() = runBlocking {
        repo.addLoan(LoanRecord("a", LoanKind.BORROWED, null, 10000))
        repo.addLoan(LoanRecord("b", LoanKind.LENT, null, 5000))
        repo.repayLoan("a", 1000, "2026-11-07")
        assertEquals(setOf("a", "b"), repo.initialSettings().loans.mapNotNull(LoanRecord::decode).map { it.id }.toSet())
    }
}
