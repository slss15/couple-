package com.qimingnan.couple.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.User
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val coupleId: String?,
    val createdAt: Long,
    /** 是否是当前登录的本人 */
    val isSelf: Boolean,
)

@Entity(tableName = "couples")
data class CoupleEntity(
    @PrimaryKey val id: String,
    val memberA: String,
    val memberB: String,
    val pairedAt: Long,
    val anniversary: Long?,
)

fun User.toEntity(isSelf: Boolean) = UserEntity(id, username, displayName, avatarUrl, coupleId, createdAt, isSelf)
fun UserEntity.toModel() = User(id, username, displayName, avatarUrl, coupleId, createdAt)

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE isSelf = 1 LIMIT 1")
    suspend fun self(): UserEntity?

    @Query("SELECT * FROM users WHERE isSelf = 1 LIMIT 1")
    fun observeSelf(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun user(id: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCouple(couple: CoupleEntity)

    @Query("SELECT * FROM couples LIMIT 1")
    suspend fun couple(): CoupleEntity?

    @Query("DELETE FROM couples")
    suspend fun clearCouples()

    @Query("DELETE FROM users WHERE isSelf = 0")
    suspend fun clearOthers()

    @Query("DELETE FROM users")
    suspend fun clearUsers()

    @Transaction
    suspend fun replaceSelf(user: User) {
        clearUsers()
        upsertUsers(listOf(user.toEntity(isSelf = true)))
    }

    @Transaction
    suspend fun saveCouple(couple: Couple, selfId: String) {
        clearCouples()
        clearOthers()
        upsertUsers(couple.members.map { it.toEntity(isSelf = it.id == selfId) })
        val ids = couple.members.map { it.id }
        upsertCouple(CoupleEntity(couple.id, ids.getOrElse(0) { "" }, ids.getOrElse(1) { "" }, couple.pairedAt, couple.anniversary))
    }

    @Transaction
    suspend fun clearCouple() {
        clearCouples()
        clearOthers()
    }

    @Transaction
    suspend fun clearAll() {
        clearCouples()
        clearUsers()
    }
}

@Database(entities = [UserEntity::class, CoupleEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}
