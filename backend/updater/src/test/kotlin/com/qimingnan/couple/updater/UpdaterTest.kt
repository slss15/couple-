package com.qimingnan.couple.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private val repo = "slss15/couple-"
    private fun asset(v: String) = "https://github.com/$repo/releases/download/edge/couple-server-windows-x64-$v.zip"

    private fun cfg(dir: Path, secret: String = "s3cret") = Config(
        installDir = dir, envFile = dir.resolve("config/couple.env").toFile(), hookSecret = secret,
        healthTimeoutSeconds = 2, healthPollMillis = 10, keepReleases = 3,
    )

    // ---------------------------------------------------------------- HMAC / replay / allow-list

    @Test
    fun hmacMatchesPythonUpdaterScheme() {
        // python: hmac.new(b"k", b"123." + b'{"a":1}', sha256).hexdigest()
        assertEquals(
            javaHmac("k", "123.{\"a\":1}"),
            Hmac.sign("k", "123", "{\"a\":1}".toByteArray()),
        )
    }

    private fun javaHmac(k: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(k.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun verifyHookRules() {
        val dir = Files.createTempDirectory("upd")
        val replay = ReplayCache(dir.resolve("replay.json"), 300)
        val now = 1_700_000_000.0
        val ts = "1700000000"
        val body = """{"service":"server","ts":1700000000}""".toByteArray()
        val sig = Hmac.sign("k", ts, body)
        assertEquals(503, verifyHook("", ts, sig, body, now, 300, replay).status)
        assertEquals(401, verifyHook("k", null, sig, body, now, 300, replay).status)
        assertEquals(401, verifyHook("k", "abc", sig, body, now, 300, replay).status)
        assertEquals(401, verifyHook("k", ts, "00".repeat(32), body, now, 300, replay).status)
        assertEquals(401, verifyHook("k", ts, sig, body, now + 301, 300, replay).status)
        assertEquals(401, verifyHook("k", ts, sig, body, now - 301, 300, replay).status)
        val mismatch = """{"service":"server","ts":1}""".toByteArray()
        assertEquals(401, verifyHook("k", ts, Hmac.sign("k", ts, mismatch), mismatch, now, 300, replay).status)
        assertEquals(400, verifyHook("k", ts, Hmac.sign("k", ts, "[1]".toByteArray()), "[1]".toByteArray(), now, 300, replay).status)
        assertEquals(200, verifyHook("k", ts, sig.uppercase(), body, now, 300, replay).status)
        assertEquals(409, verifyHook("k", ts, sig, body, now, 300, replay).status)
        // Replay protection survives a restart (persisted).
        val reloaded = ReplayCache(dir.resolve("replay.json"), 300)
        assertEquals(409, verifyHook("k", ts, sig, body, now + 10, 300, reloaded).status)
        // ... and forgets entries after 2*ttl + 60 s.
        assertTrue(reloaded.checkAndAdd("other", now))
        assertTrue(reloaded.checkAndAdd(sig, now + 661))
    }

    @Test
    fun payloadAllowList() {
        val c = cfg(Files.createTempDirectory("upd"))
        fun p(url: String, version: String = "edge-abc1234", sha256: String = "a".repeat(64), service: String = "server") =
            Json.parseToJsonElement("""{"service":"$service","version":"$version","sha":"abc1234","assetUrl":"$url","sha256":"$sha256","ts":1}""").jsonObject
        assertNotNull(validatePayload(c, p(asset("edge-abc1234"))).second)
        assertNotNull(validatePayload(c, p("https://github.com/$repo/releases/download/v0.2.0/couple-server-windows-x64-0.2.0.zip", "0.2.0")).second)
        listOf(
            "http://github.com/$repo/releases/download/edge/couple-server-windows-x64-1.zip",
            "https://github.com/evil/couple-/releases/download/edge/couple-server-windows-x64-1.zip",
            "https://github.com/$repo/releases/download/../x/couple-server-windows-x64-1.zip",
            "https://github.com/$repo/releases/download/edge/sub/couple-server-windows-x64-1.zip",
            "https://github.com/$repo/releases/download/edge/evil.zip",
            "https://github.com/$repo/releases/download/edge/couple-server-windows-x64-1.zip?x=1",
            "https://github.com/$repo/releases/download/%2e%2e/couple-server-windows-x64-1.zip",
            "https://github.com.evil.com/$repo/releases/download/edge/couple-server-windows-x64-1.zip",
        ).forEach { assertNull(validatePayload(c, p(it)).second, it) }
        assertNull(validatePayload(c, p(asset("1"), version = "../x")).second)
        assertNull(validatePayload(c, p(asset("1"), sha256 = "xyz")).second)
        assertNull(validatePayload(c, p(asset("1"), service = "backend")).second)
    }

    @Test
    fun windowParsing() {
        assertTrue(Updater.inWindow("", LocalTime.of(12, 0)))
        assertTrue(Updater.inWindow("03:00-05:00", LocalTime.of(4, 0)))
        assertFalse(Updater.inWindow("03:00-05:00", LocalTime.of(5, 0)))
        assertTrue(Updater.inWindow("23:00-02:00", LocalTime.of(1, 0)))
        assertFalse(Updater.inWindow("23:00-02:00", LocalTime.of(12, 0)))
    }

    // ---------------------------------------------------------------- deploy / rollback with fakes

    private class FakeService : ServiceControl {
        val calls = mutableListOf<String>()
        override fun stop(): Boolean { calls += "stop"; return true }
        override fun start(): Boolean { calls += "start"; return true }
    }

    /** Healthy unless the rendered service config points at a release listed in [broken]. */
    private class FakeHealth(val servicesXml: Path, val broken: MutableSet<String> = mutableSetOf()) : HealthProbe {
        override fun status(url: String): Int {
            val xml = runCatching { Files.readString(servicesXml) }.getOrDefault("")
            return if (broken.any { xml.contains("releases${java.io.File.separator}$it") || xml.contains("releases/$it") }) 503 else 200
        }
    }

    private class FakeDownloader(val files: Map<String, ByteArray>) : Downloader {
        val requested = mutableListOf<String>()
        override fun download(url: String, dest: Path) {
            requested += url
            Files.write(dest, files[url] ?: throw java.io.IOException("404"))
        }
    }

    private fun packageZip(version: String, complete: Boolean = true): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry("couple-server-windows-x64-$version/$name")); z.write(text.toByteArray()); z.closeEntry() }
            put("VERSION", version)
            put("jre/bin/java.exe", "MZ")
            put("app/lib/couple-backend-$version.jar", "jar")
            if (complete) {
                put("service/couple-backend.xml.in", "<service><executable>@RELEASE_DIR@\\jre\\bin\\java.exe</executable><arguments>@JAVA_OPTS@</arguments><env name=\"APP_VERSION\" value=\"@VERSION@\"/><logpath>@INSTALL_DIR@\\logs</logpath></service>")
            }
        }
        return bos.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Env(val dir: Path, val up: Updater, val svc: FakeService, val health: FakeHealth, val dl: FakeDownloader, val posted: MutableList<String>)

    private fun env(zips: Map<String, ByteArray>, source: ReleaseSource = ReleaseSource { null }): Env {
        val dir = Files.createTempDirectory("couple-home")
        Files.createDirectories(dir.resolve("config"))
        Files.writeString(dir.resolve("config/couple.env"), "JAVA_OPTS=-Xmx256m -Dx=\"a&b\"\n")
        val svc = FakeService()
        val health = FakeHealth(dir.resolve("services/couple-backend.xml"))
        val dl = FakeDownloader(zips.mapKeys { asset(it.key) })
        val posted = mutableListOf<String>()
        var now = 0L
        val up = Updater(cfg(dir), svc, health, dl, source, postStatus = { posted += it }, clock = { now }, sleep = { now += it })
        return Env(dir, up, svc, health, dl, posted)
    }

    private fun job(v: String, zip: ByteArray) = DeployJob(v, asset(v), sha(zip), "abc1234")

    @Test
    fun deploySwitchesCurrentAndRendersTemplate() {
        val z1 = packageZip("1.0.0")
        val e = env(mapOf("1.0.0" to z1))
        assertEquals("success", e.up.deploy(job("1.0.0", z1), "hook"))
        assertEquals("1.0.0", e.up.releases.current())
        assertEquals("1.0.0", e.up.currentVersion())
        assertEquals(listOf("stop", "start"), e.svc.calls)
        val xml = Files.readString(e.dir.resolve("services/couple-backend.xml"))
        assertTrue(xml.contains(e.dir.resolve("releases").resolve("1.0.0").toString()), xml)
        assertTrue(xml.contains("-Xmx256m -Dx=&quot;a&amp;b&quot;"), xml)
        assertTrue(xml.contains("value=\"1.0.0\""))
        assertTrue(Files.isRegularFile(e.dir.resolve("releases/1.0.0/jre/bin/java.exe")), "top folder stripped")
        assertTrue(e.posted.last().contains("\"state\":\"success\""))
        // Same version again: noop, service untouched.
        assertEquals("noop", e.up.deploy(job("1.0.0", z1), "hook"))
        assertEquals(2, e.svc.calls.size)
    }

    @Test
    fun unhealthyReleaseRollsBackAndIsMarkedBad() {
        val z1 = packageZip("1.0.0"); val z2 = packageZip("2.0.0"); val z3 = packageZip("3.0.0")
        val e = env(mapOf("1.0.0" to z1, "2.0.0" to z2, "3.0.0" to z3), source = { RemoteAsset("2.0.0", asset("2.0.0"), sha(z2)) })
        assertEquals("success", e.up.deploy(job("1.0.0", z1), "hook"))
        e.health.broken += "2.0.0"
        assertEquals("rolled_back", e.up.deploy(job("2.0.0", z2), "hook"))
        assertEquals("1.0.0", e.up.releases.current())
        assertEquals("1.0.0", e.up.currentVersion())
        assertTrue(Files.readString(e.dir.resolve("services/couple-backend.xml")).contains("1.0.0"))
        assertTrue(sha(z2) in e.up.state.bad["server"]!!)
        val rec = e.up.state.deploys["server"]!!
        assertEquals("rolled_back", rec.state); assertEquals("health_timeout", rec.error); assertEquals("1.0.0", rec.previous)
        // The poller skips the bad build.
        val before = e.dl.requested.size
        e.up.pollOnce(LocalTime.NOON)
        assertEquals(before, e.dl.requested.size)
        // A newer good build still deploys.
        assertEquals("success", e.up.deploy(job("3.0.0", z3), "poll"))
        // State survives a restart of the updater.
        val again = Updater(e.up.cfg, e.svc, e.health, e.dl, { null })
        assertEquals("3.0.0", again.currentVersion())
        assertTrue(sha(z2) in again.state.bad["server"]!!)
    }

    @Test
    fun failuresBeforeSwitchNeverTouchTheService() {
        val good = packageZip("1.0.0"); val incomplete = packageZip("2.0.0", complete = false)
        val e = env(mapOf("1.0.0" to good, "2.0.0" to incomplete))
        // sha256 mismatch
        assertEquals("failed", e.up.deploy(DeployJob("1.0.0", asset("1.0.0"), "0".repeat(64), ""), "hook"))
        assertEquals("sha256_mismatch", e.up.state.deploys["server"]!!.error)
        // missing service template
        assertEquals("failed", e.up.deploy(job("2.0.0", incomplete), "hook"))
        // download failure
        assertEquals("failed", e.up.deploy(DeployJob("9.9.9", asset("9.9.9"), "1".repeat(64), ""), "hook"))
        assertTrue(e.svc.calls.isEmpty())
        assertNull(e.up.releases.current())
    }

    @Test
    fun firstDeployWithoutPreviousFailsCleanly() {
        val z = packageZip("1.0.0")
        val e = env(mapOf("1.0.0" to z))
        e.health.broken += "1.0.0"
        assertEquals("failed", e.up.deploy(job("1.0.0", z), "hook"))
        assertEquals("health_timeout", e.up.state.deploys["server"]!!.error)
    }

    @Test
    fun keepsLastThreeReleases() {
        val zips = (1..5).associate { "$it.0.0" to packageZip("$it.0.0") }
        val e = env(zips)
        for (i in 1..5) {
            assertEquals("success", e.up.deploy(job("$i.0.0", zips.getValue("$i.0.0")), "hook"))
            Thread.sleep(20) // distinct mtimes
        }
        val left = Files.list(e.dir.resolve("releases")).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() }.toSet()
        assertEquals(setOf("3.0.0", "4.0.0", "5.0.0"), left)
    }

    @Test
    fun zipSlipRejected() {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("pkg/ok.txt")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("pkg/../../evil.txt")); z.write(1); z.closeEntry()
        }
        val dir = Files.createTempDirectory("slip")
        val zip = dir.resolve("x.zip").also { Files.write(it, bos.toByteArray()) }
        val r = runCatching { Files2.extract(zip, dir.resolve("out")) }
        assertTrue(r.isFailure)
        assertFalse(Files.exists(dir.resolve("evil.txt")))
    }

    // ---------------------------------------------------------------- HTTP listener

    @Test
    fun httpHookEndpoint() {
        val z = packageZip("1.0.0")
        val e = env(mapOf("1.0.0" to z))
        val server = startHttp(e.up, "127.0.0.1", 0)
        val port = server.address.port
        val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
        fun post(body: String, ts: String = (System.currentTimeMillis() / 1000).toString(), sig: String? = null): Int =
            client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/_deploy/hook"))
                    .header("X-Deploy-Timestamp", ts)
                    .header("X-Deploy-Signature", sig ?: Hmac.sign("s3cret", ts, body.toByteArray()))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
        try {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val ok = """{"service":"server","version":"1.0.0","sha":"abc1234","assetUrl":"${asset("1.0.0")}","sha256":"${sha(z)}","ts":$ts}"""
            assertEquals(202, post(ok, ts))
            assertEquals(409, post(ok, ts)) // replay
            assertEquals(401, post(ok, ts, sig = "00"))
            assertEquals(413, post("x".repeat(MAX_BODY + 1)))
            val badUrl = ok.replace("slss15", "evil")
            assertEquals(400, post(badUrl, ts))
            val status = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/_deploy/status")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, status.statusCode())
            assertTrue(status.body().contains("\"target\":\"windows\""))
            assertFalse(status.body().contains("s3cret"))
            assertEquals(404, client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/other")).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
        } finally {
            server.stop(0)
        }
    }
}
