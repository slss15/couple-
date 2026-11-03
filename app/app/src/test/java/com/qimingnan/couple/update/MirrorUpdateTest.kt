package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.GithubUpdate
import com.qimingnan.couple.data.update.MirrorNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUpdateTest {
    private val direct =
        "https://github.com/slss15/couple-/releases/download/v1.2.3/couple-1.2.3.apk"

    @Test
    fun orderedPrefersV4OnIpv4AndV6OnIpv6() {
        assertEquals("v4.gh-proxy.org", MirrorNodes.ordered(false, null).first())
        assertEquals("v6.gh-proxy.org", MirrorNodes.ordered(true, null).first())
        assertEquals(
            listOf("cdn.gh-proxy.org", "v4.gh-proxy.org", "gh-proxy.org", "axisnow.gh-proxy.org", "v6.gh-proxy.org"),
            MirrorNodes.ordered(false, "cdn.gh-proxy.org"),
        )
        // An unknown last-success host must not change the order.
        assertEquals(MirrorNodes.ordered(false, null), MirrorNodes.ordered(false, "evil.example.com"))
    }

    @Test
    fun rewriteOnlyAllowsThisRepo() {
        assertEquals(
            "https://v4.gh-proxy.org/https://github.com/slss15/couple-/releases/download/v1.2.3/couple-1.2.3.apk",
            MirrorNodes.rewrite(direct, "v4.gh-proxy.org"),
        )
        assertNull(MirrorNodes.rewrite("https://github.com/other/repo/releases/download/v1/a.apk", "v4.gh-proxy.org"))
        assertNull(MirrorNodes.rewrite("http://github.com/slss15/couple-/releases/download/v1/a.apk", "v4.gh-proxy.org"))
        assertNull(MirrorNodes.rewrite("https://evil.example.com/x.apk", "v4.gh-proxy.org"))
        assertTrue(MirrorNodes.isMirrorOrGithub("https://v4.gh-proxy.org/https://github.com/slss15/couple-/releases/download/v1/a.apk"))
        assertTrue(MirrorNodes.isMirrorOrGithub(direct))
        assertFalse(MirrorNodes.isMirrorOrGithub("https://v4.gh-proxy.org/https://github.com/other/repo/x.apk"))
        assertFalse(MirrorNodes.isMirrorOrGithub("https://github.akams.cn/https://github.com/slss15/couple-/releases/download/v1/a.apk"))
    }

    @Test
    fun candidatesEndWithDirect() {
        val urls = MirrorNodes.candidates(direct, false, null)
        assertEquals(direct, urls.last())
        assertTrue(urls.first().startsWith("https://v4.gh-proxy.org/"))
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun pickLatestFiltersDraftPrereleaseAndRequiresManifest() {
        val json = """
            [
              {"tag_name":"v1.9.3","draft":false,"prerelease":false,"assets":[{"name":"release.json","browser_download_url":"https://example.invalid/r.json"}]},
              {"tag_name":"v1.10.0","draft":false,"prerelease":false,"assets":[]},
              {"tag_name":"v2.0.0","draft":false,"prerelease":true,"assets":[{"name":"release.json","browser_download_url":"https://example.invalid/r.json"}]},
              {"tag_name":"v1.10.1","draft":true,"prerelease":false,"assets":[{"name":"release.json","browser_download_url":"https://example.invalid/r.json"}]},
              {"tag_name":"v1.10.2","draft":false,"prerelease":false,"assets":[{"name":"release.json","browser_download_url":"https://example.invalid/r.json"},{"name":"couple-1.10.2.apk","browser_download_url":"https://example.invalid/a.apk"}]}
            ]
        """.trimIndent()
        val latest = GithubUpdate.pickLatest(GithubUpdate.parseReleases(json))
        assertEquals("v1.10.2", latest?.tag_name)
        assertEquals("https://example.invalid/a.apk", GithubUpdate.apkAsset(latest!!, "1.10.2")?.browser_download_url)
        assertEquals(emptyList<Any>(), GithubUpdate.parseReleases("not json"))
        assertNull(GithubUpdate.pickLatest(emptyList()))
    }
}
