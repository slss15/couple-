package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.conflict
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

    data class Decision(val release: AppRelease?, val force: Boolean, val reason: String? = null)

    const val REASON_MIN_SUPPORTED = "MIN_SUPPORTED"
    const val REASON_PEER_PROTOCOL = "PEER_PROTOCOL"
    const val REASON_PEER_NEWER = "PEER_NEWER"

    /**
     * [published] = PUBLISHED releases (any order). Paused releases are ignored entirely; CANARY-stage releases are
     * visible only when [canary]. newest = the highest visible release.
     * 1. force (MIN_SUPPORTED) when current < newest.minSupportedVersionCode; offers newest, ignoring rollout.
     * 2. force (PEER_PROTOCOL) when the partner requires a protocol this build cannot speak
     *    ([selfProtocol] < peer.minPeerProtocol): offers the highest non-paused release newer than [current] that speaks
     *    it, ignoring rollout and stage (the partner already runs it).
     * 3. otherwise the newest visible release newer than [current] whose rollout includes the device (an older
     *    fully-rolled-out release can be offered while a newer one is still in a partial rollout), or none;
     *    reason PEER_NEWER when the partner already runs a newer version.
     */
    fun decide(
        published: List<AppRelease>,
        current: Int,
        deviceId: String,
        selfProtocol: Int = 1,
        peer: DeviceReport? = null,
        canary: Boolean = false,
    ): Decision {
        val live = published.filter { it.status == ReleaseStatus.PUBLISHED && it.pausedAt == null }.sortedByDescending { it.versionCode }
        val visible = live.filter { it.stage == ReleaseStage.ALL || canary }
        val newest = visible.firstOrNull()
        if (newest != null && current < newest.minSupportedVersionCode) return Decision(newest, true, REASON_MIN_SUPPORTED)
        if (peer != null && selfProtocol < peer.minPeerProtocol) {
            val target = live.firstOrNull { it.versionCode > current && it.protocolVersion >= peer.minPeerProtocol }
            if (target != null) return Decision(target, true, REASON_PEER_PROTOCOL)
        }
        val pick = visible.firstOrNull { it.versionCode > current && bucket(deviceId, it.versionCode) < it.rolloutPercent }
        val reason = if (pick != null && peer != null && peer.versionCode > current) REASON_PEER_NEWER else null
        return Decision(pick, false, reason)
    }

    /** Devices (reported recently) that a publish of [r] would force but that cannot install it (sdkInt < minSdk). */
    fun cannotFollow(r: AppRelease, reports: List<DeviceReport>): List<DeviceReport> =
        if (r.minSdk <= 0) emptyList() else reports.filter { d ->
            d.sdkInt != null && d.sdkInt < r.minSdk &&
                (d.versionCode < r.minSupportedVersionCode || d.protocolVersion < r.minPeerProtocol)
        }
}

