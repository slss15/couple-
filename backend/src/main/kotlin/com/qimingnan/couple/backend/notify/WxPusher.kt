package com.qimingnan.couple.backend.notify

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Admin config `notify.wxpusher`: {"appToken": "<write-only secret>", "uids": ["UID_xxx", ...]}. */
data class WxPusherSettings(val appToken: String, val uids: List<String>) {
    val configured: Boolean get() = appToken.isNotBlank() && uids.isNotEmpty()

    companion object {
        val NONE = WxPusherSettings("", emptyList())

        fun fromConfig(notify: JsonObject?): WxPusherSettings {
            val wx = notify?.get("wxpusher") as? JsonObject ?: return NONE
            val token = (wx["appToken"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
            val uids = (wx["uids"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            return WxPusherSettings(token, uids)
        }
    }
}

/**
 * WxPusher (wechat service channel) alerts for things the operator must know without opening the admin:
 * deploy failures / rollbacks reported by the server-updater, auto-paused releases, canary timeouts.
 *
 * Best effort by design: nothing here may take the API path down. Unconfigured = silent no-op; send failures
 * are logged and swallowed. Sends are debounced per [WxPusherService.notify] key so a flapping updater cannot
 * spam wechat (an alert that fires once per 10 minutes is enough).
 */
class WxPusherService(
    private val config: AppConfig,
    private val settingsProvider: suspend () -> WxPusherSettings,
    /** Returns true on HTTP 2xx. Injectable so tests never touch the network. */
    private val send: suspend (String, String) -> Boolean = ::defaultSend,
) {
    private val lastSent = ConcurrentHashMap<String, Long>()

    /**
     * Sends [summary]/[content] when the channel is configured and [key] has not been sent within
     * [minIntervalMs]. The debounce mark is written before the send (at-most-once per window is fine for alerts).
     */
    suspend fun notify(key: String, summary: String, content: String, minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val s = runCatching { settingsProvider() }.getOrNull() ?: return false
        if (!s.configured) return false
        val now = config.clock()
        val prev = lastSent[key]
        if (prev != null && now - prev < minIntervalMs) return false
        lastSent[key] = now
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("appToken", s.appToken)
                put("content", content.take(2000))
                put("summary", summary.take(100))
                put("contentType", 1)
                put("uids", JsonArray(s.uids.map { JsonPrimitive(it) }))
            },
        )
        val ok = runCatching { send(WXPUSHER_API, body) }.getOrDefault(false)
        if (!ok) log.warn("wxpusher notify {} failed (debounced until {} ms from now)", key, minIntervalMs)
        return ok
    }

    companion object {
        const val WXPUSHER_API = "https://wxpusher.zjiecode.com/api/send/message"
        const val DEFAULT_MIN_INTERVAL_MS = 10 * 60_000L
        private val log = LoggerFactory.getLogger(WxPusherService::class.java)

        /** Blocking POST on the IO dispatcher; the WxPusher API answers in well under a second. */
        private suspend fun defaultSend(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }
    }
}
