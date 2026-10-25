package com.qimingnan.couple.data.update

import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.bootstrap.Ed25519Verifier
import kotlinx.serialization.Serializable
import java.util.Base64

/** release.json as signed by CI with the OTA release key (contract "Auto-update", docs/ota-design.md §2). */
@Serializable
data class SignedManifest(
    val schema: Int = 1,
    val packageName: String? = null,
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
    val minSupportedVersionCode: Int = 0,
    val protocolVersion: Int = 1,
    val minPeerProtocol: Int = 1,
    val minSdk: Int = 0,
    val rollout: String? = null,
    val notes: String? = null,
    val publishedAt: Long = 0,
)

/**
 * Verifies `latest.manifest` / `latest.manifestSig` from GET /app/update against the release public key pinned at
 * build time (BuildConfig.OTA_PUBLIC_KEY). Pure JVM code (BouncyCastle Ed25519) so it is unit-testable.
 *
 * The signature covers the exact manifest bytes. After verifying, every security-relevant field the server sent in
 * `latest` must equal the signed value, so the download/verification pipeline can keep using `latest` unchanged.
 */
object ManifestVerifier {
    sealed interface Result {
        data class Verified(val manifest: SignedManifest) : Result
        /** No key pinned (debug/local build): verification disabled. */
        data object Skipped : Result
        data class Rejected(val reason: String) : Result
    }

    private const val MAX_MANIFEST_B64 = 96 * 1024

    fun verify(info: UpdateInfo, pinnedPublicKeyB64: String, expectedPackage: String): Result {
        if (pinnedPublicKeyB64.isBlank()) return Result.Skipped
        val key = runCatching { Base64.getDecoder().decode(pinnedPublicKeyB64.trim()) }.getOrNull()
            ?.takeIf { it.size == 32 } ?: return Result.Rejected("内置的更新公钥无效")
        val b64 = info.manifest ?: return Result.Rejected("更新信息缺少签名清单")
        val sigB64 = info.manifestSig ?: return Result.Rejected("更新信息缺少签名")
        if (b64.length > MAX_MANIFEST_B64) return Result.Rejected("签名清单过大")
        val bytes = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull() ?: return Result.Rejected("签名清单格式错误")
        val sig = runCatching { Base64.getDecoder().decode(sigB64.trim()) }.getOrNull()
            ?.takeIf { it.size == 64 } ?: return Result.Rejected("签名格式错误")
        if (!Ed25519Verifier.verify(key, bytes, sig)) return Result.Rejected("更新信息签名无效")
        val m = runCatching { CoupleJson.decodeFromString(SignedManifest.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return Result.Rejected("签名清单无法解析")
        val mismatch = when {
            m.packageName != null && m.packageName != expectedPackage -> "packageName"
            m.versionCode != info.versionCode -> "versionCode"
            m.versionName != info.versionName -> "versionName"
            UpdateLogic.normalizeSha256(m.sha256) == null || UpdateLogic.normalizeSha256(m.sha256) != UpdateLogic.normalizeSha256(info.sha256) -> "sha256"
            m.size != info.size -> "size"
            UpdateLogic.normalizeSha256(m.signingCertSha256) == null ||
                UpdateLogic.normalizeSha256(m.signingCertSha256) != UpdateLogic.normalizeSha256(info.signingCertSha256) -> "signingCertSha256"
            else -> null
        }
        if (mismatch != null) return Result.Rejected("更新信息与签名清单不一致（$mismatch）")
        return Result.Verified(m)
    }

    /** The release package id: debug builds carry an ".debug" applicationIdSuffix. */
    fun releasePackageOf(packageName: String): String = packageName.removeSuffix(".debug")
}
