package com.qimingnan.couple.backend.admin

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.SettingsRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Stored admin config (secrets in clear text inside the DB; never returned unmasked). */
@Serializable
data class StoredConfig(
    val baseUrl: String? = null,
    val mqttUrl: String? = null,
    val turn: JsonObject = JsonObject(emptyMap()),
    val storage: JsonObject = JsonObject(emptyMap()),
    /** Out-of-band alert channel config, e.g. {"wxpusher": {"appToken": "<secret>", "uids": [...]}}. */
    val notify: JsonObject = JsonObject(emptyMap()),
    /** Sticker search fallback (apihz), e.g. {"apihz": {"id": "...", "apiKey": "<secret>"}}. Optional. */
    val stickers: JsonObject = JsonObject(emptyMap()),
)

/** Body of PUT /admin/config. */
@Serializable
data class AdminConfigUpdate(
    val baseUrl: String? = null,
    val mqttUrl: String? = null,
    val turn: JsonObject? = null,
    val storage: JsonObject? = null,
    val notify: JsonObject? = null,
    val stickers: JsonObject? = null,
)

/**
 * Response of GET/PUT /admin/config. configVersion and updatedAt are additions to the contract's shape
 * (needed by the admin UI to show which version a QR will carry).
 */
@Serializable
data class AdminConfigView(
    val baseUrl: String?,
    val mqttUrl: String?,
    val turn: JsonObject,
    val storage: JsonObject,
    val notify: JsonObject,
    val stickers: JsonObject = JsonObject(emptyMap()),
    val configVersion: Long,
    val updatedAt: Long?,
    /** Values derived from the request (forwarded headers) for empty fields; see contract "First-run setup". */
    val suggested: com.qimingnan.couple.backend.setup.SuggestedUrls? = null,
)

class AdminConfigService(private val db: Db, private val config: AppConfig) {

    data class Snapshot(val stored: StoredConfig, val version: Long, val updatedAt: Long?)

    suspend fun snapshot(): Snapshot = db.tx { readSnapshot() }

    suspend fun view(): AdminConfigView = snapshot().toView()

    /**
     * Applies an update. Secret fields (see [isSecretKey]) are write-only: omitting the key, sending null, or echoing
     * back the masked value keeps the stored secret; sending "" clears it; any other value overwrites it. Omitted top-level sections keep their stored value.
     * Every successful PUT bumps configVersion.
     */
    suspend fun update(req: AdminConfigUpdate): Pair<AdminConfigView, List<String>> {
        req.baseUrl?.let {
            if (!(it.startsWith("https://") || it.startsWith("http://"))) throw badRequest("VALIDATION_ERROR", "baseUrl must be an http(s) URL")
        }
        req.mqttUrl?.let {
            if (!it.startsWith("wss://")) throw badRequest("VALIDATION_ERROR", "mqttUrl must be a wss:// URL")
        }
        req.turn?.let { t ->
            val provider = (t["provider"] as? JsonPrimitive)?.contentOrNull
            if (provider != null && provider !in setOf("cloudflare", "coturn")) {
                throw badRequest("VALIDATION_ERROR", "turn.provider must be \"cloudflare\" or \"coturn\"")
            }
        }
        val now = config.clock()
        val changedSecrets = mutableListOf<String>()
        val snap = db.tx {
            val cur = readSnapshot(forUpdate = true)
            val next = StoredConfig(
                baseUrl = req.baseUrl?.trimEnd('/') ?: cur.stored.baseUrl,
                mqttUrl = req.mqttUrl ?: cur.stored.mqttUrl,
                turn = req.turn?.let { mergeSecrets(cur.stored.turn, it, "turn", changedSecrets) } ?: cur.stored.turn,
                storage = req.storage?.let { mergeSecrets(cur.stored.storage, it, "storage", changedSecrets) } ?: cur.stored.storage,
                notify = req.notify?.let { mergeSecrets(cur.stored.notify, it, "notify", changedSecrets) } ?: cur.stored.notify,
                stickers = req.stickers?.let { mergeSecrets(cur.stored.stickers, it, "stickers", changedSecrets) } ?: cur.stored.stickers,
            )
            val version = cur.version + 1
            SettingsRepo.put(KEY_CONFIG, AppJson.encodeToString(StoredConfig.serializer(), next), now)
            SettingsRepo.put(KEY_VERSION, version.toString(), now)
            Snapshot(next, version, now)
        }
        return snap.toView() to changedSecrets
    }

