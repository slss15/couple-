package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.qimingnan.couple.data.anniversary.AnniversaryCalendar
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryType
import kotlinx.coroutines.flow.Flow

/**
 * Anniversary plugin storage (PR3, DB v5). Only this table is added; no
 * existing table is touched. Records carry the sync-ready fields the plugin
 * host requires (owner/couple, timestamps, soft-delete, pending-sync), even
 * though sync itself is a later, server-deployed step and must not be
 * faked while no server is deployed.
 */
@Entity(tableName = "anniversary_events")
data class AnniversaryEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,
    val calendar: String,
    val month: Int,
    val day: Int,
    val isLeap: Boolean,
    val year: Int?,
    val recurring: Boolean,
    val onlyLeapMonth: Boolean,
    val reminderDaysAhead: Int,
    val note: String,
    val coupleId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val pendingSync: Boolean,
)

fun AnniversaryEvent.toEntity(coupleId: String?, nowMillis: Long, existing: AnniversaryEventEntity? = null) =
    AnniversaryEventEntity(
        id = id,
        title = title,
        type = type.name,
        calendar = calendar.name,
        month = month,
        day = day,
        isLeap = isLeap,
        year = year,
        recurring = recurring,
        onlyLeapMonth = onlyLeapMonth,
        reminderDaysAhead = reminderDaysAhead,
        note = note,
        coupleId = coupleId ?: existing?.coupleId,
        createdAt = existing?.createdAt ?: nowMillis,
        updatedAt = nowMillis,
        deleted = deleted,
        pendingSync = true,
    )

fun AnniversaryEventEntity.toModel() = AnniversaryEvent(
    id = id,
    title = title,
    type = AnniversaryType.entries.firstOrNull { it.name == type } ?: AnniversaryType.CUSTOM,
    calendar = AnniversaryCalendar.entries.firstOrNull { it.name == calendar } ?: AnniversaryCalendar.SOLAR,
    month = month,
    day = day,
    isLeap = isLeap,
    year = year,
    recurring = recurring,
    onlyLeapMonth = onlyLeapMonth,
    reminderDaysAhead = reminderDaysAhead,
    note = note,
    deleted = deleted,
)

@Dao
interface AnniversaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AnniversaryEventEntity)

    @Query("SELECT * FROM anniversary_events WHERE deleted = 0 ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AnniversaryEventEntity>>

    @Query("SELECT * FROM anniversary_events WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AnniversaryEventEntity?

    @Query("UPDATE anniversary_events SET deleted = 1, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
