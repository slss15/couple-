package com.qimingnan.couple.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.db.*
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.data.settings.SettingsRepository
import com.qimingnan.couple.theme.CoupleTheme
import com.qimingnan.couple.ui.ledger.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class LoanRepaymentWizardTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: LedgerViewModel
    private lateinit var repo: LoanLedgerRepository
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: File
    private val viewModelStore = androidx.lifecycle.ViewModelStore()
    private val owner = "local"
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = File(context.cacheDir, "wizard-${UUID.randomUUID()}.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { prefs }, scope)
        repo = LoanLedgerRepository(db, settings, Clock.System)
        val accounts = listOf(LedgerAccount("cash", "测试现金", LedgerAccountType.CASH, 10000),
            LedgerAccount("lent", "测试借出", LedgerAccountType.LENT, 0), LedgerAccount("borrowed", "测试借入", LedgerAccountType.BORROWED, 0))
        accounts.forEach { db.ledgerDao().upsertAccount(it.toEntity(owner, 1)) }
        vm = LedgerViewModel(db.ledgerDao(), db.profileDao(), Clock.System, repo)
        viewModelStore.put("ledger", vm)
    }
    @After fun cleanup() { viewModelStore.clear(); db.close(); scope.cancel(); prefs.delete() }
    private fun open() {
        compose.setContent {
            val accounts by vm.accounts.collectAsState()
            val entries by vm.entries.collectAsState()
            CoupleTheme { LedgerEntryWizard(vm, accounts, entries, {}) }
        }
    }
    private fun tap(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    @Test fun incomeCollectsFromBorrowerInLentAccount() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.LENT, null, 1000, label = "测试借款", counterparty = "小李", loanAccountId = "lent"), owner)
        open()
        compose.waitUntil(5000) { vm.loans.value.isNotEmpty() }
        compose.onNodeWithText("5").performClick()
        compose.onNodeWithText("下一步").performClick()
        tap("收入"); tap("测试现金"); tap("还钱 · 收回借款"); tap("测试借出"); tap("小李"); tap("测试借款")
        compose.onNodeWithText("保存还钱").performClick()
        compose.waitUntil(5000) { vm.loans.value.firstOrNull()?.repaidCents == 500L }
        assertEquals(LoanAction.COLLECT_LENT, vm.entries.value.single().loanAction)
    }
    @Test fun expenseRepaysNamedPlatformInBorrowedAccount() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.BORROWED, null, 1000, label = "平台账单", counterparty = "平台A", loanAccountId = "borrowed", counterpartyType = CounterpartyType.PLATFORM), owner)
        open()
        compose.waitUntil(5000) { vm.loans.value.isNotEmpty() }
        compose.onNodeWithText("5").performClick()
        compose.onNodeWithText("下一步").performClick()
        tap("支出"); tap("测试现金"); tap("还钱 · 归还借款"); tap("测试借入"); tap("平台A"); tap("平台账单")
        compose.onNodeWithText("保存还钱").performClick()
        compose.waitUntil(5000) { vm.loans.value.firstOrNull()?.repaidCents == 500L }
        assertEquals(LoanAction.REPAY_BORROWED, vm.entries.value.single().loanAction)
    }
    @Test fun sameNamePersonAndPlatformHaveSeparateDetailPages() = runBlocking {
        repo.saveLoan(LoanRecord("person", LoanKind.LENT, null, 1000, label = "个人借款明细", counterparty = "同名", loanAccountId = "lent"), owner)
        repo.saveLoan(LoanRecord("platform", LoanKind.LENT, null, 2000, label = "平台借款明细", counterparty = "同名", loanAccountId = "lent", counterpartyType = CounterpartyType.PLATFORM), owner)
        compose.setContent {
            val loans by vm.loans.collectAsState()
            val accounts by vm.accounts.collectAsState()
            val entries by vm.entries.collectAsState()
            CoupleTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    LoanAccountDetail(vm, LedgerAccount("lent", "测试借出", LedgerAccountType.LENT, 0), accounts.filterNot { it.type.isLoan }, loans, entries, {})
                }
            }
        }
        compose.waitUntil(5000) { vm.loans.value.size == 2 }
        tap("平台"); tap("同名")
        compose.onNodeWithText("平台借款明细").assertExists()
        compose.onNodeWithText("个人借款明细").assertDoesNotExist()
    }

}