class ReleaseService(
    private val db: Db,
    private val config: AppConfig,
    private val settings: ReleaseSettingsService? = null,
    private val audit: AuditService? = null,
) {

    fun downloadPath(versionCode: Int) = "/api/v1/app/releases/$versionCode/apk"

    /**
     * GET /app/update. [bucketDeviceId] only selects the rollout bucket; the device report and canary membership use
     * the authenticated [caller]'s token did (a client cannot report for, or impersonate, another device).
     */
    suspend fun checkUpdate(
        current: Int,
        bucketDeviceId: String,
        caller: AuthUser? = null,
        selfProtocol: Int? = null,
        selfMinPeer: Int? = null,
        sdkInt: Int? = null,
    ): UpdateResponse {
        validateProtocol(selfProtocol, selfMinPeer, sdkInt)
        val now = config.clock()
        val proto = selfProtocol ?: 1
        val minPeer = selfMinPeer ?: 1
        val canaryIds = settings?.get()?.canaryDeviceIds.orEmpty()
        val (all, peer) = db.tx {
            if (caller != null && caller.deviceId.isNotEmpty()) {
                DeviceReportRepo.upsert(DeviceReport(caller.deviceId, caller.userId, current, proto, minPeer, sdkInt, now))
            }
            val peer = caller?.let { CouplePeer.peerOf(it.userId) }?.let { DeviceReportRepo.latestForUser(it) }
            ReleaseRepo.published() to peer
        }
        val published = all.filter { File(it.filePath).isFile }
        val canary = caller != null && caller.deviceId in canaryIds
        val d = UpdatePolicy.decide(published, current, bucketDeviceId, proto, peer, canary)
        val peerView = peer?.let {
            PeerView(it.versionCode, it.protocolVersion, it.minPeerProtocol, it.reportedAt, proto >= it.minPeerProtocol && it.protocolVersion >= minPeer)
        }
        val r = d.release ?: return UpdateResponse(null, false, null, peerView)
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
                manifest = r.manifestB64,
                manifestSig = r.manifestSig,
                protocolVersion = r.protocolVersion,
                minPeerProtocol = r.minPeerProtocol,
            ),
            force = d.force,
            reason = d.reason,
            peer = peerView,
        )
    }

    private fun validateProtocol(proto: Int?, minPeer: Int?, sdkInt: Int?) {
        if (proto != null && proto !in 1..1000) throw badRequest("VALIDATION_ERROR", "protocolVersion must be 1..1000")
        if (minPeer != null && minPeer !in 1..1000) throw badRequest("VALIDATION_ERROR", "minPeerProtocol must be 1..1000")
        if (sdkInt != null && sdkInt !in 1..1000) throw badRequest("VALIDATION_ERROR", "sdkInt must be 1..1000")
    }

    /** POST /app/health-report: store, refresh the device report, auto-pause on failure, promote CANARY on success. */
    suspend fun healthReport(caller: AuthUser, req: HealthReportRequest) {
        if (req.status !in HEALTH_STATUSES) throw badRequest("VALIDATION_ERROR", "status must be OK, CRASH or FAILED")
        if (req.versionCode <= 0) throw badRequest("VALIDATION_ERROR", "versionCode must be positive")
        if ((req.detail?.length ?: 0) > 500) throw badRequest("VALIDATION_ERROR", "detail too long")
        validateProtocol(req.protocolVersion, req.minPeerProtocol, req.sdkInt)
        val now = config.clock()
        val canary = caller.deviceId in settings?.get()?.canaryDeviceIds.orEmpty()
        val event: Pair<String, String?>? = db.tx {
            DeviceReportRepo.upsert(
                DeviceReport(caller.deviceId, caller.userId, req.versionCode, req.protocolVersion ?: 1, req.minPeerProtocol ?: 1, req.sdkInt, now),
            )
            ReleaseHealthRepo.insert(Ids.newId(), req.versionCode, caller.deviceId, caller.userId, req, now)
            val r = ReleaseRepo.byVersionCode(req.versionCode, forUpdate = true) ?: return@tx null
            if (req.status != "OK") {
                if (r.status == ReleaseStatus.PUBLISHED && r.pausedAt == null) {
                    val reason = "${req.status} from device ${caller.deviceId}: ${req.detail ?: "-"}".take(500)
                    ReleaseRepo.update(r.copy(pausedAt = now, pauseReason = reason))
                    "paused" to reason
                } else null
            } else if (r.stage == ReleaseStage.CANARY && r.pausedAt == null && canary && !ReleaseHealthRepo.hasFailure(r.versionCode)) {
                ReleaseRepo.update(r.copy(stage = ReleaseStage.ALL))
                "promoted" to null
            } else null
        }
        val target = "release:${req.versionCode}"
        val meta = mapOf("deviceId" to caller.deviceId, "status" to req.status, "detail" to req.detail?.take(200))
        if (req.status != "OK") audit?.log(caller.userId, AuditActions.RELEASE_HEALTH_FAILED, target, meta)
        when (event?.first) {
            "paused" -> {
                log.warn("Auto-paused release {}: {}", req.versionCode, event.second)
                audit?.log(null, AuditActions.RELEASE_AUTO_PAUSED, target, meta + ("reason" to event.second))
            }
            "promoted" -> audit?.log(null, AuditActions.RELEASE_PROMOTED, target, mapOf("deviceId" to caller.deviceId))
        }
    }

    suspend fun devices(): List<DeviceReportView> = db.tx { DeviceReportRepo.all() }.map {
        DeviceReportView(it.deviceId, it.userId, it.versionCode, it.protocolVersion, it.minPeerProtocol, it.sdkInt, it.reportedAt)
    }

    suspend fun health(versionCode: Int): List<HealthView> = db.tx { ReleaseHealthRepo.forVersion(versionCode) }

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
        val stage = p.stage?.let { st ->
            runCatching { ReleaseStage.valueOf(st) }.getOrNull() ?: throw badRequest("VALIDATION_ERROR", "stage must be CANARY or ALL")
        }
        val now = config.clock()
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
                stage = stage ?: cur.stage,
                pausedAt = when (p.paused) { true -> cur.pausedAt ?: now; false -> null; null -> cur.pausedAt },
                pauseReason = when (p.paused) { true -> cur.pauseReason ?: "manual"; false -> null; null -> cur.pauseReason },
            )
            if (next.status == ReleaseStatus.PUBLISHED) {
                if (cur.status != ReleaseStatus.PUBLISHED) {
                    val hw = HighWater.get()
                    if (next.versionCode < hw) {
                        throw conflict("DOWNGRADE_REFUSED", "versionCode ${next.versionCode} is below the highest published $hw; ship a newer version instead")
                    }
                }
                val forcing = cur.status != ReleaseStatus.PUBLISHED || next.minSupportedVersionCode != cur.minSupportedVersionCode
                if (forcing && !p.overridePeerCheck) {
                    val blocked = UpdatePolicy.cannotFollow(next, DeviceReportRepo.since(now - PEER_REPORT_MAX_AGE_MS))
                    if (blocked.isNotEmpty()) {
                        throw conflict(
                            "PEER_CANNOT_FOLLOW",
                            "minSdk ${next.minSdk} would force devices that cannot install it: " +
                                blocked.joinToString { "${it.deviceId} (sdk ${it.sdkInt}, v${it.versionCode})" },
                        )
                    }
                }
                HighWater.raise(next.versionCode, now)
            }
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
        signed = manifestSig != null,
        protocolVersion = protocolVersion, minPeerProtocol = minPeerProtocol, minSdk = minSdk,
        stage = stage.name, pausedAt = pausedAt, pauseReason = pauseReason,
    )

    private val log = org.slf4j.LoggerFactory.getLogger(ReleaseService::class.java)

    companion object {
        const val KEY_UPDATER = "updater.status"
        val HEALTH_STATUSES = setOf("OK", "CRASH", "FAILED")
        /** Device reports older than this are ignored by the PEER_CANNOT_FOLLOW check. */
        const val PEER_REPORT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
