package com.qimingnan.couple.net

import com.qimingnan.couple.data.auth.StoredTokens
import com.qimingnan.couple.data.auth.TokenRefresher
import com.qimingnan.couple.data.auth.TokenStore
import com.qimingnan.couple.data.net.AuthHeaderInterceptor
import com.qimingnan.couple.data.net.BaseUrlInterceptor
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.TokenAuthenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemTokenStore(var t: StoredTokens?) : TokenStore {
    override fun read() = t
    override fun write(tokens: StoredTokens) { t = tokens }
    override fun clear() { t = null }
}

class TokenRefreshTest {
    private lateinit var server: MockWebServer
    private val refreshCalls = AtomicInteger()

    private fun pair(n: Int) = """{"accessToken":"access-$n","refreshToken":"refresh-$n","accessExpiresAt":9999999999999,
        "user":{"id":"U1","username":"me","displayName":"Me","createdAt":1}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> {
                    val body = request.body.readUtf8()
                    val n = refreshCalls.incrementAndGet()
                    Thread.sleep(50)
                    if (body.contains("\"refresh-bad\"")) MockResponse().setResponseCode(401).setBody("""{"code":"REFRESH_REUSED","message":"x"}""")
                    else MockResponse().setBody(pair(n))
                }
                "/api/v1/couple" -> {
                    if (request.getHeader("Authorization")?.startsWith("Bearer access-") == true) {
                        MockResponse().setBody("""{"id":"C1","members":[],"pairedAt":1}""")
                    } else MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHORIZED","message":"expired"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun client(store: TokenStore): Pair<OkHttpClient, TokenRefresher> {
        val base = server.url("/").toString().trimEnd('/')
        val plain = OkHttpClient()
        val refresher = TokenRefresher(store, plain, { "$base/api/v1" }, { "DEV1" })
        val c = plain.newBuilder()
            .addInterceptor(BaseUrlInterceptor { base })
            .addInterceptor(AuthHeaderInterceptor(store))
            .authenticator(TokenAuthenticator(refresher))
            .build()
        return c to refresher
    }

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url(CoupleApi.PLACEHOLDER_BASE + "couple").build()).execute()

    @Test fun expiredAccessIsRefreshedAndRotated() {
        val store = MemTokenStore(StoredTokens("stale", "refresh-0", 0))
        val (c, _) = client(store)
        get(c).use { assertEquals(200, it.code) }
        assertEquals("refresh-1", store.t?.refreshToken) // 新 refresh token 已持久化
        assertEquals(1, refreshCalls.get())
    }

    @Test fun concurrent401sRefreshOnlyOnce() {
        val store = MemTokenStore(StoredTokens("stale", "refresh-0", 0))
        val (c, _) = client(store)
        val pool = Executors.newFixedThreadPool(5)
        val latch = CountDownLatch(5)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(5) { pool.execute { get(c).use { codes += it.code }; latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(List(5) { 200 }, codes)
        assertEquals(1, refreshCalls.get())
    }

    @Test fun rejectedRefreshClearsSession() {
        val store = MemTokenStore(StoredTokens("stale", "refresh-bad", 0))
        val (c, _) = client(store)
        get(c).use { assertEquals(401, it.code) }
        assertNull(store.t)
    }

    @Test fun baseUrlRewrittenToApiRoot() {
        val store = MemTokenStore(StoredTokens("access-x", "refresh-0", Long.MAX_VALUE))
        val (c, _) = client(store)
        get(c).use { assertEquals(200, it.code) }
        assertEquals("/api/v1/couple", server.takeRequest().path)
    }
}
