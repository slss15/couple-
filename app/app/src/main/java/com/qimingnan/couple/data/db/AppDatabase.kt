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

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>

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

@Database(
    entities = [
        UserEntity::class, CoupleEntity::class, PostEntity::class, PostMediaEntity::class, CommentEntity::class,
        ChatMessageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun feedDao(): FeedDao
    abstract fun chatDao(): ChatDao

    companion object {
        /** v1 → v2：feed 缓存表（纯新增，旧表不动）。 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `posts` (`id` TEXT NOT NULL, `authorId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `likeCount` INTEGER NOT NULL, `likedByMe` INTEGER NOT NULL, " +
                        "`pendingState` TEXT, `pendingImages` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_media` (`postId` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`storageKey` TEXT NOT NULL, `thumbKey` TEXT, `mimeType` TEXT NOT NULL, `width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL, `size` INTEGER NOT NULL, PRIMARY KEY(`postId`, `sortOrder`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_comments` (`id` TEXT NOT NULL, `postId` TEXT NOT NULL, " +
                        "`authorId` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`postId`) REFERENCES `posts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_comments_postId` ON `post_comments` (`postId`)")
            }
        }

        /** v2 → v3：聊天缓存表（纯新增，旧表不动）。 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `senderId` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `replyTo` TEXT, `clientTs` INTEGER NOT NULL, `createdAt` INTEGER, " +
                        "`sortAt` INTEGER NOT NULL, `recalledAt` INTEGER, `pendingState` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sortAt` ON `chat_messages` (`sortAt`)")
            }
        }
    }
}
