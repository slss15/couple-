package com.qimingnan.couple.backend.chat

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class StickerView(val url: String, val width: Int? = null, val height: Int? = null)

/** Minimal outbound GET (injectable for tests). Never follows redirects on its own. */
fun interface StickerHttp {
    /** Header names are lower-cased. Reads at most [maxBytes] + 1 body bytes, so callers can detect oversize. */
    data class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    fun get(url: String, maxBytes: Int): Resp

    companion object {
        val Default: StickerHttp = JdkStickerHttp()
    }
}

class JdkStickerHttp : StickerHttp {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    override fun get(url: String, maxBytes: Int): StickerHttp.Resp {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "couple-backend/1 (sticker proxy)").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        val body = resp.body().use { it.readNBytes(maxBytes + 1) }
        val headers = resp.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
        return StickerHttp.Resp(resp.statusCode(), headers, body)
    }
}

/** apihz fallback credentials from admin config `stickers` (`id` + write-only `apiKey`). */
data class ApihzCreds(val id: String, val key: String)

/**
 * Sticker search proxy (contract "Chat" §stickers) and the guarded downloader used by `type:"sticker"` messages.
 * Downloads only from hosts that appeared in provider results (in-memory allowlist), https/http only, ≤ 5 MB,
 * image/any only, ≤ 3 redirects, and never to loopback / private / link-local addresses.
 */
class StickerService(
    private val http: StickerHttp,
    private val resolve: (String) -> List<InetAddress>,
    private val creds: suspend () -> ApihzCreds?,
) {
    private val log = LoggerFactory.getLogger(StickerService::class.java)
    private val seenHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun allowedHosts(): Set<String> = seenHosts.toSet()

    suspend fun search(q: String?, page: Int?): List<StickerView> {
        val query = q?.trim().orEmpty()
        if (query.isEmpty() || query.length > 50) throw badRequest("VALIDATION_ERROR", "q must be 1..50 chars")
        val p = (page ?: 1).coerceIn(1, 50)
        val enc = URLEncoder.encode(query, Charsets.UTF_8)
        val primary = runCatching { oiapi(enc, p) }.onFailure { log.info("oiapi sticker search failed: {}", it.message) }.getOrNull()
        val result = if (!primary.isNullOrEmpty()) primary else {
            val c = creds()
            if (c == null) primary.orEmpty() else runCatching { apihz(c, enc, p) }
                .onFailure { log.info("apihz sticker search failed: {}", it.message) }.getOrDefault(emptyList())
        }
        val clean = result.filter { isWebUrl(it.url) }
        clean.forEach { u -> URI(u.url).host?.lowercase()?.let(seenHosts::add) }
        return clean
    }

    private fun oiapi(enc: String, page: Int): List<StickerView> {
        val r = http.get("https://oiapi.net/api/EmoticonPack?keyword=$enc&page=$page&limit=30", SEARCH_MAX)
        if (r.status != 200) error("oiapi HTTP ${r.status}")
        val root = AppJson.parseToJsonElement(r.body.decodeToString()) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            StickerView(url, o["width"]?.jsonPrimitive?.intOrNull, o["height"]?.jsonPrimitive?.intOrNull)
        }
    }

    private fun apihz(c: ApihzCreds, enc: String, page: Int): List<StickerView> {
        val id = URLEncoder.encode(c.id, Charsets.UTF_8)
        val key = URLEncoder.encode(c.key, Charsets.UTF_8)
        val r = http.get("https://cn.apihz.cn/api/img/apihzbqb.php?id=$id&key=$key&type=2&words=$enc&page=$page&limit=20", SEARCH_MAX)
        if (r.status != 200) error("apihz HTTP ${r.status}")
        val root = AppJson.parseToJsonElement(r.body.decodeToString()) as? JsonObject ?: return emptyList()
        val res = root["res"] as? JsonArray ?: return emptyList()
        return res.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { u -> StickerView(u) } }
    }

    data class Fetched(val bytes: ByteArray, val mimeType: String)

    /** Downloads a sticker URL under the guard rails; 400 STICKER_REJECTED / 502 STICKER_FETCH_FAILED on failure. */
    fun fetch(url: String): Fetched {
        if (!isWebUrl(url)) throw badRequest("VALIDATION_ERROR", "stickerUrl must be an http(s) url")
        val origin = URI(url).host.lowercase()
        if (origin !in seenHosts) throw badRequest("STICKER_REJECTED", "sticker host not allowed: $origin")
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val uri = URI(current)
            val host = uri.host?.lowercase() ?: throw badRequest("STICKER_REJECTED", "bad redirect")
            if (host != origin && host !in seenHosts && !sameSite(host, origin)) {
                throw badRequest("STICKER_REJECTED", "redirect to a host that is not allowed: $host")
            }
            val addrs = runCatching { resolve(host) }.getOrElse { throw stickerFailed("cannot resolve $host") }
            if (addrs.isEmpty() || addrs.any { isPrivate(it) }) throw badRequest("STICKER_REJECTED", "sticker host resolves to a private address")
            val r = runCatching { http.get(current, MAX_BYTES) }.getOrElse { throw stickerFailed("download failed: ${it.message}") }
            if (r.status in 300..399) {
                val loc = r.headers["location"] ?: throw stickerFailed("redirect without location")
                current = uri.resolve(loc).toString()
                if (!isWebUrl(current)) throw badRequest("STICKER_REJECTED", "redirect to a non-http url")
                return@repeat
            }
            if (r.status != 200) throw stickerFailed("upstream HTTP ${r.status}")
            if (r.body.size > MAX_BYTES) throw badRequest("STICKER_REJECTED", "sticker larger than 5 MB")
            val ct = r.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (!ct.startsWith("image/")) throw badRequest("STICKER_REJECTED", "not an image ($ct)")
            if (r.body.isEmpty()) throw stickerFailed("empty body")
            return Fetched(r.body, if (ct == "image/jpg") "image/jpeg" else ct)
        }
        throw badRequest("STICKER_REJECTED", "too many redirects")
    }

    private fun stickerFailed(msg: String) =
        com.qimingnan.couple.backend.common.ApiException(io.ktor.http.HttpStatusCode.BadGateway, "STICKER_FETCH_FAILED", msg)

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        private const val SEARCH_MAX = 2 * 1024 * 1024

        fun isWebUrl(u: String): Boolean = runCatching {
            val uri = URI(u)
            (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)

        /** Same registrable-ish site (last two labels), e.g. a CDN redirect inside gtimg.com. */
        fun sameSite(a: String, b: String): Boolean {
            fun site(h: String) = h.split('.').takeLast(2).joinToString(".")
            return a.contains('.') && site(a) == site(b)
        }

        fun isPrivate(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> {
                    val b0 = b[0].toInt() and 0xFF
                    val b1 = b[1].toInt() and 0xFF
                    b0 == 0 || (b0 == 100 && b1 in 64..127) || (b0 == 192 && b1 == 0 && (b[2].toInt() and 0xFF) == 0) ||
                        (b0 == 198 && b1 in 18..19) || b0 >= 240
                }
                is Inet6Address -> {
                    val b0 = b[0].toInt() and 0xFF
                    (b0 and 0xFE) == 0xFC || // fc00::/7 unique-local
                        (b.take(10).all { it.toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF &&
                            isPrivate(InetAddress.getByAddress(b.copyOfRange(12, 16)))) // v4-mapped
                }
                else -> true
            }
        }
    }
}
