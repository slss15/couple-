package com.qimingnan.couple.backend.mqtt

import com.qimingnan.couple.backend.config.AppConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Server→client MQTT push (contract "Feed" §MQTT push): the backend publishes events to `couple/{cid}/<topic>`
 * after the DB commit. MQTT is a hint — clients merge by id and refetch; push being disabled only costs latency.
 */
interface ServerPush {
    suspend fun publish(coupleId: String, topic: String, payload: String)
}

/** Embedded broker: publish in-process through Moquette's internalPublish. */
class EmbeddedServerPush(private val broker: EmbeddedMqttBroker) : ServerPush {
    override suspend fun publish(coupleId: String, topic: String, payload: String) {
        broker.publishToCouple(coupleId, "couple/$coupleId/$topic", payload)
    }
}

/** External EMQX: its HTTP publish API (env EMQX_API_URL / EMQX_API_KEY / EMQX_API_SECRET). */
class EmqxHttpPush(apiUrl: String, apiKey: String, apiSecret: String) : ServerPush {
    private val log = LoggerFactory.getLogger(EmqxHttpPush::class.java)
    private val publishUrl = apiUrl.trimEnd('/') + "/api/v5/publish"
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$apiKey:$apiSecret".toByteArray())
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { encodeDefaults = true }

    override suspend fun publish(coupleId: String, topic: String, payload: String) {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("topic", "couple/$coupleId/$topic")
                put("payload", payload)
                put("qos", 1)
                put("retain", false)
            },
        )
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(publishUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (code !in 200..299) log.warn("EMQX publish {} returned {}", topic, code)
        }.onFailure { log.warn("EMQX publish {} failed: {}", topic, it.message) }
    }
}

/** No config (or embedded broker without a broker instance): push disabled, catch-up still works. */
object NullServerPush : ServerPush {
    override suspend fun publish(coupleId: String, topic: String, payload: String) {}
}

fun serverPushFor(config: AppConfig, broker: EmbeddedMqttBroker?): ServerPush = when {
    broker != null -> EmbeddedServerPush(broker)
    config.emqxApiUrl != null && config.emqxApiKey != null && config.emqxApiSecret != null ->
        EmqxHttpPush(config.emqxApiUrl, config.emqxApiKey, config.emqxApiSecret)
    else -> NullServerPush
}
