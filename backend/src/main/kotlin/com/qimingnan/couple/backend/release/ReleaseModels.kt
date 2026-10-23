package com.qimingnan.couple.backend.release

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The `release.json` asset CI attaches to every GitHub Release (contract "Auto-update"). */
@Serializable
data class ReleaseManifest(
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val minSupportedVersionCode: Int = 0,
    val notes: String? = null,
    val publishedAt: Long = 0,
)

enum class ReleaseStatus { MIRRORED, PUBLISHED, WITHDRAWN }

/** One row of app_releases. */
data class AppRelease(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val minSupportedVersionCode: Int,
    val notes: String?,
    val publishedAt: Long,
    val filePath: String,
    val status: ReleaseStatus,
    val rolloutPercent: Int,
    val createdAt: Long,
)

/** `latest` in GET /app/update. */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val notes: String?,
    val downloadUrl: String,
    val publishedAt: Long,
)

@Serializable
data class UpdateResponse(val latest: UpdateInfo?, val force: Boolean)

/** Admin view of a release. */
@Serializable
data class AdminReleaseView(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val minSupportedVersionCode: Int,
    val notes: String?,
    val publishedAt: Long,
    val status: String,
    val rolloutPercent: Int,
    val createdAt: Long,
    val fileExists: Boolean,
)

/** PATCH /admin/releases/{versionCode}; every field optional. */
@Serializable
data class ReleasePatch(
    val status: String? = null,
    val rolloutPercent: Int? = null,
    val minSupportedVersionCode: Int? = null,
    val notes: String? = null,
)

/** State of the mirror job, returned by POST/GET /admin/releases/sync. */
@Serializable
data class SyncState(
    val running: Boolean,
    val lastRunAt: Long?,
    val lastFinishedAt: Long?,
    /** "ok" | "error" | null (never ran). */
    val lastResult: String?,
    val lastError: String?,
    /** versionCodes mirrored by the last run. */
    val mirrored: List<Int> = emptyList(),
    /** "versionCode: REASON" for releases rejected by the last run. */
    val rejected: List<String> = emptyList(),
)

/** Response of GET /admin/server/update-status. */
@Serializable
data class ServerUpdateStatus(
    val backend: BackendVersion,
    /** Last JSON the server-updater posted to /internal/updater/status, verbatim; null if it never reported. */
    val updater: JsonElement?,
    val updaterReportedAt: Long?,
)

@Serializable
data class BackendVersion(val version: String, val buildVersion: String, val revision: String?)
