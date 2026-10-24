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
    // ---- schema 2 (OTA v2, all optional so schema-1 manifests still parse) ----
    val schema: Int = 1,
    val packageName: String? = null,
    /** App<->app (partner) protocol this build speaks. */
    val protocolVersion: Int = 1,
    /** Oldest partner protocol this build understands. */
    val minPeerProtocol: Int = 1,
    val minSdk: Int = 0,
    /** "staged" (canary devices first) | "all"; null for schema-1 manifests (= all). */
    val rollout: String? = null,
)

enum class ReleaseStatus { MIRRORED, PUBLISHED, WITHDRAWN }

/** OTA v2 staged rollout: CANARY = only release settings' canaryDeviceIds; ALL = everyone (subject to rolloutPercent). */
enum class ReleaseStage { CANARY, ALL }

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
    /** Exact bytes of release.json as published by CI, base64 (null for releases mirrored before OTA v2). */
    val manifestB64: String? = null,
    /** release.json.sig verbatim (standard base64 Ed25519 signature over the manifest bytes), or null. */
    val manifestSig: String? = null,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
    val minSdk: Int = 0,
    val stage: ReleaseStage = ReleaseStage.ALL,
    /** Set by auto-pause (failed health report) or PATCH paused=true; paused releases are offered to nobody. */
    val pausedAt: Long? = null,
    val pauseReason: String? = null,
)

/** Last report of a device (device_reports). */
data class DeviceReport(
    val deviceId: String,
    val userId: String,
    val versionCode: Int,
    val protocolVersion: Int,
    val minPeerProtocol: Int,
    val sdkInt: Int?,
    val reportedAt: Long,
)

/** `peer` in GET /app/update. */
@Serializable
data class PeerView(
    val versionCode: Int,
    val protocolVersion: Int,
    val minPeerProtocol: Int,
    val reportedAt: Long,
    val compatible: Boolean,
)

/** POST /app/health-report. */
@Serializable
data class HealthReportRequest(
    val versionCode: Int,
    val fromVersionCode: Int? = null,
    val status: String,
    val mqttConnected: Boolean? = null,
    val detail: String? = null,
    val protocolVersion: Int? = null,
    val minPeerProtocol: Int? = null,
    val sdkInt: Int? = null,
)

@Serializable
data class HealthView(
    val versionCode: Int,
    val deviceId: String,
    val userId: String,
    val status: String,
    val fromVersionCode: Int?,
    val mqttConnected: Boolean?,
    val detail: String?,
    val createdAt: Long,
)

@Serializable
data class DeviceReportView(
    val deviceId: String,
    val userId: String,
    val versionCode: Int,
    val protocolVersion: Int,
    val minPeerProtocol: Int,
    val sdkInt: Int?,
    val reportedAt: Long,
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
    /** base64 of the exact release.json bytes; the app verifies [manifestSig] over them against its pinned key. */
    val manifest: String? = null,
    val manifestSig: String? = null,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
)

@Serializable
data class UpdateResponse(
    val latest: UpdateInfo?,
    val force: Boolean,
    /** null | MIN_SUPPORTED | PEER_PROTOCOL | PEER_NEWER */
    val reason: String? = null,
    val peer: PeerView? = null,
)

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
    /** A release.json.sig was mirrored with this release. */
    val signed: Boolean = false,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
    val minSdk: Int = 0,
    val stage: String = "ALL",
    val pausedAt: Long? = null,
    val pauseReason: String? = null,
)

/** PATCH /admin/releases/{versionCode}; every field optional. */
@Serializable
data class ReleasePatch(
    val status: String? = null,
    val rolloutPercent: Int? = null,
    val minSupportedVersionCode: Int? = null,
    val notes: String? = null,
    /** "CANARY" | "ALL" */
    val stage: String? = null,
    /** true = pause now (reason "manual"), false = resume. */
    val paused: Boolean? = null,
    /** Publish even if a reported device could not follow the forced update (PEER_CANNOT_FOLLOW). */
    val overridePeerCheck: Boolean = false,
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
