package com.qimingnan.couple.data.auth

import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.net.RefreshRequest
import com.qimingnan.couple.data.net.TokenPair
import com.qimingnan.couple.data.net.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 统一的 token 刷新入口（OkHttp Authenticator 与 MQTT 连接前都会用）。
 * - 同一时间只会有一次刷新（synchronized）。
 * - 刷新成功：新 refresh token 立即持久化（服务端每次轮换，旧的再用会吊销整族）。
 * - 服务端明确拒绝（4xx）：清空本地 token 并发出 [sessionExpired]。
 * - 网络错误：保留 token，返回 null，下次再试。
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    private val client: OkHttpClient,
    private val apiRootProvider: () -> String?,
    private val deviceIdProvider: () -> String,
    private val clock: Clock = Clock.System,
) {
    private val _sessionExpired = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sessionExpired: SharedFlow<String> = _sessionExpired

    private val _userUpdates = MutableSharedFlow<User>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 刷新响应里带的最新 User（例如配对后 coupleId 变化）。 */
    val userUpdates: SharedFlow<User> = _userUpdates

    /**
     * @param staleAccessToken 触发刷新的那个 access token；若本地已经是更新的 token，直接返回本地的。
     * @param force true 时即使未过期也刷新（配对/解绑后需要拿到新的 cid claim）。
     */
    @Synchronized
    fun refreshBlocking(staleAccessToken: String?, force: Boolean = false): String? {
        val current = tokenStore.read() ?: return null
        if (!force && staleAccessToken != null && current.accessToken != staleAccessToken) {
            return current.accessToken
        }
        val root = apiRootProvider() ?: return null
        val body = CoupleJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(current.refreshToken, deviceIdProvider()))
        val req = Request.Builder()
            .url("$root/auth/refresh")
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val pair = CoupleJson.decodeFromString(TokenPair.serializer(), resp.body?.string().orEmpty())
                        tokenStore.write(StoredTokens(pair.accessToken, pair.refreshToken, pair.accessExpiresAt))
                        _userUpdates.tryEmit(pair.user)
                        pair.accessToken
                    }
                    resp.code in 400..499 && resp.code != 408 && resp.code != 429 -> {
                        tokenStore.clear()
                        _sessionExpired.tryEmit("登录已失效（HTTP ${resp.code}），请重新登录")
                        null
                    }
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 返回一个至少还有 [minValidityMillis] 有效期的 access token（MQTT 用它当密码）。 */
    fun validAccessTokenBlocking(minValidityMillis: Long = 60_000): String? {
        val t = tokenStore.read() ?: return null
        if (t.accessExpiresAt - clock.nowMillis() > minValidityMillis) return t.accessToken
        return refreshBlocking(t.accessToken)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
