package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.SettingsRepo
import com.qimingnan.couple.backend.health.SERVER_VERSION
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** Pure update-selection rules (contract "Auto-update"), kept free of I/O so they are unit-testable. */
object UpdatePolicy {
    /**
     * Rollout bucket 0..99: first 4 bytes of SHA-256(UTF-8("$deviceId:$versionCode")) as an unsigned big-endian
     * integer, mod 100. A device is in the rollout of a release when bucket < rolloutPercent, so 0 % reaches nobody
     * and 100 % everybody, and raising the percentage only ever adds devices.
     */
    fun bucket(deviceId: String, versionCode: Int): Int {
        val d = MessageDigest.getInstance("SHA-256").digest("$deviceId:$versionCode".toByteArray(Charsets.UTF_8))
        val v = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return (v % 100).toInt()
    }

    data class Decision(val release: AppRelease?, val force: Boolean)

    /**
     * [published] = PUBLISHED releases (any order). force = current < minSupportedVersionCode of the newest published
     * release; a forced update always offers that newest release, ignoring rollout. Otherwise the newest published
     * release newer than [current] whose rollout includes the device is offered (an older fully-rolled-out release can
     * be offered while a newer one is still in a partial rollout), or none.
     */
    fun decide(published: List<AppRelease>, current: Int, deviceId: String): Decision {
        val sorted = published.filter { it.status == ReleaseStatus.PUBLISHED }.sortedByDescending { it.versionCode }
        val newest = sorted.firstOrNull() ?: return Decision(null, false)
        if (current < newest.minSupportedVersionCode) return Decision(newest, true)
        val pick = sorted.firstOrNull { it.versionCode > current && bucket(deviceId, it.versionCode) < it.rolloutPercent }
        return Decision(pick, false)
    }
}

class ReleaseService(private val db: Db, private val config: AppConfig) {

    fun downloadPath(versionCode: Int) = "/api/v1/app/releases/$versionCode/apk"

    suspend fun checkUpdate(current: Int, deviceId: String): UpdateResponse {
        val published = db.tx { ReleaseRepo.published() }.filter { File(it.filePath).isFile }
        val d = UpdatePolicy.decide(published, current, deviceId)
        val r = d.release ?: return UpdateResponse(null, false)
        return UpdateResponse(
            latest = UpdateInfo(
                versionName = r.versionName,
                versionCode = r.versionCode,
                sha256 = r.sha256,
                size = r.size,
                signingCertSha256 = r.signingCertSha256,
                notes = r.notes,
                downloadUrl = downloadPath(r.versionCode),
                publishedAt = r.publishedAt,
            ),
            force = d.force,
        )
    }

    /** The published release's file, or 404. */
    suspend fun publishedFile(versionCode: Int): Pair<AppRelease, File> {
        val r = db.tx { ReleaseRepo.byVersionCode(versionCode) }
        if (r == null || r.status != ReleaseStatus.PUBLISHED) throw notFound("RELEASE_NOT_FOUND", "No published release $versionCode")
        val f = File(r.filePath)
        if (!f.isFile) throw notFound("RELEASE_FILE_MISSING", "APK for $versionCode is missing on the server")
        return r to f
    }

    suspend fun list(): List<AdminReleaseView> = db.tx { ReleaseRepo.all() }.map { it.toAdminView() }

    /** Applies a PATCH; returns (before, after). */
    suspend fun patch(versionCode: Int, p: ReleasePatch): Pair<AdminReleaseView, AdminReleaseView> {
        val status = p.status?.let { s ->
            runCatching { ReleaseStatus.valueOf(s) }.getOrNull() ?: throw badRequest("VALIDATION_ERROR", "status must be MIRRORED, PUBLISHED or WITHDRAWN")
        }
        p.rolloutPercent?.let { if (it !in 0..100) throw badRequest("VALIDATION_ERROR", "rolloutPercent must be 0..100") }
        p.notes?.let { if (it.length > 10_000) throw badRequest("VALIDATION_ERROR", "notes too long") }
        return db.tx {
            val cur = ReleaseRepo.byVersionCode(versionCode, forUpdate = true) ?: throw notFound("RELEASE_NOT_FOUND", "No release $versionCode")
            p.minSupportedVersionCode?.let {
                if (it < 0 || it > cur.versionCode) throw badRequest("VALIDATION_ERROR", "minSupportedVersionCode must be 0..${cur.versionCode}")
            }
            if (status == ReleaseStatus.PUBLISHED && !File(cur.filePath).isFile) {
                throw badRequest("RELEASE_FILE_MISSING", "APK file is missing; re-sync before publishing")
            }
            val next = cur.copy(
                status = status ?: cur.status,
                rolloutPercent = p.rolloutPercent ?: cur.rolloutPercent,
                minSupportedVersionCode = p.minSupportedVersionCode ?: cur.minSupportedVersionCode,
                notes = p.notes ?: cur.notes,
            )
            ReleaseRepo.update(next)
            cur.toAdminView() to next.toAdminView()
        }
    }

    // ---- server-updater status ----

    /** Stores the updater's JSON; returns true when something other than lastCheck changed (worth an audit entry). */
    suspend fun storeUpdaterStatus(body: JsonObject): Boolean {
        val now = config.clock()
        return db.tx {
            val prev = SettingsRepo.get(KEY_UPDATER)?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            SettingsRepo.put(KEY_UPDATER, AppJson.encodeToString(JsonObject.serializer(), body), now)
            fun sig(o: JsonObject?) = o?.let { listOf(it["current"], it["lastResult"], it["lastError"]) }
            sig(prev) != sig(body)
        }
    }

    suspend fun serverUpdateStatus(): ServerUpdateStatus {
        val row = db.tx { SettingsRepo.getRow(KEY_UPDATER) }
        val json: JsonElement? = row?.first?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() }
        return ServerUpdateStatus(
            backend = BackendVersion(version = config.appVersion ?: SERVER_VERSION, buildVersion = SERVER_VERSION, revision = config.appRevision),
            updater = json,
            updaterReportedAt = row?.second,
        )
    }

    private fun AppRelease.toAdminView() = AdminReleaseView(
        id = id, versionCode = versionCode, versionName = versionName, sha256 = sha256, size = size,
        signingCertSha256 = signingCertSha256, minSupportedVersionCode = minSupportedVersionCode, notes = notes,
        publishedAt = publishedAt, status = status.name, rolloutPercent = rolloutPercent, createdAt = createdAt,
        fileExists = File(filePath).isFile,
    )

    companion object {
        const val KEY_UPDATER = "updater.status"
    }
}
