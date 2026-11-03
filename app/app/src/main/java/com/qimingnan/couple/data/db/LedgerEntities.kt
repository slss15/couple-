package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerAccountType
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerEntryType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Ordinary personal ledger storage (PR4, DB v6). Only these two tables are
 * added; no existing table is touched. This is personal, private data: it
 * never goes to feed, chat or TA, and the owner id is the logged-in user id.
 * Records carry the sync-ready fields the plugin host requires, even though
 * sync itself is a later, server-deployed step.
 */
@Entity(tableName = "ledger_accounts")
data class LedgerAccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val initialBalanceCents: Long,
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val pendingSync: Boolean,
)

@Entity(tableName = "ledger_entries")
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val accountId: String?,
    val type: String,
    val categoryId: String?,
    val amountCents: Long,
    val fromAccountId: String?,
    val toAccountId: String?,
    /** ISO date, yyyy-MM-dd, so Room needs no type converter. */
    val occurredOn: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val pendingSync: Boolean,
)

fun LedgerAccount.toEntity(ownerId: String, nowMillis: Long, existing: LedgerAccountEntity? = null) =
    LedgerAccountEntity(id, name, type.name, initialBalanceCents, ownerId, existing?.createdAt ?: nowMillis, nowMillis, false, true)

fun LedgerAccountEntity.toModel() = LedgerAccount(
    id, name, LedgerAccountType.entries.firstOrNull { it.name == type } ?: LedgerAccountType.OTHER, initialBalanceCents,
)

fun LedgerEntry.toEntity(nowMillis: Long, existing: LedgerEntryEntity? = null) =
    LedgerEntryEntity(
        id, ownerId, accountId, type.name, categoryId, amountCents, fromAccountId, toAccountId,
        occurredOn.toString(), existing?.createdAt ?: nowMillis, nowMillis, deleted, true,
    )

fun LedgerEntryEntity.toModel() = LedgerEntry(
    id, ownerId, accountId,
    LedgerEntryType.entries.firstOrNull { it.name == type } ?: LedgerEntryType.EXPENSE,
    categoryId, amountCents, fromAccountId, toAccountId, LocalDate.parse(occurredOn), deleted,
)

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: LedgerAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: LedgerEntryEntity)

    @Query("SELECT * FROM ledger_accounts WHERE deleted = 0 ORDER BY createdAt ASC")
    fun observeAccounts(): Flow<List<LedgerAccountEntity>>

    @Query("SELECT * FROM ledger_entries WHERE deleted = 0 ORDER BY occurredOn DESC, createdAt DESC")
    fun observeEntries(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_accounts WHERE id = :id LIMIT 1")
    suspend fun accountById(id: String): LedgerAccountEntity?

    @Query("UPDATE ledger_entries SET deleted = 1, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun softDeleteEntry(id: String, now: Long)

    @Query("UPDATE ledger_accounts SET deleted = 1, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun softDeleteAccount(id: String, now: Long)
}
