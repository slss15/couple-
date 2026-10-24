package com.qimingnan.couple.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val MAX_BODY = 8192

/** Same scheme as deploy/updater/updater.py: hex(HMAC-SHA256(secret, "<ts>.<body>")). */
object Hmac {
    fun sign(secret: String, ts: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(ts.toByteArray(Charsets.UTF_8))
        mac.update('.'.code.toByte())
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/** Remembers accepted signatures (persisted) until they can no longer pass the timestamp check. */
class ReplayCache(private val path: Path?, private val ttlSeconds: Long) {
    private val seen = HashMap<String, Double>()

    init {
        if (path != null && Files.isRegularFile(path)) {
            runCatching {
                Json.parseToJsonElement(Files.readString(path)).jsonObject.forEach { (k, v) ->
                    v.jsonPrimitive.contentOrNull?.toDoubleOrNull()?.let { seen[k] = it }
                }
            }
        }
    }

    /** True if [key] is new (and records it); false if it is a replay. */
    @Synchronized
    fun checkAndAdd(key: String, nowSeconds: Double): Boolean {
        seen.entries.removeIf { it.value <= nowSeconds }
        if (key in seen) return false
        seen[key] = nowSeconds + 2 * ttlSeconds + 60
        val path = path
        if (path != null) {
            runCatching {
                path.parent?.let { Files.createDirectories(it) }
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                Files.writeString(tmp, JsonObject(seen.mapValues { JsonPrimitive(it.value) }).toString())
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        return true
    }
}

data class HookResult(val status: Int, val reason: String, val body: JsonObject? = null)

private val TS_RE = Regex("^\\d{1,12}$")

fun verifyHook(
    secret: String,
    tsHeader: String?,
    sigHeader: String?,
    body: ByteArray,
    nowSeconds: Double,
    maxSkew: Long,
    replay: ReplayCache,
): HookResult {
    if (secret.isEmpty()) return HookResult(503, "hook disabled (DEPLOY_HOOK_SECRET unset)")
    if (tsHeader.isNullOrEmpty() || sigHeader.isNullOrEmpty()) return HookResult(401, "missing signature headers")
    if (!TS_RE.matches(tsHeader)) return HookResult(401, "bad timestamp")
    val expected = Hmac.sign(secret, tsHeader, body)
    if (!Hmac.constantTimeEquals(expected, sigHeader.trim().lowercase())) return HookResult(401, "bad signature")
    if (kotlin.math.abs(nowSeconds - tsHeader.toLong()) > maxSkew) return HookResult(401, "timestamp outside allowed window")
    val data = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) }.getOrNull()
        ?: return HookResult(400, "body is not JSON")
    if (data !is JsonObject) return HookResult(400, "body must be an object")
    val ts = (data["ts"] as? JsonPrimitive)?.let { it.longOrNull?.toString() ?: it.contentOrNull }
    if (ts != tsHeader) return HookResult(401, "body ts does not match header")
    if (!replay.checkAndAdd(expected, nowSeconds)) return HookResult(409, "replayed request")
    return HookResult(200, "ok", data)
}

/**
 * A deploy request for the Windows server package. [allowDowngrade] = explicit manual pin / rollback: the
 * anti-downgrade rule (see [VersionOrder]) does not apply.
 */
data class DeployJob(val version: String, val assetUrl: String, val sha256: String, val sha: String, val allowDowngrade: Boolean = false)

private val VERSION_RE = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$")
private val SHA256_RE = Regex("^[0-9a-f]{64}$")
private val GIT_SHA_RE = Regex("^[0-9a-f]{7,40}$")
private val ASSET_NAME_RE = Regex("^couple-server-windows-x64-[0-9A-Za-z._+-]{1,64}\\.zip$")

/**
 * Payload: {service:"server", version, sha, assetUrl, sha256, ts, allowDowngrade?}. assetUrl must be a release asset
 * of the configured repo: https://github.com/<repo>/releases/download/<tag>/couple-server-windows-x64-<version>.zip.
 * allowDowngrade (boolean, default false) marks an intentional rollback to an older build (signed like the rest).
 */
fun validatePayload(cfg: Config, data: JsonObject): Pair<String?, DeployJob?> {
    fun str(k: String) = (data[k] as? JsonPrimitive)?.contentOrNull ?: ""
    if (str("service") != "server") return "unknown service '${str("service").take(32)}' (expected server)" to null
    val version = str("version")
    if (!VERSION_RE.matches(version) || ".." in version) return "invalid version" to null
    val sha256 = str("sha256").lowercase()
    if (!SHA256_RE.matches(sha256)) return "sha256 must be 64 hex chars" to null
    val sha = str("sha").lowercase()
    if (sha.isNotEmpty() && !GIT_SHA_RE.matches(sha)) return "sha must be hex" to null
    val url = str("assetUrl")
    val err = assetUrlError(cfg, url)
    if (err != null) return err to null
    val downgrade = when (val d = data["allowDowngrade"]) {
        null, JsonNull -> false
        is JsonPrimitive -> if (d.isString) return "allowDowngrade must be a boolean" to null else d.booleanOrNull ?: return "allowDowngrade must be a boolean" to null
        else -> return "allowDowngrade must be a boolean" to null
    }
    return null to DeployJob(version, url, sha256, sha, downgrade)
}

fun assetUrlError(cfg: Config, url: String): String? {
    if (!url.startsWith(cfg.assetPrefix)) return "assetUrl not allowed (must start with ${cfg.assetPrefix})"
    val rest = url.removePrefix(cfg.assetPrefix)
    val parts = rest.split('/')
    if (parts.size != 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return "assetUrl must be <tag>/<asset>"
    if (url.any { it == '?' || it == '#' || it == '\\' || it == '%' || it.isWhitespace() }) return "assetUrl has forbidden characters"
    if (!ASSET_NAME_RE.matches(parts[1])) return "asset name must be couple-server-windows-x64-<version>.zip"
    return null
}
