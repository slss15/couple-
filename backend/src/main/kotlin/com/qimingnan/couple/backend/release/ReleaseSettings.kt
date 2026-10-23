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
) {
    companion object {
        const val DEFAULT_REPO = "qimingnan17/couple-"
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
)

class ReleaseSettingsService(private val db: Db, private val clock: () -> Long) {

    suspend fun get(): ReleaseSettings = db.tx { read().first }

    suspend fun view(): ReleaseSettingsView = db.tx { read() }.let { (s, at) -> s.toView(at) }

    /** Returns the new view plus the names of changed sensitive fields (for the audit log). */
    suspend fun update(req: ReleaseSettingsUpdate): Pair<ReleaseSettingsView, List<String>> {
        req.repo?.let { if (!REPO.matches(it.trim())) throw badRequest("VALIDATION_ERROR", "repo must look like owner/name") }
        req.pollMinutes?.let { if (it != 0 && it !in 5..1440) throw badRequest("VALIDATION_ERROR", "pollMinutes must be 0 (off) or 5..1440") }
        val pin = req.expectedSigningCertSha256?.let { if (it.isEmpty()) "" else normalizeHex(it) ?: throw badRequest("VALIDATION_ERROR", "expectedSigningCertSha256 must be 64 hex chars") }
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
            val n = cur.copy(
                repo = req.repo?.trim() ?: cur.repo,
                githubToken = token,
                autoPublish = req.autoPublish ?: cur.autoPublish,
                pollMinutes = req.pollMinutes ?: cur.pollMinutes,
                expectedSigningCertSha256 = pin ?: cur.expectedSigningCertSha256,
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
    )

    companion object {
        const val KEY = "release.settings"
        private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Lowercases and strips ':' / spaces; returns null unless the result is 64 hex chars. */
        fun normalizeHex(s: String): String? = s.lowercase().replace(":", "").replace(" ", "").trim().takeIf { HEX64.matches(it) }
    }
}
