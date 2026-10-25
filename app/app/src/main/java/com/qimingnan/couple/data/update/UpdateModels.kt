package com.qimingnan.couple.data.update

import com.qimingnan.couple.core.CoupleJson
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** `latest` of GET /app/update (contract "Auto-update (P0.5)"). */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val notes: String? = null,
    val downloadUrl: String,
    val publishedAt: Long = 0,
    /** base64 of the exact release.json bytes (OTA v2); verified with [manifestSig] by [ManifestVerifier]. */
    val manifest: String? = null,
    val manifestSig: String? = null,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
)

/** The partner's last report as seen by the server (OTA v2 partner gate). */
@Serializable
data class PeerInfo(
    val versionCode: Int,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
    val reportedAt: Long = 0,
    val compatible: Boolean = true,
)

@Serializable
data class UpdateResponse(
    val latest: UpdateInfo? = null,
    val force: Boolean = false,
    /** null | MIN_SUPPORTED | PEER_PROTOCOL | PEER_NEWER */
    val reason: String? = null,
    val peer: PeerInfo? = null,
)

/** Pure update rules (no Android dependencies) so they can be unit-tested on the JVM. */
object UpdateLogic {
    /** Max one automatic check per 6 hours (manual checks ignore this). */
    const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun parseResponse(json: String): UpdateResponse = CoupleJson.decodeFromString(UpdateResponse.serializer(), json)

    /**
     * Whether a response is actionable for the installed [currentVersionCode]. The server already filters by rollout;
     * we still ignore anything not strictly newer (e.g. a stale cached response after an update).
     */
    fun isNewer(info: UpdateInfo?, currentVersionCode: Int): Boolean = info != null && info.versionCode > currentVersionCode

    /**
     * Anti-downgrade (docs/ota-design.md §3): an offer is acceptable only when it is newer than the installed build and
     * not older than the highest versionCode this device has installed or seen in a *verified* manifest.
     */
    fun acceptOffer(offeredVersionCode: Int, installedVersionCode: Int, highestSeen: Int): Boolean =
        offeredVersionCode > installedVersionCode && offeredVersionCode >= highestSeen

    /** New high-water mark; only verified manifests may raise it above the installed version. */
    fun nextHighestSeen(current: Int, installedVersionCode: Int, verifiedOffer: Int?): Int =
        maxOf(current, installedVersionCode, verifiedOffer ?: 0)

    /** Short Chinese explanation for the dialog. */
    fun reasonText(reason: String?, peer: PeerInfo?): String? = when (reason) {
        "MIN_SUPPORTED" -> "当前版本已停止支持，需要更新后才能继续使用。"
        "PEER_PROTOCOL" -> "对方已升级到新的通信协议，需要更新后才能继续和对方通信。"
        "PEER_NEWER" -> "对方已经更新到新版本，建议你也更新。"
        else -> null
    }

    fun shouldAutoCheck(lastCheckAt: Long, now: Long): Boolean =
        lastCheckAt <= 0 || now - lastCheckAt >= AUTO_CHECK_INTERVAL_MS || now < lastCheckAt

    /** Same scheme CI uses: X.Y.Z -> X*10000 + Y*100 + Z (Y, Z <= 99). Null when not X.Y.Z. */
    fun versionCodeOf(versionName: String): Int? {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(versionName.trim()) ?: return null
        val (x, y, z) = m.destructured.toList().map { it.toIntOrNull() ?: return null }
        if (y > 99 || z > 99) return null
        return x * 10000 + y * 100 + z
    }

    /** Numeric, segment-wise comparison of dotted versions ("1.10.0" > "1.9.3"); a missing segment counts as 0. */
    fun compareVersionNames(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Lowercase hex without ':'/spaces, or null unless it is a 64-char SHA-256. */
    fun normalizeSha256(s: String?): String? =
        s?.lowercase()?.replace(":", "")?.replace(" ", "")?.trim()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }

    /**
     * Resolves the server's downloadUrl (absolute or "/api/v1/...") against the configured baseUrl and refuses any
     * URL on another origin: the authenticated client attaches the access token to every request it sends.
     */
    fun resolveDownloadUrl(baseUrl: String, downloadUrl: String): HttpUrl? {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        val resolved = base.resolve(downloadUrl) ?: return null
        val sameOrigin = resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port
        return resolved.takeIf { sameOrigin }
    }

    fun sha256Hex(file: File): String = file.inputStream().use(::sha256Hex)

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface CertCheck {
        data object Ok : CertCheck
        data class Rejected(val reason: String) : CertCheck
    }

    /**
     * Signing-certificate rule: the downloaded APK must be signed by exactly the same certificate set as the installed
     * app (what Android itself requires for an update, checked up front so we never hand the installer a foreign APK),
     * and the server-reported signingCertSha256 must be one of those certificates.
     * All inputs are lowercase hex SHA-256 of the DER certificates.
     */
    fun checkCertificates(installed: Set<String>, apk: Set<String>, serverReported: String?): CertCheck {
        val server = normalizeSha256(serverReported) ?: return CertCheck.Rejected("服务器未提供有效的签名证书指纹")
        if (installed.isEmpty()) return CertCheck.Rejected("无法读取当前应用的签名证书")
        if (apk.isEmpty()) return CertCheck.Rejected("安装包没有有效签名")
        if (apk != installed) return CertCheck.Rejected("安装包签名与当前应用不一致，已拒绝")
        if (server !in apk) return CertCheck.Rejected("安装包签名与服务器公布的证书不一致，已拒绝")
        return CertCheck.Ok
    }
}
