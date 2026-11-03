package com.qimingnan.couple.backend.release

import com.qimingnan.couple.backend.admin.AdminConfigService
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.SettingsRepo
import kotlinx.serialization.Serializable

/** Stored in server_settings under [ReleaseSettingsService.KEY] (token in clear inside the DB, never returned). */
@Serializable
data class ReleaseSettings(
    val repo: String = DEFAULT_REPO,
    val githubToken: String = "",
    val autoPublish: Boolean = false,
    val pollMinutes: Int = 30,
    /** Lowercase hex SHA-256 of the APK signing certificate; "" = not pinned yet (pinned from the first mirror). */
    val expectedSigningCertSha256: String = "",
    /** OTA v2 staged rollout: devices (token did) that get CANARY-stage releases first ("me first"). */
    val canaryDeviceIds: List<String> = emptyList(),
    /**
     * Domestic download mirrors (https base URLs, e.g. an OSS/COS bucket the operator uploads APKs to). Offered to the
     * app as `<base>/couple-<versionName>.apk` in GET /app/update `latest.mirrors` (contract "Auto-update → mirrors").
     */
    val mirrors: List<String> = emptyList(),
    /** Public GitHub accelerator prefixes; `<prefix><GitHub asset URL>` is offered as a mirror. Third-party, unofficial. */
    val ghAccelerators: List<String> = DEFAULT_GH_ACCELERATORS,
) {
    companion object {
        const val DEFAULT_REPO = "slss15/couple-"
        /** Verified with curl (HTTP 206 on a Range request for a release asset) in 2026-11. */
        val DEFAULT_GH_ACCELERATORS = listOf("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")
        const val MAX_MIRRORS = 5
    }
}

/** GET/PUT /admin/releases/settings view: the token is masked like other secrets. */
@Serializable
data class ReleaseSettingsView(
    val repo: String,
    val githubToken: String?,
    val autoPublish: Boolean,
    val pollMinutes: Int,
    val expectedSigningCertSha256: String?,
    val updatedAt: Long?,
    val canaryDeviceIds: List<String> = emptyList(),
    val mirrors: List<String> = emptyList(),
    val ghAccelerators: List<String> = emptyList(),
)

/**
 * PUT body. Every field optional (omitted = keep). githubToken follows the secret rules of /admin/config:
 * null / omitted / the masked value = keep, "" = clear, anything else = overwrite.
 * expectedSigningCertSha256: "" clears the pin (the next mirrored release pins again).
 */
@Serializable
data class ReleaseSettingsUpdate(
    val repo: String? = null,
    val githubToken: String? = null,
    val autoPublish: Boolean? = null,
    val pollMinutes: Int? = null,
    val expectedSigningCertSha256: String? = null,
    val canaryDeviceIds: List<String>? = null,
    /** https base URLs (≤ 5); [] clears. */
    val mirrors: List<String>? = null,
    /** https prefixes (≤ 5); [] = no accelerators. */
    val ghAccelerators: List<String>? = null,
)

class ReleaseSettingsService(private val db: Db, private val clock: () -> Long) {

    suspend fun get(): ReleaseSettings = db.tx { read().first }

    suspend fun view(): ReleaseSettingsView = db.tx { read() }.let { (s, at) -> s.toView(at) }

