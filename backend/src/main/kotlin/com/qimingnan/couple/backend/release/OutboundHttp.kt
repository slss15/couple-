package com.qimingnan.couple.backend.release

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
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
import java.time.Duration

/**
 * Outbound HTTP for the release mirror (the home server is behind NAT: it can only call out).
 * - Honours HTTPS_PROXY / NO_PROXY (GitHub can be slow or blocked from mainland China), including proxy credentials.
 * - Follows redirects manually and drops Authorization when the host changes (GitHub asset downloads redirect to
 *   a pre-signed object-storage URL that rejects a second auth mechanism).
 * - Retries connection errors, 429 and 5xx with exponential backoff.
 */
class OutboundHttp(
    proxyUrl: String?,
    noProxy: String?,
    private val retryBaseMs: Long,
    private val attempts: Int,
) {
    private val log = LoggerFactory.getLogger(OutboundHttp::class.java)
    private val client: HttpClient

    init {
        val b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
        val proxy = proxyUrl?.let { runCatching { URI(if ("://" in it) it else "http://$it") }.getOrNull() }
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
                // Basic auth for CONNECT tunnels is disabled by default in the JDK.
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
                System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
                b.authenticator(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? =
                        if (requestorType == RequestorType.PROXY) PasswordAuthentication(user, pass.toCharArray()) else null
                })
            }
            log.info("Release mirror uses outbound proxy {}:{}", proxy.host, addr.port)
        }
        client = b.build()
    }

    class HttpStatusException(val status: Int, message: String) : IOException(message)

    /** GET returning the body as a string (for small JSON documents). */
    suspend fun getString(url: String, headers: Map<String, String>): String =
        withRetry(url) { send(url, headers, HttpResponse.BodyHandlers.ofString()).body() }

    /** GET streaming the body into [consume]; retried as a whole (consume must be restartable). */
    suspend fun <T> getStream(url: String, headers: Map<String, String>, consume: (InputStream) -> T): T =
        withRetry(url) { send(url, headers, HttpResponse.BodyHandlers.ofInputStream()).body().use(consume) }

    private suspend fun <T> send(url: String, headers: Map<String, String>, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        var uri = URI(url)
        val originalHost = uri.host
        repeat(6) {
            val rb = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET()
            for ((k, v) in headers) {
                if (k.equals("Authorization", true) && !uri.host.equals(originalHost, true)) continue
                rb.header(k, v)
            }
            val res = client.sendAsync(rb.build(), handler).await()
            when (val code = res.statusCode()) {
                in 200..299 -> return res
                301, 302, 303, 307, 308 -> {
                    closeQuietly(res.body())
                    val loc = res.headers().firstValue("Location").orElseThrow { HttpStatusException(code, "redirect without Location") }
                    uri = uri.resolve(loc)
                }
                else -> {
                    closeQuietly(res.body())
                    throw HttpStatusException(code, "HTTP $code from ${uri.host}")
                }
            }
        }
        throw HttpStatusException(310, "too many redirects")
    }

    private fun closeQuietly(body: Any?) {
        (body as? InputStream)?.let { runCatching { it.close() } }
    }

    private suspend fun <T> withRetry(url: String, block: suspend () -> T): T {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                return block()
            } catch (e: HttpStatusException) {
                if (e.status != 429 && e.status < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (i < attempts - 1) {
                val wait = retryBaseMs shl i
                log.warn("Outbound GET {} failed ({}); retry {}/{} in {} ms", URI(url).host, last?.message, i + 1, attempts - 1, wait)
                delay(wait)
            }
        }
        throw last ?: IOException("request failed")
    }
}
