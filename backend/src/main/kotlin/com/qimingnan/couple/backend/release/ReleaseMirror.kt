package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors APKs from GitHub Releases into [AppConfig.releasesDir] (outbound only).
 *
 * For each of the newest 5 non-draft, non-prerelease releases tagged vX.Y.Z that are not in app_releases yet:
 * download release.json, validate it, download couple-X.Y.Z.apk, verify size + sha256, read the signing cert out of
 * the APK and require it to equal release.json's signingCertSha256 and the pinned cert (pinning it on first use),
 * then store the row as MIRRORED (or PUBLISHED when release_auto_publish is on). Everything is audit-logged.
 */
class ReleaseMirror(
    private val db: Db,
    private val config: AppConfig,
    private val settings: ReleaseSettingsService,
    private val audit: AuditService,
    private val http: OutboundHttp = OutboundHttp(config.outboundProxy, config.noProxy, config.releaseRetryBaseMs, config.releaseRetryAttempts),
) {
    private val log = LoggerFactory.getLogger(ReleaseMirror::class.java)
    private val mutex = Mutex()

    /** Releases rejected for a permanent reason (bad metadata / cert); skipped by scheduled runs until restart. */
    private val permanentlyRejected = ConcurrentHashMap<String, String>()

    @Volatile
    var state = SyncState(running = false, lastRunAt = null, lastFinishedAt = null, lastResult = null, lastError = null)
        private set

    class Rejected(val reason: String, message: String) : Exception(message)

    val releasesDir: File get() = File(config.releasesDir)

    /** Runs one mirror pass now (serialised; a second caller waits for the running pass). */
    suspend fun syncNow(manual: Boolean = false): SyncState = mutex.withLock {
        val started = config.clock()
        state = state.copy(running = true, lastRunAt = started)
        val mirrored = mutableListOf<Int>()
        val rejected = mutableListOf<String>()
        try {
            if (manual) permanentlyRejected.clear()
            withContext(Dispatchers.IO) { runPass(mirrored, rejected) }
            state = SyncState(false, started, config.clock(), "ok", null, mirrored, rejected)
        } catch (e: Exception) {
            log.warn("Release mirror pass failed: {}", e.toString())
            audit.log(null, AuditActions.RELEASE_SYNC_FAILED, "github", mapOf("error" to e.message?.take(300)))
            state = SyncState(false, started, config.clock(), "error", e.message ?: e.javaClass.simpleName, mirrored, rejected)
        }
        state
    }

    /** Starts the periodic job (interval re-read from settings every cycle; 0 = paused). */
    fun startScheduler(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(config.releasePollInitialDelayMs)
        while (isActive) {
            val minutes = runCatching { settings.get().pollMinutes }.getOrDefault(30)
            if (minutes > 0) syncNow()
            delay(maxOf(minutes, 5) * 60_000L)
        }
    }

    private fun ghHeaders(token: String, accept: String) = buildMap {
        put("Accept", accept)
        put("User-Agent", "couple-backend-release-mirror")
        put("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) put("Authorization", "Bearer $token")
    }

    private suspend fun runPass(mirrored: MutableList<Int>, rejected: MutableList<String>) {
        val s = settings.get()
        val api = config.githubApiBase.trimEnd('/')
        val listJson = http.getString("$api/repos/${s.repo}/releases?per_page=5", ghHeaders(s.githubToken, "application/vnd.github+json"))
        val releases = AppJson.parseToJsonElement(listJson) as? JsonArray ?: throw IllegalStateException("unexpected GitHub response")
        val known = db.tx { ReleaseRepo.versionCodes() }
        releasesDir.mkdirs()

        for (el in releases) {
            val rel = el as? JsonObject ?: continue
            if (rel["draft"]?.jsonPrimitive?.booleanOrNull == true || rel["prerelease"]?.jsonPrimitive?.booleanOrNull == true) continue
            val tag = rel["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val version = TAG.matchEntire(tag)?.groupValues?.get(1) ?: continue
            val assets = rel["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            fun asset(name: String) = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }
            val manifestAsset = asset("release.json") ?: continue
            val apkAsset = asset("couple-$version.apk") ?: continue
            val sigAsset = asset("release.json.sig")

            val manifestBytes = download(manifestAsset, s.githubToken) { it.readNBytes(MAX_MANIFEST + 1) }
            if (manifestBytes.size > MAX_MANIFEST) {
                reject(tag, "INVALID_METADATA", "release.json too large", permanent = true, rejected = rejected, key = "$tag:too-large")
                continue
            }
            val manifestText = manifestBytes.toString(Charsets.UTF_8)
            val manifest = try {
                AppJson.decodeFromString(ReleaseManifest.serializer(), manifestText)
            } catch (e: Exception) {
                reject(tag, "INVALID_METADATA", "release.json unparsable", permanent = true, rejected = rejected, key = "$tag:${manifestText.hashCode()}")
                continue
            }
            if (manifest.versionCode in known) continue
            val key = "${manifest.versionCode}:${manifest.sha256}"
            if (permanentlyRejected.containsKey(key)) continue
            try {
                val sig = sigAsset?.let { a ->
                    val b = download(a, s.githubToken) { it.readNBytes(MAX_SIG + 1) }
                    if (b.size > MAX_SIG) throw Rejected("SIGNATURE_INVALID", "release.json.sig too large")
                    b.toString(Charsets.UTF_8).trim()
                }
                val row = mirrorOne(tag, version, manifest, manifestBytes, sig, apkAsset, s)
                mirrored += row.versionCode
                log.info("Mirrored release {} ({}) as {}", row.versionName, row.versionCode, row.status)
            } catch (e: Rejected) {
                val permanent = e.reason != "SHA256_MISMATCH" && e.reason != "SIZE_MISMATCH"
                reject(tag, e.reason, e.message ?: e.reason, permanent, rejected, key, manifest.versionCode)
            }
        }
    }

    private suspend fun reject(
        tag: String, reason: String, message: String, permanent: Boolean,
        rejected: MutableList<String>, key: String, versionCode: Int? = null,
    ) {
        log.warn("Rejected release {}: {} ({})", tag, reason, message)
        if (permanent) permanentlyRejected[key] = reason
        rejected += "${versionCode ?: tag}: $reason"
        audit.log(null, AuditActions.RELEASE_REJECTED, tag, mapOf("reason" to reason, "message" to message.take(300), "versionCode" to versionCode))
    }

    private suspend fun <T> download(asset: JsonObject, token: String, consume: (java.io.InputStream) -> T): T {
        // With a token use the API asset URL (works for private repos); otherwise the public download URL.
        val apiUrl = asset["url"]?.jsonPrimitive?.contentOrNull
        val publicUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
        val url = (if (token.isNotBlank()) apiUrl ?: publicUrl else publicUrl ?: apiUrl) ?: throw Rejected("INVALID_METADATA", "asset has no URL")
        return http.getStream(url, ghHeaders(token, "application/octet-stream"), consume)
    }

    private suspend fun mirrorOne(
        tag: String, version: String, m: ReleaseManifest, manifestBytes: ByteArray, sig: String?,
        apkAsset: JsonObject, s: ReleaseSettings,
    ): AppRelease {
        val sha = ReleaseSettingsService.normalizeHex(m.sha256) ?: throw Rejected("INVALID_METADATA", "sha256 is not 64 hex chars")
        val claimedCert = ReleaseSettingsService.normalizeHex(m.signingCertSha256) ?: throw Rejected("INVALID_METADATA", "signingCertSha256 is not 64 hex chars")
        if (m.versionName != version) throw Rejected("INVALID_METADATA", "versionName ${m.versionName} does not match tag $tag")
        if (m.versionCode <= 0) throw Rejected("INVALID_METADATA", "versionCode must be positive")
        if (m.size <= 0 || m.size > MAX_APK) throw Rejected("INVALID_METADATA", "size out of range")
        if (m.minSupportedVersionCode < 0 || m.minSupportedVersionCode > m.versionCode) throw Rejected("INVALID_METADATA", "minSupportedVersionCode out of range")
        if (m.protocolVersion < 1 || m.minPeerProtocol < 1 || m.minPeerProtocol > m.protocolVersion) {
            throw Rejected("INVALID_METADATA", "need 1 <= minPeerProtocol <= protocolVersion")
        }
        if (m.minSdk < 0 || m.minSdk > 1000) throw Rejected("INVALID_METADATA", "minSdk out of range")
        if (m.rollout != null && m.rollout != "staged" && m.rollout != "all") throw Rejected("INVALID_METADATA", "rollout must be staged or all")
        if (sig != null && sig.length > MAX_SIG) throw Rejected("SIGNATURE_INVALID", "release.json.sig too large")
        // Signed manifest (defense in depth; the app re-verifies against its build-time pin).
        config.otaPublicKey?.let { pub ->
            if (sig == null) throw Rejected("SIGNATURE_MISSING", "OTA_PUBLIC_KEY is set but the release has no release.json.sig")
            if (!ManifestSignature.verify(pub, manifestBytes, sig)) throw Rejected("SIGNATURE_INVALID", "release.json.sig does not verify against OTA_PUBLIC_KEY")
        }

        val tmp = File(releasesDir, ".tmp-${m.versionCode}-${System.nanoTime()}.apk")
        try {
            val (actualSha, actualSize) = download(apkAsset, s.githubToken) { input ->
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > m.size) throw Rejected("SIZE_MISMATCH", "APK larger than declared size ${m.size}")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) } to total
            }
            if (actualSize != m.size) throw Rejected("SIZE_MISMATCH", "APK is $actualSize bytes, release.json says ${m.size}")
            if (actualSha != sha) throw Rejected("SHA256_MISMATCH", "APK sha256 $actualSha != $sha")

            val apkCert = try {
                ApkSigningCert.certSha256(tmp)
            } catch (e: ApkSigningCert.ApkFormatException) {
                throw Rejected("APK_UNSIGNED", "cannot read APK signing certificate: ${e.message}")
            }
            if (apkCert != claimedCert) throw Rejected("CERT_MISMATCH", "APK is signed by $apkCert but release.json claims $claimedCert")
            val pin = s.expectedSigningCertSha256
            if (pin.isNotEmpty() && pin != apkCert) throw Rejected("CERT_MISMATCH", "APK cert $apkCert does not match pinned cert $pin")
            if (pin.isEmpty() && settings.pinIfUnset(apkCert)) {
                log.warn("Pinned release signing certificate {} from {} (trust on first use)", apkCert, tag)
                audit.log(null, AuditActions.RELEASE_CERT_PINNED, tag, mapOf("signingCertSha256" to apkCert))
            } else if (pin.isEmpty()) {
                // Someone pinned concurrently: re-check against it.
                val now = settings.get().expectedSigningCertSha256
                if (now != apkCert) throw Rejected("CERT_MISMATCH", "APK cert $apkCert does not match pinned cert $now")
            }

            // Anti-downgrade: auto-publish never publishes below the highest version ever published.
            val highWater = db.tx { HighWater.get() }
            val publish = s.autoPublish && m.versionCode >= highWater
            // Staged rollout only makes sense when there is a canary device to go first.
            val stage = if (m.rollout == "staged" && s.canaryDeviceIds.isNotEmpty()) ReleaseStage.CANARY else ReleaseStage.ALL

            val dest = File(releasesDir, "couple-${m.versionName}.apk")
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // Keep the manifest byte-exact: the signature covers these bytes.
            File(releasesDir, "couple-${m.versionName}.release.json").writeBytes(manifestBytes)
            if (sig != null) File(releasesDir, "couple-${m.versionName}.release.json.sig").writeText(sig)

            val row = AppRelease(
                id = Ids.newId(),
                versionCode = m.versionCode,
                versionName = m.versionName,
                sha256 = sha,
                size = m.size,
                signingCertSha256 = apkCert,
                minSupportedVersionCode = m.minSupportedVersionCode,
                notes = m.notes,
                publishedAt = if (m.publishedAt > 0) m.publishedAt else config.clock(),
                filePath = dest.absolutePath,
                status = if (publish) ReleaseStatus.PUBLISHED else ReleaseStatus.MIRRORED,
                rolloutPercent = 100,
                createdAt = config.clock(),
                manifestB64 = java.util.Base64.getEncoder().encodeToString(manifestBytes),
                manifestSig = sig,
                protocolVersion = m.protocolVersion,
                minPeerProtocol = m.minPeerProtocol,
                minSdk = m.minSdk,
                stage = stage,
            )
            db.tx {
                ReleaseRepo.insert(row)
                if (publish) HighWater.raise(row.versionCode, config.clock())
            }
            if (s.autoPublish && !publish) {
                audit.log(null, AuditActions.RELEASE_DOWNGRADE_REFUSED, tag, mapOf("versionCode" to m.versionCode, "highWater" to highWater))
            }
            audit.log(
                null, AuditActions.RELEASE_MIRRORED, tag,
                mapOf("versionCode" to row.versionCode, "sha256" to sha, "size" to row.size, "status" to row.status.name, "signed" to (sig != null), "stage" to row.stage.name),
            )
            return row
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private val TAG = Regex("^v(\\d+\\.\\d+\\.\\d+)$")
        private const val MAX_MANIFEST = 64 * 1024
        private const val MAX_SIG = 128
        /** Hard ceiling on a mirrored APK. */
        const val MAX_APK = 500L * 1024 * 1024
    }
}
