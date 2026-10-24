package com.qimingnan.couple.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Controls the backend Windows service. */
interface ServiceControl {
    fun stop(): Boolean
    fun start(): Boolean
}

/** Uses the WinSW wrapper (services\couple-backend.exe): `stopwait` blocks until the service has stopped. */
class WinswServiceControl(private val exe: Path, private val timeoutSeconds: Long = 120) : ServiceControl {
    override fun stop(): Boolean = run("stopwait")
    override fun start(): Boolean = run("start")

    private fun run(cmd: String): Boolean {
        val p = ProcessBuilder(exe.toString(), cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            log("winsw $cmd timed out")
            return false
        }
        val ok = p.exitValue() == 0
        if (!ok) log("winsw $cmd failed (${p.exitValue()}): ${out.trim().take(300)}")
        return ok
    }
}

fun interface HealthProbe {
    /** HTTP status of GET [url], or 0 when unreachable. */
    fun status(url: String): Int
}

fun interface Downloader {
    /** Downloads [url] to [dest] (overwriting). Throws on any failure. */
    fun download(url: String, dest: Path)
}

data class RemoteAsset(val version: String, val assetUrl: String, val sha256: String, val sha: String = "")

fun interface ReleaseSource {
    /** The newest server zip on [channel], or null when unavailable. */
    fun latest(channel: String): RemoteAsset?
}

fun log(msg: String) {
    println("${java.time.OffsetDateTime.now().withNano(0)} updater: $msg")
    System.out.flush()
}

/** java.net.http clients: one direct (loopback calls), one honouring HTTPS_PROXY / NO_PROXY for GitHub. */
class Http(proxyUrl: String?, noProxy: String?) {
    val outbound: HttpClient
    val direct: HttpClient

    init {
        // Must be set before the first java.net.http client exists: the JDK reads these once. Basic auth for CONNECT
        // tunnels is disabled by default, which breaks HTTPS_PROXY=http://user:pass@host:port.
        if (proxyUrl?.let { "@" in it } == true) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
        }
        direct = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            // NORMAL never follows https -> http; GitHub asset downloads redirect to its object storage over https.
            .followRedirects(HttpClient.Redirect.NORMAL)
        val proxy = proxyUrl?.takeIf { it.isNotBlank() }?.let { runCatching { URI(if ("://" in it) it else "http://$it") }.getOrNull() }
        if (proxy?.host != null) {
            val addr = InetSocketAddress.createUnresolved(proxy.host, if (proxy.port > 0) proxy.port else 80)
            val bypass = noProxy.orEmpty().split(',').map { it.trim().trimStart('*').lowercase() }.filter { it.isNotEmpty() }
            b.proxy(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val host = uri.host?.lowercase().orEmpty()
                    val skip = bypass.any { host == it.trimStart('.') || host.endsWith(if (it.startsWith(".")) it else ".$it") }
                    return listOf(if (skip) Proxy.NO_PROXY else Proxy(Proxy.Type.HTTP, addr))
                }
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            })
            proxy.rawUserInfo?.let { info ->
                val user = URLDecoder.decode(info.substringBefore(':'), Charsets.UTF_8)
                val pass = URLDecoder.decode(info.substringAfter(':', ""), Charsets.UTF_8)
                b.authenticator(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? =
                        if (requestorType == RequestorType.PROXY) PasswordAuthentication(user, pass.toCharArray()) else null
                })
            }
            log("outbound proxy ${proxy.host}:${addr.port}")
        }
        outbound = b.build()
    }

    fun health(): HealthProbe = HealthProbe { url ->
        try {
            direct.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: Exception) {
            0
        }
    }

    fun downloader(): Downloader = Downloader { url, dest ->
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).header("User-Agent", "couple-updater").GET().build()
        val tmp = dest.resolveSibling(dest.fileName.toString() + ".part")
        val res = outbound.send(req, HttpResponse.BodyHandlers.ofFile(tmp))
        if (res.statusCode() !in 200..299) {
            Files.deleteIfExists(tmp)
            throw IOException("HTTP ${res.statusCode()} downloading ${URI(url).host}")
        }
        if (res.uri().scheme != "https") throw IOException("download ended on a non-https URL")
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Posts the status JSON to the backend (best effort, loopback, never through a proxy). */
    fun postStatus(url: String, token: String, body: String) {
        if (url.isBlank() || token.isBlank()) return
        runCatching {
            direct.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }
}

