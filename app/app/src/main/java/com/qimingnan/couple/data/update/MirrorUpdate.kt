package com.qimingnan.couple.data.update

import com.qimingnan.couple.core.CoupleJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Mirror-first GitHub update (server-independent, PR1).
 *
 * The app no longer depends on a deployed backend for updates: it checks
 * GitHub Releases directly, downloads through a verified mirror list, and
 * still verifies the signed manifest, size, sha256 and signing certificate
 * before anything is installed. A mirror is only an untrusted transport;
 * verification is the trust root.
 *
 * Verified 2026-11-04 (small update.json file, not APK speed): every host
 * below returned the same update.json as GitHub direct, while
 * https://github.akams.cn/https://github.com/... returned 404 and is
 * therefore NOT in this list.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 */
object MirrorNodes {
    /** Only this exact GitHub Releases path may be mirrored. Anything else is rejected. */
    const val GITHUB_PREFIX = "https://github.com/slss15/couple-/releases/download/"
    const val API_URL = "https://api.github.com/repos/slss15/couple-/releases?per_page=10"

    /** v6 supports IPv6/IPv4, v4 and axisnow are IPv4-only (per their listings). */
    val IPV6_FIRST = listOf(
        "v6.gh-proxy.org",
        "v4.gh-proxy.org",
        "gh-proxy.org",
        "cdn.gh-proxy.org",
        "axisnow.gh-proxy.org",
    )
    val IPV4_FIRST = listOf(
        "v4.gh-proxy.org",
        "gh-proxy.org",
        "cdn.gh-proxy.org",
        "axisnow.gh-proxy.org",
        "v6.gh-proxy.org",
    )

    fun ordered(hasIpv6: Boolean, lastSuccess: String?): List<String> {
        val base = if (hasIpv6) IPV6_FIRST else IPV4_FIRST
        val clean = lastSuccess?.takeIf { it in base } ?: return base
        return listOf(clean) + base.filter { it != clean }
    }

    fun isGithubReleaseUrl(url: String): Boolean = url.startsWith(GITHUB_PREFIX)

    /** A URL is a mirror download only if it is a known host prefixing the exact GitHub path. */
    fun isMirrorUrl(url: String): Boolean =
        (IPV6_FIRST + IPV4_FIRST).distinct().any { host ->
            url.startsWith("https://$host/") && url.contains("github.com/slss15/couple-/releases/download/")
        }

    fun isMirrorOrGithub(url: String): Boolean = isGithubReleaseUrl(url) || isMirrorUrl(url)

    /** Rewrite a direct GitHub URL through one mirror host, or null if the URL is not allowed. */
    fun rewrite(directUrl: String, host: String): String? =
        if (isGithubReleaseUrl(directUrl)) "https://$host/$directUrl" else null

    /** Mirror candidates in preferred order, with the direct URL last as the final fallback. */
    fun candidates(directUrl: String, hasIpv6: Boolean, lastSuccess: String?): List<String> =
        (ordered(hasIpv6, lastSuccess).mapNotNull { rewrite(directUrl, it) } + directUrl).distinct()

    /**
     * Releases 列表（API）请求的候选：官方直连在前，gh-proxy 的 API 代理随后兜底。
     * 国内 api.github.com 常被墙，直连超时是「检查更新」慢的主因；这些候选配合并行竞速使用。
     */
    fun apiCandidates(hasIpv6: Boolean, lastSuccess: String?): List<String> =
        (listOf(API_URL) + ordered(hasIpv6, lastSuccess).map { "https://$it/$API_URL" }).distinct()

    fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    /** Host to remember after a successful mirror download, or null for a direct success. */
    fun lastSuccessOf(successUrl: String): String? =
        hostOf(successUrl)?.takeIf { it in (IPV6_FIRST + IPV4_FIRST).distinct() }
}

@Serializable
data class GithubAsset(
    val name: String,
    val browser_download_url: String,
)

@Serializable
data class GithubRelease(
    val tag_name: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/** Pure selection over a GitHub Releases list response. No network, no Android. */
object GithubUpdate {
    fun parseReleases(json: String): List<GithubRelease> =
        runCatching {
            CoupleJson.decodeFromString(ListSerializer(GithubRelease.serializer()), json)
        }.getOrNull() ?: emptyList()

    fun manifestAsset(release: GithubRelease): GithubAsset? =
        release.assets.firstOrNull { it.name == "release.json" }

    fun sigAsset(release: GithubRelease): GithubAsset? =
        release.assets.firstOrNull { it.name == "release.json.sig" }

    fun apkAsset(release: GithubRelease, versionName: String): GithubAsset? =
        release.assets.firstOrNull { it.name == "couple-$versionName.apk" }

    /**
     * Newest non-draft, non-prerelease `vX.Y.Z` release that actually carries
     * a release.json manifest, or null. Version comparison is by versionCode,
     * not by tag string, so 1.10.0 correctly beats 1.9.3.
     */
    fun pickLatest(releases: List<GithubRelease>): GithubRelease? =
        releases
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { release ->
                val versionName = release.tag_name.removePrefix("v")
                val code = UpdateLogic.versionCodeOf(versionName) ?: return@mapNotNull null
                if (manifestAsset(release) == null) null else release to code
            }
            .maxByOrNull { it.second }
            ?.first
}
