package com.qimingnan.couple.data.demo

import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.bootstrap.ServerConfig
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.PairCode
import com.qimingnan.couple.data.net.TokenPair
import com.qimingnan.couple.data.net.User
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 离线调试账号（只在 debug 包可用）：一个进程内的假后端，不需要任何服务器。
 *
 * 所有发往 [HOST] 的请求都由 [DemoInterceptor] 在本机直接应答，因此 引导 → 登录 → 配对 → 主界面 → 我的 → 解绑
 * 的整条 UI 流程都能跑通。MQTT 不连接，伴侣在线状态由 AppCoordinator 直接注入。
 */
object DemoBackend {
    const val HOST = "demo.couple.local"
    const val BASE_URL = "https://$HOST"
    const val MQTT_URL = "wss://$HOST/mqtt"
    const val USERNAME = "demo"
    const val PASSWORD = "demo123"

    /** 调试入口只在 debug 构建出现；release 包里这个假后端永远不会被走到。 */
    val available: Boolean get() = BuildConfig.DEBUG

    fun isDemo(baseUrl: String?): Boolean = baseUrl != null && runCatching { java.net.URI(baseUrl).host == HOST }.getOrDefault(false)
    fun isDemoUrl(url: String?): Boolean = url != null && runCatching { java.net.URI(url).host == HOST }.getOrDefault(false)

    fun config(now: Long): ServerConfig = ServerConfig(
        baseUrl = BASE_URL, mqttUrl = MQTT_URL, configVersion = 1, issuedAt = now, signed = false, appliedAt = now,
    )

    val partner = User(id = "demo-partner", username = "ta", displayName = "TA（演示）", createdAt = 1_700_000_000_000)

    /** 假后端的内存状态（进程重启后回到“已配对”）。 */
    @Volatile var paired: Boolean = true
    @Volatile var displayName: String = "我（调试）"

    fun self(): User = User(
        id = "demo-self", username = USERNAME, displayName = displayName,
        coupleId = if (paired) COUPLE_ID else null, createdAt = 1_700_000_000_000,
    )

    const val COUPLE_ID = "demo-couple"
    fun couple(): Couple = Couple(id = COUPLE_ID, members = listOf(self(), partner), pairedAt = 1_735_689_600_000)

    fun tokens(now: Long): TokenPair = TokenPair(
        accessToken = "demo-access", refreshToken = "demo-refresh", accessExpiresAt = now + 24 * 3600_000L, user = self(),
    )

    /** 纯函数：根据方法 + 路径给出 (HTTP 状态码, JSON body)。方便 JVM 单测。 */
    fun respond(method: String, path: String, now: Long): Pair<Int, String> {
        val p = path.removePrefix("/api/v1/").removePrefix("/api/v1").trimEnd('/')
        fun <T> ok(s: KSerializer<T>, v: T) = 200 to CoupleJson.encodeToString(s, v)
        return when ("$method $p") {
            "GET health" -> 200 to buildJsonObject { put("status", "ok"); put("version", "demo"); put("protocolVersion", 1) }.toString()
            "GET bootstrap/pubkey" -> 404 to err("NOT_FOUND", "演示模式没有签名公钥")
            "POST auth/login", "POST auth/register", "POST auth/refresh" -> ok(TokenPair.serializer(), tokens(now))
            "POST auth/logout" -> 204 to ""
            "GET couple" -> if (paired) ok(Couple.serializer(), couple()) else 404 to err("NOT_PAIRED", "还没有配对")
            "POST pair/code" -> ok(PairCode.serializer(), PairCode("520131", now + 10 * 60_000L))
            "POST pair/join" -> { paired = true; ok(Couple.serializer(), couple()) }
            "POST couple/unpair/request" -> 200 to """{"requestId":"demo-unpair"}"""
            "POST couple/unpair/confirm" -> { paired = false; 204 to "" }
            "GET app/update" -> 200 to """{"latest":null,"force":false}"""
            "POST app/health-report" -> 204 to ""
            else -> 404 to err("NOT_FOUND", "演示后端未实现：$method /$p")
        }
    }

    private fun err(code: String, message: String) = buildJsonObject { put("code", code); put("message", message) }.toString()
}

/** 放在 OkHttp 链最前面：只拦截 [DemoBackend.HOST]，其余请求原样放行。 */
class DemoInterceptor(private val now: () -> Long = System::currentTimeMillis) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req: Request = chain.request()
        if (!DemoBackend.available || req.url.host != DemoBackend.HOST) return chain.proceed(req)
        val (code, body) = DemoBackend.respond(req.method, req.url.encodedPath, now())
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Demo")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
