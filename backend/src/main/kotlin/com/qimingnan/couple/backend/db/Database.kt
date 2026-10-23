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
