package com.qimingnan.couple.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
)

/** 同步接口：OkHttp 拦截器/Authenticator 在 IO 线程里直接读写。 */
interface TokenStore {
    fun read(): StoredTokens?
    fun write(tokens: StoredTokens)
    fun clear()
}

/** Token 存在 EncryptedSharedPreferences（AES256-GCM，主密钥在 Android Keystore）。 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenStore {
    /** null = 加密存储彻底不可用（Keystore 主密钥损坏）。这时退化成"未登录"，不能让 App 起不来。 */
    private val prefs: SharedPreferences? by lazy { open() }
    @Volatile private var cache: StoredTokens? = null
    @Volatile private var loaded = false

    private fun open(): SharedPreferences? = try {
        create()
    } catch (e: Exception) {
        // Keystore 被清空 / 备份恢复到新设备时密钥失效：丢弃旧文件重来一次。
        runCatching { context.deleteSharedPreferences(FILE) }
        // 第二次还失败说明主密钥本身坏了。以前这里没有兜底，App 一启动就崩；现在返回 null。
        runCatching { create() }.onFailure { Log.w(TAG, "encrypted token store unavailable; treating as logged out", it) }.getOrNull()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    override fun read(): StoredTokens? {
        if (loaded) return cache
        val p = prefs ?: run { loaded = true; cache = null; return null }
        val access = p.getString(K_ACCESS, null)
        val refresh = p.getString(K_REFRESH, null)
        val exp = p.getLong(K_EXP, 0L)
        cache = if (access != null && refresh != null) StoredTokens(access, refresh, exp) else null
        loaded = true
        return cache
    }

    @Synchronized
    override fun write(tokens: StoredTokens) {
        // commit()：轮换后的 refresh token 必须落盘，否则进程被杀会因复用旧 token 触发整族吊销。
        // 落盘失败时仍然更新内存缓存（本次会话还能用），但要留痕：下次启动会拿旧 token，服务端会吊销整族。
        val ok = prefs?.edit()
            ?.putString(K_ACCESS, tokens.accessToken)
            ?.putString(K_REFRESH, tokens.refreshToken)
            ?.putLong(K_EXP, tokens.accessExpiresAt)
            ?.commit() ?: false
        if (!ok) Log.w(TAG, "failed to persist rotated tokens; next launch may be logged out")
        cache = tokens
        loaded = true
    }

    @Synchronized
    override fun clear() {
        prefs?.edit()?.clear()?.commit()
        cache = null
        loaded = true
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE = "auth_tokens"
        const val K_ACCESS = "access"
        const val K_REFRESH = "refresh"
        const val K_EXP = "access_exp"
    }
}
