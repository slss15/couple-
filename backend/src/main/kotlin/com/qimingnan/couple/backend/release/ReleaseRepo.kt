package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.db.AppReleases
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** Must be called inside a transaction. */
object ReleaseRepo {
    private fun toRelease(r: ResultRow) = AppRelease(
        id = r[AppReleases.id],
        versionCode = r[AppReleases.versionCode],
        versionName = r[AppReleases.versionName],
        sha256 = r[AppReleases.sha256],
        size = r[AppReleases.size],
        signingCertSha256 = r[AppReleases.signingCertSha256],
        minSupportedVersionCode = r[AppReleases.minSupportedVersionCode],
        notes = r[AppReleases.notes],
        publishedAt = r[AppReleases.publishedAt],
        filePath = r[AppReleases.filePath],
        status = runCatching { ReleaseStatus.valueOf(r[AppReleases.status]) }.getOrDefault(ReleaseStatus.WITHDRAWN),
        rolloutPercent = r[AppReleases.rolloutPercent],
        createdAt = r[AppReleases.createdAt],
    )

    fun all(): List<AppRelease> =
        AppReleases.selectAll().orderBy(AppReleases.versionCode to SortOrder.DESC).map(::toRelease)

    fun published(): List<AppRelease> =
        AppReleases.selectAll().where { AppReleases.status eq ReleaseStatus.PUBLISHED.name }
            .orderBy(AppReleases.versionCode to SortOrder.DESC).map(::toRelease)

    fun byVersionCode(vc: Int, forUpdate: Boolean = false): AppRelease? {
        val q = AppReleases.selectAll().where { AppReleases.versionCode eq vc }
        if (forUpdate) q.forUpdate()
        return q.firstOrNull()?.let(::toRelease)
    }

    fun versionCodes(): Set<Int> = AppReleases.select(AppReleases.versionCode).map { it[AppReleases.versionCode] }.toSet()

    fun insert(r: AppRelease) {
        AppReleases.insert {
            it[id] = r.id
            it[versionCode] = r.versionCode
            it[versionName] = r.versionName
            it[sha256] = r.sha256
            it[size] = r.size
            it[signingCertSha256] = r.signingCertSha256
            it[minSupportedVersionCode] = r.minSupportedVersionCode
            it[notes] = r.notes
            it[publishedAt] = r.publishedAt
            it[filePath] = r.filePath
            it[status] = r.status.name
            it[rolloutPercent] = r.rolloutPercent
            it[createdAt] = r.createdAt
        }
    }

    fun update(r: AppRelease) {
        AppReleases.update({ AppReleases.versionCode eq r.versionCode }) {
            it[status] = r.status.name
            it[rolloutPercent] = r.rolloutPercent
            it[minSupportedVersionCode] = r.minSupportedVersionCode
            it[notes] = r.notes
        }
    }
}
