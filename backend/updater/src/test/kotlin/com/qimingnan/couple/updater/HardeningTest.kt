package com.qimingnan.couple.updater

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalTime
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Anti-downgrade, stale sha256 after install.ps1, and authenticated (private repo) GitHub downloads. */
class HardeningTest {
    private val repo = "slss15/couple-"
    private fun asset(v: String, tag: String = "edge") = "https://github.com/$repo/releases/download/$tag/couple-server-windows-x64-$v.zip"

    // ---------------------------------------------------------------- version order

    @Test
    fun semverPrecedence() {
        assertEquals(-1, VersionOrder.compareSemVer("0.2.0", "0.10.0"))
        assertEquals(1, VersionOrder.compareSemVer("1.0.0", "0.99.99"))
        assertEquals(0, VersionOrder.compareSemVer("v1.2.3", "1.2.3"))
        assertEquals(0, VersionOrder.compareSemVer("1.2.3+build.5", "1.2.3"))
        assertEquals(-1, VersionOrder.compareSemVer("1.0.0-rc.1", "1.0.0"))
        assertEquals(-1, VersionOrder.compareSemVer("1.0.0-alpha", "1.0.0-alpha.1"))
        assertEquals(-1, VersionOrder.compareSemVer("1.0.0-alpha.1", "1.0.0-alpha.beta"))
        assertEquals(-1, VersionOrder.compareSemVer("1.0.0-beta.2", "1.0.0-beta.11"))
        assertEquals(-1, VersionOrder.compareSemVer("1.0.0-beta.11", "1.0.0-rc.1"))
        assertNull(VersionOrder.compareSemVer("edge-abc1234", "1.0.0"))
        assertNull(VersionOrder.compareSemVer("1.0", "1.0.0"))
    }

    @Test
    fun relationUsesSemverThenBuildTime() {
        val r = VersionOrder::relation
        assertEquals(VersionOrder.Relation.DOWNGRADE, r("0.2.0", null, "0.3.0", null))
        assertEquals(VersionOrder.Relation.UPGRADE, r("0.3.1", 1, "0.3.0", 999))   // names win over epochs
        assertEquals(VersionOrder.Relation.SAME, r("edge-a", null, "edge-a", null))
        assertEquals(VersionOrder.Relation.DOWNGRADE, r("edge-old", 100, "edge-new", 200))
        assertEquals(VersionOrder.Relation.UPGRADE, r("edge-new", 200, "edge-old", 100))
        assertEquals(VersionOrder.Relation.DOWNGRADE, r("0.3.0", 100, "edge-new", 200)) // stable older than edge
        assertEquals(VersionOrder.Relation.UNKNOWN, r("edge-x", null, "edge-y", 200))     // pre-BUILD_INFO package
        assertEquals(VersionOrder.Relation.UNKNOWN, r("edge-x", 200, "edge-y", 200))
        assertEquals(VersionOrder.Relation.UNKNOWN, r("1.0.0", null, null, null))
        assertTrue(VersionOrder.isPinnedChannel("0.2.0"))
        assertFalse(VersionOrder.isPinnedChannel("edge"))
        assertFalse(VersionOrder.isPinnedChannel("stable"))
    }

    @Test
    fun buildInfoParsing() {
        val b = BuildInfo.parse("VERSION=edge-abc\r\nCOMMIT_SHA=abc\r\nCOMMIT_EPOCH=1760000000\r\n")
        assertEquals("edge-abc", b.version); assertEquals("abc", b.commitSha); assertEquals(1760000000L, b.commitEpoch)
        assertNull(BuildInfo.parse("COMMIT_EPOCH=x").commitEpoch)
        assertNull(BuildInfo.parse("COMMIT_EPOCH=0").commitEpoch)
        assertNull(BuildInfo.read(Files.createTempDirectory("bi")))
    }

