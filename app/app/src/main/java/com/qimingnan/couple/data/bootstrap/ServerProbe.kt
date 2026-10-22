package com.qimingnan.couple.data.bootstrap

import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.di.PlainClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HealthResult {
    data class Ok(val version: String?, val protocolVersion: Int?) : HealthResult
    /** 服务器可达但协议不兼容 —— 不必再等，直接回滚。 */
    data class Incompatible(val reason: String) : HealthResult
    data class Unreachable(val reason: String) : HealthResult
}

/** 与新服务器交互的两个只读探针：公钥与健康检查。 */
interface ServerProbe {
    suspend fun fetchPublicKey(baseUrl: String): ByteArray
    suspend fun health(baseUrl: String): HealthResult
}

fun apiRoot(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1"

@Singleton
class OkHttpServerProbe @Inject constructor(
    @PlainClient private val client: OkHttpClient,
) : ServerProbe {

    override suspend fun fetchPublicKey(baseUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiRoot(baseUrl) + "/bootstrap/pubkey").get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BootstrapError.PubkeyUnavailable("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val parsed = CoupleJson.decodeFromString(PubkeyResponse.serializer(), body)
                if (!parsed.alg.equals("Ed25519", ignoreCase = true)) {
                    throw BootstrapError.PubkeyUnavailable("不支持的算法 ${parsed.alg}")
                }
                Ed25519Verifier.normalizePublicKey(parsed.publicKey)
            }
        } catch (e: BootstrapError) {
            throw e
        } catch (e: Exception) {
            throw BootstrapError.PubkeyUnavailable(e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun health(baseUrl: String): HealthResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiRoot(baseUrl) + "/health").get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext HealthResult.Unreachable("HTTP ${resp.code}")
                val h = CoupleJson.decodeFromString(HealthResponse.serializer(), resp.body?.string().orEmpty())
                when {
                    h.status != "ok" -> HealthResult.Unreachable("status=${h.status}")
                    h.protocolVersion != null && h.protocolVersion != BuildConfig.PROTOCOL_VERSION ->
                        HealthResult.Incompatible("协议版本不兼容：服务器 ${h.protocolVersion}，App ${BuildConfig.PROTOCOL_VERSION}")
                    else -> HealthResult.Ok(h.version, h.protocolVersion)
                }
            }
        } catch (e: IOException) {
            HealthResult.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            HealthResult.Unreachable("响应无法解析：${e.message}")
        }
    }
}