    /** Returns the new view plus the names of changed sensitive fields (for the audit log). */
    suspend fun update(req: ReleaseSettingsUpdate): Pair<ReleaseSettingsView, List<String>> {
        req.repo?.let { if (!REPO.matches(it.trim())) throw badRequest("VALIDATION_ERROR", "repo must look like owner/name") }
        req.pollMinutes?.let { if (it != 0 && it !in 5..1440) throw badRequest("VALIDATION_ERROR", "pollMinutes must be 0 (off) or 5..1440") }
        val pin = req.expectedSigningCertSha256?.let { if (it.isEmpty()) "" else normalizeHex(it) ?: throw badRequest("VALIDATION_ERROR", "expectedSigningCertSha256 must be 64 hex chars") }
        val canary = req.canaryDeviceIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.also { ids ->
            if (ids.size > 10 || ids.any { it.length > 128 }) throw badRequest("VALIDATION_ERROR", "canaryDeviceIds: at most 10 ids of 1..128 chars")
        }
        val mirrors = req.mirrors?.let { httpsList("mirrors", it, trailingSlash = false) }
        val accel = req.ghAccelerators?.let { httpsList("ghAccelerators", it, trailingSlash = true) }
        val changed = mutableListOf<String>()
        val now = clock()
        val (next, at) = db.tx {
            val (cur, _) = read(forUpdate = true)
            val token = when {
                req.githubToken == null -> cur.githubToken
                req.githubToken == AdminConfigService.MASK -> cur.githubToken
                cur.githubToken.isNotEmpty() && req.githubToken == AdminConfigService.maskValue(cur.githubToken) -> cur.githubToken
                else -> req.githubToken.trim()
            }
            if (token != cur.githubToken) changed += "githubToken"
            if (pin != null && pin != cur.expectedSigningCertSha256) changed += "expectedSigningCertSha256"
            if (mirrors != null && mirrors != cur.mirrors) changed += "mirrors"
            if (accel != null && accel != cur.ghAccelerators) changed += "ghAccelerators"
            val n = cur.copy(
                repo = req.repo?.trim() ?: cur.repo,
                githubToken = token,
                autoPublish = req.autoPublish ?: cur.autoPublish,
                pollMinutes = req.pollMinutes ?: cur.pollMinutes,
                expectedSigningCertSha256 = pin ?: cur.expectedSigningCertSha256,
                canaryDeviceIds = canary ?: cur.canaryDeviceIds,
                mirrors = mirrors ?: cur.mirrors,
                ghAccelerators = accel ?: cur.ghAccelerators,
            )
            SettingsRepo.put(KEY, AppJson.encodeToString(ReleaseSettings.serializer(), n), now)
            n to now
        }
        return next.toView(at) to changed
    }

    /** Pins the signing cert only if nothing is pinned yet. Returns true when this call set the pin. */
    suspend fun pinIfUnset(certSha256: String): Boolean = db.tx {
        val (cur, _) = read(forUpdate = true)
        if (cur.expectedSigningCertSha256.isNotEmpty()) return@tx false
        SettingsRepo.put(KEY, AppJson.encodeToString(ReleaseSettings.serializer(), cur.copy(expectedSigningCertSha256 = certSha256)), clock())
        true
    }

    private fun read(forUpdate: Boolean = false): Pair<ReleaseSettings, Long?> {
        val row = SettingsRepo.getRow(KEY, forUpdate)
        val s = row?.let { AppJson.decodeFromString(ReleaseSettings.serializer(), it.first) } ?: ReleaseSettings()
        return s to row?.second
    }

    private fun ReleaseSettings.toView(at: Long?) = ReleaseSettingsView(
        repo = repo,
        githubToken = if (githubToken.isEmpty()) null else AdminConfigService.maskValue(githubToken),
        autoPublish = autoPublish,
        pollMinutes = pollMinutes,
        expectedSigningCertSha256 = expectedSigningCertSha256.ifEmpty { null },
        updatedAt = at,
        canaryDeviceIds = canaryDeviceIds,
        mirrors = mirrors,
        ghAccelerators = ghAccelerators,
    )

    companion object {
        const val KEY = "release.settings"
        private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /**
         * Validates a list of mirror bases / accelerator prefixes: absolute https, a host, no query/fragment/userinfo,
         * ≤ 256 chars, ≤ [ReleaseSettings.MAX_MIRRORS]. Bases lose their trailing '/', prefixes always get one.
         */
        fun httpsList(field: String, raw: List<String>, trailingSlash: Boolean): List<String> {
            val list = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (list.size > ReleaseSettings.MAX_MIRRORS) throw badRequest("VALIDATION_ERROR", "$field: at most ${ReleaseSettings.MAX_MIRRORS} URLs")
            return list.map { s ->
                val u = runCatching { java.net.URI(s) }.getOrNull()
                val ok = u != null && u.scheme == "https" && !u.host.isNullOrEmpty() && u.rawQuery == null &&
                    u.rawFragment == null && u.rawUserInfo == null && s.length <= 256
                if (!ok) throw badRequest("VALIDATION_ERROR", "$field: '$s' must be an absolute https URL without query/fragment")
                if (trailingSlash) (if (s.endsWith("/")) s else "$s/") else s.trimEnd('/')
            }
        }

        /** Lowercases and strips ':' / spaces; returns null unless the result is 64 hex chars. */
        fun normalizeHex(s: String): String? = s.lowercase().replace(":", "").replace(" ", "").trim().takeIf { HEX64.matches(it) }
    }
}