    @Test
    fun payloadAllowDowngradeFlag() {
        val c = Config(installDir = Files.createTempDirectory("upd"), envFile = null)
        fun p(extra: String) = Json.parseToJsonElement(
            """{"service":"server","version":"0.2.0","sha":"abc1234","assetUrl":"${asset("0.2.0", "v0.2.0")}","sha256":"${"a".repeat(64)}","ts":1$extra}""",
        ).jsonObject
        assertEquals(false, validatePayload(c, p("")).second!!.allowDowngrade)
        assertEquals(true, validatePayload(c, p(""","allowDowngrade":true""")).second!!.allowDowngrade)
        assertEquals(false, validatePayload(c, p(""","allowDowngrade":false""")).second!!.allowDowngrade)
        assertEquals(false, validatePayload(c, p(""","allowDowngrade":null""")).second!!.allowDowngrade)
        assertNull(validatePayload(c, p(""","allowDowngrade":"true"""")).second)
        assertNull(validatePayload(c, p(""","allowDowngrade":1""")).second)
    }

    // ---------------------------------------------------------------- deploy with fakes

    private class FakeService : ServiceControl {
        val calls = mutableListOf<String>()
        override fun stop(): Boolean { calls += "stop"; return true }
        override fun start(): Boolean { calls += "start"; return true }
    }

    private class FakeDownloader(val files: Map<String, ByteArray>) : Downloader {
        val requested = mutableListOf<String>()
        override fun download(url: String, dest: Path) {
            requested += url
            Files.write(dest, files[url] ?: throw java.io.IOException("404"))
        }
    }

    private fun packageZip(version: String, epoch: Long?): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry("couple-server-windows-x64-$version/$name")); z.write(text.toByteArray()); z.closeEntry() }
            put("VERSION", version)
            put("jre/bin/java.exe", "MZ")
            put("app/lib/couple-backend.jar", "jar")
            put("service/couple-backend.xml.in", "<service><executable>@RELEASE_DIR@\\jre\\bin\\java.exe</executable></service>")
            if (epoch != null) put("BUILD_INFO", "VERSION=$version\nCOMMIT_EPOCH=$epoch\n")
        }
        return bos.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Env(val dir: Path, val up: Updater, val svc: FakeService, val dl: FakeDownloader)

    private fun env(zips: Map<String, ByteArray>, channel: String = "edge", source: ReleaseSource = ReleaseSource { null }, dir: Path = Files.createTempDirectory("couple-home")): Env {
        Files.createDirectories(dir.resolve("config"))
        val svc = FakeService()
        val dl = FakeDownloader(zips)
        var now = 0L
        val cfg = Config(installDir = dir, envFile = dir.resolve("config/couple.env").toFile(), channel = channel, healthTimeoutSeconds = 1, healthPollMillis = 10)
        val up = Updater(cfg, svc, { 200 }, dl, source, clock = { now }, sleep = { now += it })
        return Env(dir, up, svc, dl)
    }

    @Test
    fun semverDowngradeRefusedBeforeDownloadUnlessAllowed() {
        val z3 = packageZip("0.3.0", null); val z2 = packageZip("0.2.0", null)
        val e = env(mapOf(asset("0.3.0", "v0.3.0") to z3, asset("0.2.0", "v0.2.0") to z2))
        assertEquals("success", e.up.deploy(DeployJob("0.3.0", asset("0.3.0", "v0.3.0"), sha(z3), ""), "hook"))
        val before = e.dl.requested.size
        assertEquals("failed", e.up.deploy(DeployJob("0.2.0", asset("0.2.0", "v0.2.0"), sha(z2), ""), "hook"))
        assertEquals("downgrade_refused", e.up.state.deploys["server"]!!.error)
        assertEquals(before, e.dl.requested.size, "no download for a semver downgrade")
        assertEquals("0.3.0", e.up.releases.current())
        assertEquals(listOf("stop", "start"), e.svc.calls)
        // Hook refusals are not remembered: an explicit allowDowngrade hook still works.
        assertTrue(e.up.state.refused["server"].orEmpty().isEmpty())
        assertEquals("success", e.up.deploy(DeployJob("0.2.0", asset("0.2.0", "v0.2.0"), sha(z2), "", allowDowngrade = true), "hook"))
        assertEquals("0.2.0", e.up.releases.current())
    }

