package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.db.AppReleases
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.DeviceReports
import com.qimingnan.couple.backend.db.ReleaseHealth
import com.qimingnan.couple.backend.db.SettingsRepo
import com.qimingnan.couple.backend.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** OTA v2 persistence helpers. Must be called inside a transaction. */
object DeviceReportRepo {
    private fun toReport(r: ResultRow) = DeviceReport(
        deviceId = r[DeviceReports.deviceId], userId = r[DeviceReports.userId], versionCode = r[DeviceReports.versionCode],
        protocolVersion = r[DeviceReports.protocolVersion], minPeerProtocol = r[DeviceReports.minPeerProtocol],
        sdkInt = r[DeviceReports.sdkInt], reportedAt = r[DeviceReports.reportedAt],
    )

    fun upsert(r: DeviceReport) {
        val n = DeviceReports.update({ DeviceReports.deviceId eq r.deviceId }) {
            it[userId] = r.userId
            it[versionCode] = r.versionCode
            it[protocolVersion] = r.protocolVersion
            it[minPeerProtocol] = r.minPeerProtocol
            // Keep a known sdkInt when a later report omits it.
            if (r.sdkInt != null) it[sdkInt] = r.sdkInt
            it[reportedAt] = r.reportedAt
        }
        if (n == 0) {
            DeviceReports.insert {
                it[deviceId] = r.deviceId
                it[userId] = r.userId
                it[versionCode] = r.versionCode
                it[protocolVersion] = r.protocolVersion
                it[minPeerProtocol] = r.minPeerProtocol
                it[sdkInt] = r.sdkInt
                it[reportedAt] = r.reportedAt
            }
        }
    }

    /** Most recent report of any device of [userId]. */
    fun latestForUser(userId: String): DeviceReport? =
        DeviceReports.selectAll().where { DeviceReports.userId eq userId }
            .orderBy(DeviceReports.reportedAt to SortOrder.DESC).limit(1).firstOrNull()?.let(::toReport)

    fun since(ts: Long): List<DeviceReport> =
        DeviceReports.selectAll().where { DeviceReports.reportedAt greaterEq ts }
            .orderBy(DeviceReports.reportedAt to SortOrder.DESC).map(::toReport)

    fun all(): List<DeviceReport> = DeviceReports.selectAll().orderBy(DeviceReports.reportedAt to SortOrder.DESC).map(::toReport)
}

object ReleaseHealthRepo {
    fun insert(id: String, versionCode: Int, deviceId: String, userId: String, req: HealthReportRequest, now: Long) {
        ReleaseHealth.insert {
            it[ReleaseHealth.id] = id
            it[ReleaseHealth.versionCode] = versionCode
            it[ReleaseHealth.deviceId] = deviceId
            it[ReleaseHealth.userId] = userId
            it[status] = req.status
            it[fromVersionCode] = req.fromVersionCode
            it[mqttConnected] = req.mqttConnected
            it[detail] = req.detail?.take(500)
            it[createdAt] = now
        }
    }

    fun hasFailure(versionCode: Int): Boolean =
        ReleaseHealth.selectAll().where { (ReleaseHealth.versionCode eq versionCode) and (ReleaseHealth.status neq "OK") }.limit(1).any()

    fun hasOk(versionCode: Int): Boolean =
        ReleaseHealth.selectAll().where { (ReleaseHealth.versionCode eq versionCode) and (ReleaseHealth.status eq "OK") }.limit(1).any()

    fun forVersion(versionCode: Int, limit: Int = 100): List<HealthView> =
        ReleaseHealth.selectAll().where { ReleaseHealth.versionCode eq versionCode }
            .orderBy(ReleaseHealth.createdAt to SortOrder.DESC).limit(limit).map {
                HealthView(
                    versionCode = it[ReleaseHealth.versionCode], deviceId = it[ReleaseHealth.deviceId], userId = it[ReleaseHealth.userId],
                    status = it[ReleaseHealth.status], fromVersionCode = it[ReleaseHealth.fromVersionCode],
                    mqttConnected = it[ReleaseHealth.mqttConnected], detail = it[ReleaseHealth.detail], createdAt = it[ReleaseHealth.createdAt],
                )
            }
}

/** Anti-downgrade high-water mark: the highest versionCode ever PUBLISHED (server_settings `release.highWater`). */
object HighWater {
    const val KEY = "release.highWater"

    fun get(): Int {
        val stored = SettingsRepo.get(KEY)?.toIntOrNull() ?: 0
        val published = AppReleases.select(AppReleases.versionCode.max())
            .where { AppReleases.status eq ReleaseStatus.PUBLISHED.name }
            .firstOrNull()?.get(AppReleases.versionCode.max()) ?: 0
        return maxOf(stored, published)
    }

    fun raise(versionCode: Int, now: Long) {
        val stored = SettingsRepo.getForUpdate(KEY)?.toIntOrNull() ?: 0
        if (versionCode > stored) SettingsRepo.put(KEY, versionCode.toString(), now)
    }
}

/** The other member of the user's (non-deleted) couple, or null. */
object CouplePeer {
    fun peerOf(userId: String): String? {
        val cid = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.coupleId) ?: return null
        val c = Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.firstOrNull() ?: return null
        return when (userId) {
            c[Couples.userA] -> c[Couples.userB]
            c[Couples.userB] -> c[Couples.userA]
            else -> null
        }
    }
}
