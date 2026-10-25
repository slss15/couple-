package com.qimingnan.couple.updater

import java.nio.file.Files
import java.nio.file.Path

/**
 * BUILD_INFO, written into every server package by deploy/windows/build-package.sh:
 *   VERSION=<version>  COMMIT_SHA=<git sha>  COMMIT_EPOCH=<commit time, unix seconds>
 * Packages built before BUILD_INFO existed have none; their order is then unknown (never treated as a downgrade).
 */
data class BuildInfo(val version: String?, val commitSha: String?, val commitEpoch: Long?) {
    companion object {
        fun parse(text: String): BuildInfo {
            val m = EnvFile.parse(text)
            return BuildInfo(
                version = m["VERSION"]?.takeIf { it.isNotBlank() },
                commitSha = m["COMMIT_SHA"]?.takeIf { it.isNotBlank() },
                commitEpoch = m["COMMIT_EPOCH"]?.toLongOrNull()?.takeIf { it > 0 },
            )
        }

        fun read(releaseDir: Path): BuildInfo? {
            val f = releaseDir.resolve("BUILD_INFO")
            return if (Files.isRegularFile(f)) runCatching { parse(Files.readString(f)) }.getOrNull() else null
        }
    }
}

/**
 * Release ordering for the anti-downgrade rule. Same rules as Get-VersionRelation in deploy/windows/lib/common.ps1:
 *  - both names are X.Y.Z[-pre] (optional leading v): semantic versioning precedence;
 *  - otherwise (e.g. edge-<sha>, or edge vs a tag) the commit epochs from BUILD_INFO decide;
 *  - anything else is unknown, which is never treated as a downgrade.
 */
object VersionOrder {
    data class SemVer(val major: Long, val minor: Long, val patch: Long, val pre: List<String>)

    private val SEMVER = Regex("^v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    fun parse(v: String?): SemVer? {
        val m = SEMVER.matchEntire(v?.trim() ?: return null) ?: return null
        val (a, b, c, pre) = m.destructured
        return SemVer(a.toLong(), b.toLong(), c.toLong(), if (pre.isEmpty()) emptyList() else pre.split('.'))
    }

    /** Negative / 0 / positive like compareTo; null when either is not a semantic version. */
    fun compareSemVer(a: String?, b: String?): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        compareValues(x.major, y.major).takeIf { it != 0 }?.let { return it }
        compareValues(x.minor, y.minor).takeIf { it != 0 }?.let { return it }
        compareValues(x.patch, y.patch).takeIf { it != 0 }?.let { return it }
        if (x.pre == y.pre) return 0
        if (x.pre.isEmpty()) return 1
        if (y.pre.isEmpty()) return -1
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val p = x.pre[i]; val q = y.pre[i]
            val pn = p.toLongOrNull(); val qn = q.toLongOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c.coerceIn(-1, 1)
        }
        return x.pre.size.compareTo(y.pre.size).coerceIn(-1, 1)
    }

    enum class Relation { UPGRADE, SAME, DOWNGRADE, UNKNOWN }

    fun relation(newVersion: String, newEpoch: Long?, currentVersion: String?, currentEpoch: Long?): Relation {
        if (currentVersion.isNullOrEmpty()) return Relation.UNKNOWN
        if (newVersion == currentVersion) return Relation.SAME
        val c = compareSemVer(newVersion, currentVersion)
        if (c != null) return when {
            c < 0 -> Relation.DOWNGRADE
            c > 0 -> Relation.UPGRADE
            else -> Relation.SAME
        }
        if (newEpoch == null || currentEpoch == null) return Relation.UNKNOWN
        return when {
            newEpoch < currentEpoch -> Relation.DOWNGRADE
            newEpoch > currentEpoch -> Relation.UPGRADE
            else -> Relation.UNKNOWN
        }
    }

    /** True for an UPDATE_CHANNEL that pins one version (anything but edge / stable / latest). */
    fun isPinnedChannel(channel: String): Boolean = channel !in setOf("edge", "stable", "latest")
}