    @Test
    fun edgeDowngradeByBuildTimeRefusedAndRememberedByPoller() {
        val newer = packageZip("edge-bbbbbbb", 2_000); val older = packageZip("edge-aaaaaaa", 1_000)
        var remote: RemoteAsset? = null
        val e = env(mapOf(asset("edge-bbbbbbb") to newer, asset("edge-aaaaaaa") to older), source = { remote })
        assertEquals("success", e.up.deploy(DeployJob("edge-bbbbbbb", asset("edge-bbbbbbb"), sha(newer), ""), "hook"))
        // The rolling edge release moved back to an older commit (e.g. a re-run of an old workflow).
        remote = RemoteAsset("edge-aaaaaaa", asset("edge-aaaaaaa"), sha(older))
        e.up.pollOnce(LocalTime.NOON)
        assertEquals("downgrade_refused", e.up.state.deploys["server"]!!.error)
        assertEquals("edge-bbbbbbb", e.up.releases.current())
        assertFalse(Files.exists(e.dir.resolve("releases/edge-aaaaaaa")), "refused release is not kept")
        assertEquals(2, e.svc.calls.size, "service untouched by the refused deploy")
        assertTrue(sha(older) in e.up.state.refused["server"]!!)
        val downloads = e.dl.requested.size
        e.up.pollOnce(LocalTime.NOON)
        assertEquals(downloads, e.dl.requested.size, "poller does not download a refused build again")
    }

    @Test
    fun pinnedChannelMayGoBack() {
        val z3 = packageZip("0.3.0", 300); val z2 = packageZip("0.2.0", 200)
        val dir = Files.createTempDirectory("couple-home")
        val first = env(mapOf(asset("0.3.0", "v0.3.0") to z3), dir = dir)
        assertEquals("success", first.up.deploy(DeployJob("0.3.0", asset("0.3.0", "v0.3.0"), sha(z3), ""), "hook"))
        val pinned = env(mapOf(asset("0.2.0", "v0.2.0") to z2), channel = "0.2.0", source = { RemoteAsset("0.2.0", asset("0.2.0", "v0.2.0"), sha(z2)) }, dir = dir)
        pinned.up.pollOnce(LocalTime.NOON)
        assertEquals("0.2.0", pinned.up.releases.current())
        assertEquals("success", pinned.up.state.deploys["server"]!!.state)
    }

    @Test
    fun unknownOrderIsNotADowngrade() {
        val a = packageZip("edge-aaaaaaa", null); val b = packageZip("edge-bbbbbbb", 100)
        val e = env(mapOf(asset("edge-aaaaaaa") to a, asset("edge-bbbbbbb") to b))
        assertEquals("success", e.up.deploy(DeployJob("edge-aaaaaaa", asset("edge-aaaaaaa"), sha(a), ""), "hook"))
        assertEquals("success", e.up.deploy(DeployJob("edge-bbbbbbb", asset("edge-bbbbbbb"), sha(b), ""), "hook"))
    }

    @Test
    fun manualInstallClearsStaleSha256() {
        val z1 = packageZip("edge-aaaaaaa", 100)
        val dir = Files.createTempDirectory("couple-home")
        val e = env(mapOf(asset("edge-aaaaaaa") to z1), dir = dir)
        assertEquals("success", e.up.deploy(DeployJob("edge-aaaaaaa", asset("edge-aaaaaaa"), sha(z1), ""), "hook"))
        assertEquals(sha(z1), e.up.state.currentSha256)
        // install.ps1 put edge-bbbbbbb in place by hand (CURRENT changed, updater.json still has the old sha256).
        Files.createDirectories(dir.resolve("releases/edge-bbbbbbb"))
        Files.writeString(dir.resolve("releases/CURRENT"), "edge-bbbbbbb\n")
        val remoteSha = "c".repeat(64)
        val again = env(emptyMap(), source = { RemoteAsset("edge-bbbbbbb", asset("edge-bbbbbbb"), remoteSha) }, dir = dir)
        assertEquals("edge-bbbbbbb", again.up.currentVersion())
        assertNull(again.up.state.currentSha256)
        again.up.pollOnce(LocalTime.NOON)
        assertTrue(again.dl.requested.isEmpty(), "matching remote build is not re-deployed")
        assertTrue(again.svc.calls.isEmpty())
    }

    @Test
    fun stateWrittenByInstallPs1Decodes() {
        // Shape produced by Update-UpdaterStateJson (ConvertTo-Json -Compress on Windows PowerShell 5.1).
        val json = """{"current":{"server":"0.3.0"},"currentSha256":null,"bad":{"server":["${"a".repeat(64)}"]},"deploys":{"server":{"version":"0.2.0","state":"success","requestedAt":1,"updatedAt":2}},"lastCheck":1760000000000,"lastResult":"server:success","lastError":null,"channel":"edge"}"""
        val s = StateJson.decodeFromString(State.serializer(), json)
        assertEquals("0.3.0", s.current["server"]); assertNull(s.currentSha256)
        assertEquals(1, s.bad["server"]!!.size)
        assertTrue(s.refused["server"].orEmpty().isEmpty())
    }

