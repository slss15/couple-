package com.qimingnan.couple.backend.release

/**
 * Public download URLs offered next to the server's own (authenticated) /releases path, so the app can race them
 * (contract "Auto-update → mirrors"). The app re-checks size + sha256 from the signed manifest whichever URL served
 * the bytes, and never sends its access token to any of these hosts.
 */
object DownloadMirrors {
    /** CI release asset name, see .github/workflows/release.yml. */
    fun apkName(versionName: String) = "couple-$versionName.apk"

    fun githubAssetUrl(repo: String, versionName: String) =
        "https://github.com/$repo/releases/download/v$versionName/${apkName(versionName)}"

    /** Admin domestic mirrors first (usually fastest in mainland China), then GitHub via accelerators, then GitHub. */
    fun forRelease(s: ReleaseSettings, versionName: String): List<String> {
        val gh = githubAssetUrl(s.repo, versionName)
        return (s.mirrors.map { "${it.trimEnd('/')}/${apkName(versionName)}" } + s.ghAccelerators.map { it + gh } + gh).distinct()
    }
}