    /**
     * Fills baseUrl / mqttUrl only where they are empty (never overwrites an operator's value). Bumps configVersion
     * when anything was written. Returns the names of the fields that were filled.
     */
    suspend fun fillMissingUrls(baseUrl: String?, mqttUrl: String?): List<String> {
        val now = config.clock()
        return db.tx {
            val cur = readSnapshot(forUpdate = true)
            val filled = mutableListOf<String>()
            var next = cur.stored
            if (cur.stored.baseUrl.isNullOrBlank() && !baseUrl.isNullOrBlank()) {
                next = next.copy(baseUrl = baseUrl.trimEnd('/')); filled += "baseUrl"
            }
            if (cur.stored.mqttUrl.isNullOrBlank() && !mqttUrl.isNullOrBlank() && mqttUrl.startsWith("wss://")) {
                next = next.copy(mqttUrl = mqttUrl); filled += "mqttUrl"
            }
            if (filled.isNotEmpty()) {
                SettingsRepo.put(KEY_CONFIG, AppJson.encodeToString(StoredConfig.serializer(), next), now)
                SettingsRepo.put(KEY_VERSION, (cur.version + 1).toString(), now)
            }
            filled
        }
    }

    private fun readSnapshot(forUpdate: Boolean = false): Snapshot {
        val row = SettingsRepo.getRow(KEY_CONFIG, forUpdate)
        val stored = row?.let { AppJson.decodeFromString(StoredConfig.serializer(), it.first) } ?: StoredConfig()
        val version = SettingsRepo.get(KEY_VERSION)?.toLongOrNull() ?: 0L
        return Snapshot(stored, version, row?.second)
    }

    private fun Snapshot.toView() = AdminConfigView(
        baseUrl = stored.baseUrl,
        mqttUrl = stored.mqttUrl,
        turn = mask(stored.turn),
        storage = mask(stored.storage),
        notify = mask(stored.notify),
        stickers = mask(stored.stickers),
        configVersion = version,
        updatedAt = updatedAt,
    )

    companion object {
        const val KEY_CONFIG = "admin.config"
        const val KEY_VERSION = "admin.config_version"
        const val MASK = "********"
        private val SECRET_KEY = Regex("(?i).*(secret|token|password|passwd|credential|private_?key|api_?key).*")

        fun isSecretKey(key: String) = SECRET_KEY.matches(key)

        /**
         * Masked rendering of a secret: "****" + last 4 chars when the secret is long enough (>= 12 chars) to make
         * that harmless, otherwise "********". Empty/unset secrets render as null.
         */
        fun maskValue(secret: String): String = if (secret.length >= 12) "****" + secret.takeLast(4) else MASK

        /** Replaces every secret string value with its masked form (or null when empty), recursively. */
        fun mask(obj: JsonObject): JsonObject = JsonObject(obj.mapValues { (k, v) ->
            when {
                v is JsonObject -> mask(v)
                isSecretKey(k) && v is JsonPrimitive && v !is JsonNull ->
                    if (v.content.isEmpty()) JsonNull else JsonPrimitive(maskValue(v.content))
                else -> v
            }
        })

        /** null, or the masked value we handed out (echoed back by a form), means "keep the stored secret". */
        private fun isKeepMarker(v: JsonElement, old: JsonElement?): Boolean {
            if (v is JsonNull) return true
            if (v !is JsonPrimitive || !v.isString) return false
            val oldStr = (old as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            return v.content == MASK || (oldStr != null && oldStr.isNotEmpty() && v.content == maskValue(oldStr))
        }

        /** New object = incoming, except secret keys whose incoming value is absent/null/MASK keep the stored value. */
        fun mergeSecrets(stored: JsonObject, incoming: JsonObject, path: String, changed: MutableList<String>): JsonObject {
            val result = LinkedHashMap<String, JsonElement>()
            for ((k, v) in incoming) {
                val old = stored[k]
                result[k] = when {
                    v is JsonObject -> mergeSecrets(old as? JsonObject ?: JsonObject(emptyMap()), v, "$path.$k", changed)
                    isSecretKey(k) && isKeepMarker(v, old) -> old ?: JsonPrimitive("")
                    else -> {
                        if (isSecretKey(k) && v != old) changed += "$path.$k"
                        v
                    }
                }
            }
            // Secrets omitted from the incoming object are kept.
            for ((k, v) in stored) {
                if (k !in result && isSecretKey(k) && v is JsonPrimitive) result[k] = v
            }
            return JsonObject(result)
        }
    }
}