    // ---------------------------------------------------------------- private repo: token on API calls and asset downloads

    @Test
    fun tokenIsUsedForApiAndAssetDownloadButNeverSentToStorage() {
        val server = HttpServer.create(InetSocketAddress(0), 0) // wildcard: reachable as 127.0.0.1 and localhost
        val port = server.address.port
        val api = "http://127.0.0.1:$port"
        val payload = packageZip("edge-abc1234", 5)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        fun reply(ex: HttpExchange, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
        fun auth(ex: HttpExchange) = ex.requestHeaders.getFirst("Authorization").orEmpty()
        server.createContext("/repos/$repo/releases/tags/edge") { ex ->
            seen += "release auth=${auth(ex)}"
            if (auth(ex) != "Bearer tok") return@createContext reply(ex, 404, "{}".toByteArray())
            reply(ex, 200, """{"tag_name":"edge","target_commitish":"abc1234","assets":[
              {"name":"couple-server-windows-x64-edge-abc1234.zip","url":"$api/repos/$repo/releases/assets/7",
               "browser_download_url":"${asset("edge-abc1234")}"},
              {"name":"couple-server-windows-x64-edge-abc1234.zip.sha256","url":"$api/repos/$repo/releases/assets/8",
               "browser_download_url":"${asset("edge-abc1234")}.sha256"}]}""".toByteArray())
        }
        server.createContext("/repos/$repo/releases/assets/") { ex ->
            val id = ex.requestURI.path.substringAfterLast('/')
            seen += "asset$id auth=${auth(ex)} accept=${ex.requestHeaders.getFirst("Accept")}"
            if (auth(ex) != "Bearer tok" || ex.requestHeaders.getFirst("Accept") != "application/octet-stream") {
                return@createContext reply(ex, 404, ByteArray(0))
            }
            reply(ex, 302, ByteArray(0), mapOf("Location" to "http://localhost:$port/storage/$id?sig=x"))
        }
        server.createContext("/storage/") { ex ->
            val id = ex.requestURI.path.substringAfterLast('/')
            seen += "storage$id auth=${auth(ex)}"
            if (auth(ex).isNotEmpty()) return@createContext reply(ex, 400, "two auth mechanisms".toByteArray())
            reply(ex, 200, if (id == "7") payload else "${sha(payload)}  couple-server-windows-x64-edge-abc1234.zip\n".toByteArray())
        }
        server.start()
        try {
            val dir = Files.createTempDirectory("gh-private")
            val cfg = Config(installDir = dir, envFile = null, githubApi = api, githubToken = "tok")
            val http = Http(null, null)
            val gh = GitHubApi(cfg, http)
            assertTrue(gh.sendsToken(java.net.URI("$api/repos/x")))
            assertFalse(gh.sendsToken(java.net.URI("http://localhost:$port/repos/x")))
            assertFalse(gh.sendsToken(java.net.URI("https://127.0.0.1:$port/repos/x")))
            // Release lookup with the token; no digest -> sidecar fetched through the API as well.
            val remote = assertNotNull(GitHubReleaseSource(cfg, http, gh).latest("edge"))
            assertEquals(sha(payload), remote.sha256)
            // Download of the public URL goes through the API asset URL with the token, storage without it.
            val plain = FakeDownloader(emptyMap())
            val dest = dir.resolve("x.zip")
            GitHubDownloader(gh, plain).download(remote.assetUrl, dest)
            assertContentEquals(payload, Files.readAllBytes(dest))
            assertTrue(plain.requested.isEmpty(), "token set: plain downloader unused")
            assertTrue(seen.contains("asset7 auth=Bearer tok accept=application/octet-stream"), seen.toString())
            assertTrue(seen.contains("storage7 auth="), seen.toString())
            assertTrue(seen.none { it.startsWith("storage") && it.contains("tok") }, seen.toString())
            // Without a token the public URL is used as before.
            val anon = GitHubApi(cfg.copy(githubToken = null), http)
            val plain2 = FakeDownloader(mapOf(asset("edge-abc1234") to payload))
            GitHubDownloader(anon, plain2).download(asset("edge-abc1234"), dir.resolve("y.zip"))
            assertEquals(listOf(asset("edge-abc1234")), plain2.requested)
        } finally {
            server.stop(0)
        }
    }
}
