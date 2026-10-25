package com.qimingnan.couple.backend

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.TokenPair
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.config.MqttBrokerMode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

const val INVITE = "test-invite-code"

class TestClock(start: Long = System.currentTimeMillis()) {
    private val now = AtomicLong(start)
    fun now() = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

fun testConfig(
    clock: TestClock = TestClock(),
    dbName: String = "t" + UUID.randomUUID().toString().replace("-", ""),
    adminUsername: String? = null,
    bootstrapPrivateKey: String? = null,
    mqttHookSecret: String? = null,
    releasesDir: String = java.nio.file.Files.createTempDirectory("releases").toString(),
    mediaDir: String = java.nio.file.Files.createTempDirectory("media").toString(),
    githubApiBase: String = "http://127.0.0.1:9",
    // Most tests do not need a broker; the embedded broker tests opt in with a free port.
    brokerMode: MqttBrokerMode = MqttBrokerMode.EXTERNAL,
    mqttWsPort: Int = 8083,
    mqttDataDir: String? = null,
    adminStaticDir: String? = null,
    setupToken: String? = null,
    dataDir: String? = null,
    otaPublicKey: String? = null,
) = AppConfig(
    dbUrl = "jdbc:h2:mem:$dbName;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    dbUser = "sa",
    dbPassword = "",
    jwtSecret = "test-secret-test-secret-test-secret-0123456789",
    registrationCode = INVITE,
    adminUsername = adminUsername,
    bootstrapPrivateKey = bootstrapPrivateKey,
    mqttHookSecret = mqttHookSecret,
    authRateLimitPerMinute = 10_000,
    dbMaxPoolSize = 4,
    clock = clock::now,
    releasesDir = releasesDir,
    mediaDir = mediaDir,
    githubApiBase = githubApiBase,
    releasePollEnabled = false,
    releaseRetryBaseMs = 10,
    releaseRetryAttempts = 2,
    mqttBrokerMode = brokerMode,
    mqttWsPort = mqttWsPort,
    mqttDataDir = mqttDataDir,
    adminStaticDir = adminStaticDir,
    adminApiBase = "https://api.example.test",
    setupToken = setupToken,
    dataDir = dataDir,
    otaPublicKey = otaPublicKey,
)

fun appTest(config: AppConfig = testConfig(), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
    application { module(config) }
    val client = createClient { install(ContentNegotiation) { json(AppJson) } }
    block(client)
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) ->
    k to when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
})

suspend fun HttpClient.postJson(path: String, body: JsonObject? = null, token: String? = null): HttpResponse = post(path) {
    contentType(ContentType.Application.Json)
    if (token != null) bearerAuth(token)
    setBody(body ?: JsonObject(emptyMap()))
}

suspend fun HttpClient.register(username: String, device: String = "dev-$username", password: String = "password-123"): TokenPair {
    val res = postJson(
        "/api/v1/auth/register",
        obj(
            "username" to username, "password" to password, "displayName" to username.uppercase(),
            "deviceId" to device, "deviceName" to "Pixel", "inviteCode" to INVITE,
        ),
    )
    assertEquals(200, res.status.value, res.bodyAsText())
    return AppJson.decodeFromString(TokenPair.serializer(), res.bodyAsText())
}

suspend fun HttpResponse.json(): JsonObject = AppJson.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.errorCode(): String = json()["code"]!!.jsonPrimitive.content