/**
 * Finds the server zip on GitHub Releases: edge -> release tag `edge` (rolling prerelease), stable -> latest
 * non-prerelease, anything else -> tag v<channel>. The sha256 comes from the asset's `digest` field, falling back to
 * the `<zip>.sha256` asset.
 */
class GitHubReleaseSource(private val cfg: Config, private val http: Http) : ReleaseSource {
    private val nameRe = Regex("^couple-server-windows-x64-(.+)\\.zip$")

    override fun latest(channel: String): RemoteAsset? = try {
        val path = when (channel) {
            "edge" -> "releases/tags/edge"
            "stable", "latest" -> "releases/latest"
            else -> "releases/tags/v${channel.removePrefix("v")}"
        }
        val rel = Json.parseToJsonElement(get("${cfg.githubApi}/repos/${cfg.repo}/$path", api = true)).jsonObject
        val assets = (rel["assets"] as? JsonArray) ?: JsonArray(emptyList())
        val zip = assets.map { it.jsonObject }.firstOrNull { nameRe.matches(it.str("name")) }
        if (zip == null) {
            null
        } else {
            val name = zip.str("name")
            val url = zip.str("browser_download_url")
            val digest = zip.str("digest").removePrefix("sha256:").lowercase()
            val sha256 = if (digest.length == 64) digest else {
                val sidecar = assets.map { it.jsonObject }.firstOrNull { it.str("name") == "$name.sha256" }
                sidecar?.let { get(it.str("browser_download_url"), api = false).trim().split(Regex("\\s+")).first().lowercase() }.orEmpty()
            }
            val version = nameRe.find(name)!!.groupValues[1]
            if (sha256.length != 64) null else RemoteAsset(version, url, sha256, rel.str("target_commitish").takeIf { Regex("^[0-9a-f]{7,40}$").matches(it) } ?: "")
        }
    } catch (e: Exception) {
        log("release lookup ($channel) failed: ${e.message}")
        null
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""

    private fun get(url: String, api: Boolean): String {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "couple-updater")
        if (api) {
            b.header("Accept", "application/vnd.github+json")
            cfg.githubToken?.let { b.header("Authorization", "Bearer $it") }
        }
        val res = http.outbound.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IOException("HTTP ${res.statusCode()} from ${URI(url).host}")
        return res.body()
    }
}

object Files2 {
    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteTree(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /**
     * Extracts [zip] into [dest] (must not exist). Rejects entries escaping [dest]. When every entry sits under one
     * top-level folder (couple-server-windows-x64-<v>/...), that folder is stripped.
     */
    fun extract(zip: Path, dest: Path) {
        val names = ZipInputStream(Files.newInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.map { it.name.replace('\\', '/') }.toList()
        }
        if (names.isEmpty()) throw IOException("empty zip")
        val tops = names.map { it.substringBefore('/') }.toSet()
        val top = tops.singleOrNull()
        val strip = if (top != null && top != ".." && top != "." && top.isNotEmpty() && names.all { it.contains('/') }) "$top/" else ""
        Files.createDirectories(dest)
        val root = dest.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.replace('\\', '/').removePrefix(strip)
                if (name.isEmpty()) continue
                val out = root.resolve(name).normalize()
                if (!out.startsWith(root) || out == root) throw IOException("zip entry escapes target: ${e.name}")
                if (e.isDirectory || name.endsWith("/")) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
