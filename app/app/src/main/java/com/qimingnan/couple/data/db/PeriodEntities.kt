package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.qimingnan.couple.data.period.PeriodLog
import kotlinx.coroutines.flow.Flow

/**
 * 经期记录插件表（Room v7，contract-free PERSONAL 插件；数据隔离契约见 data/plugin/Plugin.kt）：
 * ownerId 冗余存本人 id（与 ledger 一致），软删 + pendingSync 与 feed/chat outbox 模式一致。
 */
@Entity(tableName = "period_logs")
data class PeriodLogEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    /** ISO yyyy-MM-dd */
    val startDate: String,
    /** ISO；null = 进行中 */
    val endDate: String?,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val pendingSync: Boolean,
)

fun PeriodLogEntity.toModel() = PeriodLog(
    id = id, startDate = startDate, endDate = endDate, note = note,
    createdAt = createdAt, updatedAt = updatedAt, deleted = deleted,
)

@Dao
interface PeriodDao {
    @Query("SELECT * FROM period_logs WHERE deleted = 0 ORDER BY startDate ASC")
    fun observeAll(): Flow<List<PeriodLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: PeriodLogEntity)

    @Query("UPDATE period_logs SET deleted = 1, updatedAt = :now, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
