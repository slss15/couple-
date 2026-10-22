package com.qimingnan.couple.data.net

import com.qimingnan.couple.data.auth.TokenRefresher
import com.qimingnan.couple.data.auth.TokenStore
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.bootstrap.apiRoot
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/** 把占位 baseUrl 改写成当前服务器配置的 {baseUrl}/api/v1/...。 */
class BaseUrlInterceptor(private val baseUrlProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val placeholder = CoupleApi.PLACEHOLDER_BASE.toHttpUrl()
        if (original.url.host != placeholder.host) return chain.proceed(original)
        val base = baseUrlProvider() ?: throw IOException("尚未配置服务器")
        val relative = original.url.encodedPath.removePrefix(placeholder.encodedPath)
        val query = original.url.encodedQuery?.let { "?$it" } ?: ""
        val newUrl = (apiRoot(base) + "/" + relative + query).toHttpUrl()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }

    companion object {
        fun fromStore(store: BootstrapStore) = BaseUrlInterceptor { store.currentConfigBlocking()?.baseUrl }
    }
}

/** 给非 /auth/ 请求加 Bearer token。 */
class AuthHeaderInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (isAuthEndpoint(req) || req.header("Authorization") != null) return chain.proceed(req)
        val token = tokenStore.read()?.accessToken ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * 401 时用 refresh token 换新 token（服务端每次轮换），并重试原请求一次。
 * 多个请求同时 401 时由 [TokenRefresher] 串行化，只刷新一次。
 */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val req = response.request
        if (isAuthEndpoint(req)) return null
        if (responseCount(response) >= 2) return null
        val stale = req.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val fresh = refresher.refreshBlocking(stale) ?: return null
        return req.newBuilder().header("Authorization", "Bearer $fresh").build()
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var count = 1
        while (r?.priorResponse != null) {
            count++
            r = r.priorResponse
        }
        return count
    }
}

internal fun isAuthEndpoint(req: Request): Boolean {
    val path = req.url.encodedPath
    return path.contains("/auth/login") || path.contains("/auth/register") ||
        path.contains("/auth/refresh") || path.contains("/auth/logout")
}
