package com.qimingnan.couple.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.db.*
import com.qimingnan.couple.data.ledger.*
import com.qimingnan.couple.data.settings.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoanLedgerRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LoanLedgerRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: File
    private val owner = "test-owner"
    private val date = "2026-11-07"
    private val now = 1794009600000L
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = db.ledgerDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = File(context.cacheDir, "loans-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { prefs }, scope)
        repo = LoanLedgerRepository(db, settings, Clock { now })
        listOf(LedgerAccount("cash", "现金", LedgerAccountType.CASH, 10000),
            LedgerAccount("borrowed", "借入账户", LedgerAccountType.BORROWED, 0),
            LedgerAccount("lent", "借出账户", LedgerAccountType.LENT, 0)).forEach { dao.upsertAccount(it.toEntity(owner, now)) }
    }
    @After fun cleanup() { db.close(); scope.cancel(); prefs.delete() }
    private suspend fun loan(id: String) = LoanRecord.decode(dao.loanById(id)!!.payload)!!
    private suspend fun cashBalance() = LedgerLogic.balance(dao.accountById("cash")!!.toModel(), dao.observeEntries().first().map { it.toModel() })

    @Test fun concurrentRepaymentsCommitCashAndDebtTogether() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.BORROWED, "cash", 10000, counterparty = "平台A", loanAccountId = "borrowed", counterpartyType = CounterpartyType.PLATFORM, occurredOn = date), owner)
        coroutineScope { repeat(20) { n -> launch(Dispatchers.Default) { repo.repay("p$n", "a", "cash", 100, date, "", owner, LoanKind.BORROWED) } } }
        assertEquals(2000L, loan("a").repaidCents)
        assertEquals(20, loan("a").repayments.size)
        assertEquals(21, dao.entriesForLoan("a").size)
        assertEquals(18000L, cashBalance())
    }
    @Test fun saveRetryIsIdempotentAndDeletingPaymentReversesBothSides() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.LENT, null, 10000, counterparty = "小李", loanAccountId = "lent"), owner)
        repeat(2) { repo.repay("p", "a", "cash", 2500, date, "还款", owner, LoanKind.LENT) }
        assertEquals(2500L, loan("a").repaidCents)
        assertEquals(12500L, cashBalance())
        repo.deleteEntry("p", owner)
        repo.deleteEntry("p", owner)
        assertEquals(0L, loan("a").repaidCents)
        assertTrue(loan("a").repayments.isEmpty())
        assertEquals(10000L, cashBalance())
    }
    @Test fun invalidDirectionAccountOwnerAndOverpaymentCannotPartiallySave() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.BORROWED, null, 1000, counterparty = "小王", loanAccountId = "borrowed"), owner)
        assertTrue(runCatching { repo.repay("wrong-kind", "a", "cash", 100, date, "", owner, LoanKind.LENT) }.isFailure)
        assertTrue(runCatching { repo.repay("wrong-cash", "a", "borrowed", 100, date, "", owner, LoanKind.BORROWED) }.isFailure)
        assertTrue(runCatching { repo.repay("wrong-owner", "a", "cash", 100, date, "", "other", LoanKind.BORROWED) }.isFailure)
        assertTrue(runCatching { repo.repay("overpay", "a", "cash", 1001, date, "", owner, LoanKind.BORROWED) }.isFailure)
        assertEquals(0L, loan("a").repaidCents)
        assertTrue(dao.entriesForLoan("a").isEmpty())
        assertEquals(10000L, cashBalance())
    }
    @Test fun closingCashAccountAllowsMetadataButNotPrincipalChanges() = runBlocking {
        val original = LoanRecord("a", LoanKind.LENT, "cash", 1000, counterparty = "小李", loanAccountId = "lent", occurredOn = date)
        repo.saveLoan(original, owner)
        repo.deleteAccount("cash", owner)
        repo.saveLoan(original.copy(counterparty = "小李同学", label = "改备注"), owner)
        assertEquals("小李同学", loan("a").counterparty)
        assertEquals("改备注", dao.entryById("loan-opening-a")!!.note)
        assertEquals(1000L, dao.entryById("loan-opening-a")!!.amountCents)
        assertTrue(runCatching { repo.saveLoan(original.copy(amountCents = 2000), owner) }.isFailure)
        assertEquals(1000L, loan("a").amountCents)
        assertEquals(1000L, dao.entryById("loan-opening-a")!!.amountCents)
    }
    @Test fun deletedLoanAccountCannotAcceptRepayment() = runBlocking {
        repo.saveLoan(LoanRecord("a", LoanKind.BORROWED, null, 1000, counterparty = "小王", loanAccountId = "borrowed"), owner)
        dao.softDeleteAccount("borrowed", now)
        assertTrue(runCatching { repo.repay("p", "a", "cash", 100, date, "", owner, LoanKind.BORROWED) }.isFailure)
        assertEquals(0L, loan("a").repaidCents)
        assertTrue(dao.entriesForLoan("a").isEmpty())
        assertEquals(10000L, cashBalance())
    }
    @Test fun closingLoanAccountAndCreatingLoanCannotLeaveOrphans() = runBlocking {
        coroutineScope {
            launch(Dispatchers.Default) { runCatching { repo.deleteAccount("lent", owner) } }
            launch(Dispatchers.Default) { runCatching {
                repo.saveLoan(LoanRecord("a", LoanKind.LENT, "cash", 1000, counterparty = "小李", loanAccountId = "lent", occurredOn = date), owner)
            } }
        }
        if (dao.accountById("lent")!!.deleted) {
            assertNull(dao.loanById("a"))
            assertTrue(dao.entriesForLoan("a").isEmpty())
            assertEquals(10000L, cashBalance())
        } else {
            assertNotNull(dao.loanById("a"))
            assertEquals(9000L, cashBalance())
            assertTrue(runCatching { repo.deleteAccount("lent", owner) }.isFailure)
        }
    }
    @Test fun oldEditorCannotEraseNewPaymentAndLinkedLoanCannotBeDeleted() = runBlocking {
        val original = LoanRecord("a", LoanKind.BORROWED, null, 1000, counterparty = "小王", loanAccountId = "borrowed")
        repo.saveLoan(original, owner)
        repo.repay("p", "a", "cash", 200, date, "", owner, LoanKind.BORROWED)
        repo.saveLoan(original.copy(label = "改备注"), owner)
        assertEquals(200L, loan("a").repaidCents)
        assertTrue(runCatching { repo.deleteLoan("a", owner) }.isFailure)
        assertNotNull(dao.entryById("p"))
    }
    @Test fun legacyImportPreservesHistoricalAmountsAndNeverGeneratesOrResurrectsCash() = runBlocking {
        settings.addLoan(LoanRecord("legacy", LoanKind.LENT, "cash", 5000, 1000, "历史"))
        repo.importLegacy(owner)
        repo.importLegacy(owner)
        assertEquals(1000L, loan("legacy").repaidCents)
        assertTrue(dao.entriesForLoan("legacy").isEmpty())
        assertTrue(settings.legacyLoansForImport().isEmpty())
        repo.deleteLoan("legacy", owner)
        repo.importLegacy(owner)
        assertNull(dao.loanById("legacy"))
    }
    @Test fun migrationFromVersionEightPreservesExistingAccountsAndEntries() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = Json.parseToJsonElement(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("com.qimingnan.couple.data.db.AppDatabase/8.json").bufferedReader().readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val entity = value.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    sqlite.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            sqlite.execSQL("INSERT INTO ledger_accounts (id,name,type,initialBalanceCents,bankCode,ownerId,createdAt,updatedAt,deleted,pendingSync) VALUES ('old', '旧现金', 'CASH', 5000, NULL, 'test-owner', 1, 1, 0, 0)")
            sqlite.execSQL("INSERT INTO ledger_entries (id,ownerId,accountId,type,categoryId,amountCents,fromAccountId,toAccountId,occurredOn,createdAt,updatedAt,deleted,pendingSync,note) VALUES ('entry', 'test-owner', 'old', 'INCOME', '工资', 1000, NULL, NULL, '2026-11-07', 1, 1, 0, 0, '旧备注')")
            sqlite.version = 8
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_8_9).build()
        try {
            assertEquals(5000L, migrated.ledgerDao().accountById("old")!!.initialBalanceCents)
            val entry = migrated.ledgerDao().entryById("entry")!!
            assertEquals("旧备注", entry.note)
            assertNull(entry.loanId)
            assertNull(entry.loanAction)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
