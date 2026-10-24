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

    /** Same proxy settings as [outbound], but redirects are handled by the caller (see [GitHubApi.fetchAsset]). */
    val outboundNoRedirect: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER)
            .also { b -> outbound.proxy().ifPresent { b.proxy(it) }; outbound.authenticator().ifPresent { b.authenticator(it) } }
            .build()
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
 * GitHub REST calls of the updater. With UPDATE_GITHUB_TOKEN set, release lookups AND asset downloads are
 * authenticated, so private repositories work: assets are fetched through the API asset URL
 * (`GET /repos/<repo>/releases/assets/<id>`, `Accept: application/octet-stream`), which answers with a redirect to a
 * short-lived signed storage URL. Redirects are followed by hand and the token is only ever sent to the API host
 * (the signed URL rejects a second credential and must never see the token).
 */
class GitHubApi(private val cfg: Config, private val http: Http) {
    private val apiUri = URI(cfg.githubApi)
    private val token = cfg.githubToken?.takeIf { it.isNotBlank() }

    val authenticated: Boolean get() = token != null

    /** True when [uri] may receive the token: same scheme/host/port as the configured API base. */
    fun sendsToken(uri: URI): Boolean = token != null && uri.scheme.equals(apiUri.scheme, true) &&
        uri.host.equals(apiUri.host, true) && uri.port == apiUri.port

    fun getJson(path: String): String {
        val url = "${cfg.githubApi}/repos/${cfg.repo}/$path"
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "couple-updater")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        token?.let { b.header("Authorization", "Bearer $it") }
        val res = http.outbound.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IOException("HTTP ${res.statusCode()} from ${URI(url).host}")
        return res.body()
    }

    /** Release JSON for [tag] (edge, v0.2.0, ...). */
    fun releaseByTag(tag: String): JsonObject = Json.parseToJsonElement(getJson("releases/tags/$tag")).jsonObject

    /**
     * Downloads an asset through its API URL ([apiAssetUrl] = the asset's `url` field) to [dest], or returns its
     * body as text when [dest] is null (sidecar .sha256 files).
     */
    fun fetchAsset(apiAssetUrl: String, dest: Path?): String? {
        var uri = URI(apiAssetUrl)
        if (!sendsToken(uri) && token != null) throw IOException("asset URL is not on ${apiUri.host}")
        val requireHttps = apiUri.scheme == "https"
        repeat(6) {
            if (requireHttps && uri.scheme != "https") throw IOException("refusing non-https URL ${uri.host}")
            val b = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30)).header("User-Agent", "couple-updater")
                .header("Accept", "application/octet-stream")
            if (sendsToken(uri)) b.header("Authorization", "Bearer $token").header("X-GitHub-Api-Version", "2022-11-28")
            val req = b.GET().build()
            val tmp = dest?.resolveSibling(dest.fileName.toString() + ".part")
            val res: HttpResponse<*> = if (tmp != null) {
                http.outboundNoRedirect.send(req, HttpResponse.BodyHandlers.ofFile(tmp))
            } else {
                http.outboundNoRedirect.send(req, HttpResponse.BodyHandlers.ofString())
            }
            when (res.statusCode()) {
                in 200..299 -> {
                    if (tmp != null && dest != null) {
                        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
                        return null
                    }
                    return res.body() as String
                }
                301, 302, 303, 307, 308 -> {
                    tmp?.let { Files.deleteIfExists(it) }
                    val loc = res.headers().firstValue("Location").orElseThrow { IOException("redirect without Location") }
                    uri = uri.resolve(loc)
                }
                else -> {
                    tmp?.let { Files.deleteIfExists(it) }
                    throw IOException("HTTP ${res.statusCode()} downloading ${uri.host}")
                }
            }
        }
        throw IOException("too many redirects downloading asset")
    }

    /**
     * Maps a public download URL (https://github.com/<repo>/releases/download/<tag>/<name>, already allow-listed) to
     * the API asset URL by looking the asset up in release <tag>.
     */
    fun apiUrlFor(downloadUrl: String): String {
        val rest = downloadUrl.removePrefix(cfg.assetPrefix)
        val tag = rest.substringBefore('/')
        val name = rest.substringAfter('/')
        val rel = releaseByTag(tag)
        val assets = (rel["assets"] as? JsonArray) ?: JsonArray(emptyList())
        val asset = assets.map { it.jsonObject }.firstOrNull { it.str("name") == name }
            ?: throw IOException("asset $name not found in release $tag")
        return asset.str("url").takeIf { it.isNotEmpty() } ?: throw IOException("asset $name has no API url")
    }
}

internal fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""

/** Downloads release zips: the public URL without a token, the authenticated API asset URL with one. */
class GitHubDownloader(private val api: GitHubApi, private val plain: Downloader) : Downloader {
    override fun download(url: String, dest: Path) {
        if (!api.authenticated) return plain.download(url, dest)
        api.fetchAsset(api.apiUrlFor(url), dest)
    }
}

/**
 * Finds the server zip on GitHub Releases: edge -> release tag `edge` (rolling prerelease), stable -> latest
 * non-prerelease, anything else -> tag v<channel>. The sha256 comes from the asset's `digest` field, falling back to
 * the `<zip>.sha256` asset (fetched through the API when a token is set).
 */
class GitHubReleaseSource(private val cfg: Config, private val http: Http, private val api: GitHubApi = GitHubApi(cfg, http)) : ReleaseSource {
    private val nameRe = Regex("^couple-server-windows-x64-(.+)\\.zip$")

    override fun latest(channel: String): RemoteAsset? = try {
        val path = when (channel) {
            "edge" -> "releases/tags/edge"
            "stable", "latest" -> "releases/latest"
            else -> "releases/tags/v${channel.removePrefix("v")}"
        }
        val rel = Json.parseToJsonElement(api.getJson(path)).jsonObject
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
                sidecar?.let {
                    val text = if (api.authenticated && it.str("url").isNotEmpty()) api.fetchAsset(it.str("url"), null).orEmpty()
                    else getPlain(it.str("browser_download_url"))
                    text.trim().split(Regex("\\s+")).first().lowercase()
                }.orEmpty()
            }
            val version = nameRe.find(name)!!.groupValues[1]
            if (sha256.length != 64) null else RemoteAsset(version, url, sha256, rel.str("target_commitish").takeIf { Regex("^[0-9a-f]{7,40}$").matches(it) } ?: "")
        }
    } catch (e: Exception) {
        log("release lookup ($channel) failed: ${e.message}")
        null
    }

    private fun getPlain(url: String): String {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "couple-updater").GET().build()
        val res = http.outbound.send(req, HttpResponse.BodyHandlers.ofString())
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
