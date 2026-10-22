package com.qimingnan.couple.backend.bootstrap

import com.qimingnan.couple.backend.admin.AdminConfigService
import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Crypto
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.SettingsRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@Serializable
data class PubKeyResponse(val alg: String, val publicKey: String)

/**
 * POST /admin/bootstrap response. `json` is the signed payload as an object, for display.
 * The signature `s` covers the exact UTF-8 bytes that are base64url-encoded in `d` — verifiers must check the
 * signature over base64url-decode(d), never over a re-serialization of `json`.
 */
@Serializable
data class BootstrapResponse(val uri: String, val json: JsonObject)

class BootstrapService(
    private val keys: BootstrapKeys,
    private val adminConfig: AdminConfigService,
    private val config: AppConfig,
) {
    fun pubkey() = PubKeyResponse(alg = "Ed25519", publicKey = keys.publicKeyBase64)

    suspend fun issue(): BootstrapResponse {
        val snap = adminConfig.snapshot()
        val baseUrl = snap.stored.baseUrl ?: throw badRequest("CONFIG_INCOMPLETE", "Set baseUrl in /admin/config first")
        val mqttUrl = snap.stored.mqttUrl ?: throw badRequest("CONFIG_INCOMPLETE", "Set mqttUrl in /admin/config first")
        val payload = buildJsonObject {
            put("v", 1)
            put("baseUrl", baseUrl)
            put("mqttUrl", mqttUrl)
            put("configVersion", snap.version)
            put("issuedAt", config.clock())
        }
        val bytes = AppJson.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8)
        val sig = keys.sign(bytes)
        val uri = "couple://bootstrap?d=${Crypto.b64url(bytes)}&s=${Crypto.b64url(sig)}"
        return BootstrapResponse(uri, payload)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BootstrapService::class.java)
        const val KEY_SEED = "bootstrap.ed25519_seed"

        /** Env key wins; otherwise load the persisted seed, generating and persisting one on first run. */
        suspend fun loadKeys(config: AppConfig, db: Db, audit: AuditService): BootstrapKeys {
            config.bootstrapPrivateKey?.let { return BootstrapKeys.parse(it) }
            var generated = false
            val keys = db.tx {
                SettingsRepo.get(KEY_SEED)?.let { return@tx BootstrapKeys.fromSeed(java.util.Base64.getDecoder().decode(it)) }
                val k = BootstrapKeys.generate()
                SettingsRepo.put(KEY_SEED, k.seedBase64, config.clock())
                generated = true
                k
            }
            if (generated) {
                log.warn("Generated a new bootstrap Ed25519 key and stored it in the database. Back it up or set BOOTSTRAP_PRIVATE_KEY; " +
                    "losing it means every app must re-pin (TOFU).")
                audit.log(null, AuditActions.BOOTSTRAP_KEY_GENERATED, null, mapOf("publicKey" to keys.publicKeyBase64))
            }
            return keys
        }
    }
}
