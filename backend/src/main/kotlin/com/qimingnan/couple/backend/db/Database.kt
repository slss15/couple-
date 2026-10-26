package com.qimingnan.couple.backend.db

import com.qimingnan.couple.backend.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

object Users : Table("users") {
    val id = varchar("id", 26)
    val username = varchar("username", 32)
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 64)
    val avatarUrl = varchar("avatar_url", 512).nullable()
    val role = varchar("role", 16)
    val coupleId = varchar("couple_id", 26).nullable()
    val createdAt = long("created_at")
    // V6 (TA page)
    val bio = varchar("bio", 200).nullable()
    val birthday = varchar("birthday", 5).nullable() // "MM-DD"
    val birthYear = short("birth_year").nullable()
    val showBirthday = bool("show_birthday").default(true)
    override val primaryKey = PrimaryKey(id)
}

/** Shared important days (V6, contract "TA"): anniversary-type dates, birthdays live on users. */
object ImportantDays : Table("important_days") {
    val id = varchar("id", 26)
    val coupleId = varchar("couple_id", 26)
    val name = varchar("name", 50)
    val month = short("date_month")   // month/day/year 是 H2 保留字，列名加 date_ 前缀
    val day = short("date_day")
    val year = short("date_year").nullable()
    val createdBy = varchar("created_by", 26)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = varchar("id", 26)
    val userId = varchar("user_id", 26)
    val familyId = varchar("family_id", 26)
    val tokenHash = varchar("token_hash", 64)
    val deviceId = varchar("device_id", 128)
    val deviceName = varchar("device_name", 128)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Couples : Table("couples") {
    val id = varchar("id", 26)
    val userA = varchar("user_a", 26)
    val userB = varchar("user_b", 26)
    val pairedAt = long("paired_at")
    val anniversary = long("anniversary").nullable()
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PairCodes : Table("pair_codes") {
    val userId = varchar("user_id", 26)
    val code = varchar("code", 6)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(userId)
}

object UnpairRequests : Table("unpair_requests") {
    val id = varchar("id", 26)
    val coupleId = varchar("couple_id", 26)
    val requestedBy = varchar("requested_by", 26)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val confirmedBy = varchar("confirmed_by", 26).nullable()
    val confirmedAt = long("confirmed_at").nullable()
    val cancelledAt = long("cancelled_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ServerSettings : Table("server_settings") {
    val key = varchar("setting_key", 64)
    val value = text("setting_value")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(key)
}

object AuditLog : Table("audit_log") {
    val id = varchar("id", 26)
    val actorId = varchar("actor_id", 26).nullable()
    val action = varchar("action", 64)
    val target = varchar("target", 128).nullable()
    val meta = text("meta").nullable()
    val ts = long("ts")
    override val primaryKey = PrimaryKey(id)
}

object AppReleases : Table("app_releases") {
    val id = varchar("id", 26)
    val versionCode = integer("version_code")
    val versionName = varchar("version_name", 32)
    val sha256 = varchar("sha256", 64)
    val size = long("size")
    val signingCertSha256 = varchar("signing_cert_sha256", 64)
    val minSupportedVersionCode = integer("min_supported_version_code")
    val notes = text("notes").nullable()
    val publishedAt = long("published_at")
    val filePath = varchar("file_path", 512)
    val status = varchar("status", 16)
    val rolloutPercent = integer("rollout_percent")
    val createdAt = long("created_at")
    // V3 (OTA v2): signed manifest + partner protocol gate
    val manifestB64 = text("manifest_b64").nullable()
    val manifestSig = varchar("manifest_sig", 128).nullable()
    val protocolVersion = integer("protocol_version").default(1)
    val minPeerProtocol = integer("min_peer_protocol").default(1)
    val minSdk = integer("min_sdk").default(0)
    // V4 (OTA v2): staged rollout + auto-pause
    val stage = varchar("stage", 16).default("ALL")
    val pausedAt = long("paused_at").nullable()
    val pauseReason = varchar("pause_reason", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Last app version/protocol each device reported (V4, OTA v2 partner gate). */
object DeviceReports : Table("device_reports") {
    val deviceId = varchar("device_id", 128)
    val userId = varchar("user_id", 26)
    val versionCode = integer("version_code")
    val protocolVersion = integer("protocol_version").default(1)
    val minPeerProtocol = integer("min_peer_protocol").default(1)
    val sdkInt = integer("sdk_int").nullable()
    val reportedAt = long("reported_at")
    override val primaryKey = PrimaryKey(deviceId)
}

/** Uploaded media objects (V5): images for feed posts and avatars; couple-scoped (contract "Media storage"). */
object MediaObjects : Table("media_objects") {
    val storageKey = varchar("storage_key", 26)
    val coupleId = varchar("couple_id", 26)
    val purpose = varchar("purpose", 16).default("post")
    val mimeType = varchar("mime_type", 64)
    val size = long("size")
    val width = integer("width")
    val height = integer("height")
    val thumbKey = varchar("thumb_key", 26).nullable()
    val usedByPost = varchar("used_by_post", 26).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(storageKey)
}

/** Feed posts (V5, contract "Feed"). */
object Posts : Table("posts") {
    val id = varchar("id", 26)
    val coupleId = varchar("couple_id", 26)
    val authorId = varchar("author_id", 26)
    val text = varchar("text", 4000)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PostLikes : Table("post_likes") {
    val postId = varchar("post_id", 26)
    val userId = varchar("user_id", 26)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(postId, userId)
}

object PostComments : Table("post_comments") {
    val id = varchar("id", 26)
    val postId = varchar("post_id", 26)
    val authorId = varchar("author_id", 26)
    val text = varchar("text", 1000)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Chat messages (V7, contract "Chat"). `createdAt` is server time and the sort key; ties break on id. */
object ChatMessages : Table("chat_messages") {
    val id = varchar("id", 26)
    val coupleId = varchar("couple_id", 26)
    val senderId = varchar("sender_id", 26)
    val msgType = varchar("msg_type", 16).default("text")
    val text = varchar("text", 4000)
    val replyTo = varchar("reply_to", 26).nullable()
    val clientTs = long("client_ts")
    val createdAt = long("created_at")
    val recalledAt = long("recalled_at").nullable()
    /** V8: image / sticker messages reference a media object (Feed media storage). */
    val mediaKey = varchar("media_key", 26).nullable()
    val mediaWidth = integer("media_width").nullable()
    val mediaHeight = integer("media_height").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Per-user read cursor (V7): the newest message id each member has read, monotonic. */
object ChatReads : Table("chat_reads") {
    val coupleId = varchar("couple_id", 26)
    val userId = varchar("user_id", 26)
    val upToId = varchar("up_to_id", 26)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(coupleId, userId)
}

/** Post-install health reports (V4). */
object ReleaseHealth : Table("release_health") {
    val id = varchar("id", 26)
    val versionCode = integer("version_code")
    val deviceId = varchar("device_id", 128)
    val userId = varchar("user_id", 26)
    val status = varchar("status", 16)
    val fromVersionCode = integer("from_version_code").nullable()
    val mqttConnected = bool("mqtt_connected").nullable()
    val detail = varchar("detail", 512).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

class Db(val dataSource: HikariDataSource, val database: Database) {
    suspend fun <T> tx(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database, Connection.TRANSACTION_READ_COMMITTED) { block() }

    fun close() = dataSource.close()

    companion object {
        fun connect(config: AppConfig): Db {
            val hc = HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.dbUser
                password = config.dbPassword
                maximumPoolSize = config.dbMaxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "couple-db"
            }
            val ds = HikariDataSource(hc)
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            return Db(ds, Database.connect(ds))
        }
    }
}
