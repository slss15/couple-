package com.qimingnan.couple.data.auth

import android.content.Context
import android.content.SharedPreferences
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
    private val prefs: SharedPreferences by lazy { open() }
    @Volatile private var cache: StoredTokens? = null
    @Volatile private var loaded = false

    private fun open(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        // Keystore 被清空 / 备份恢复到新设备时密钥失效：丢弃旧文件，重新登录即可。
        context.deleteSharedPreferences(FILE)
        create()
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
        val access = prefs.getString(K_ACCESS, null)
        val refresh = prefs.getString(K_REFRESH, null)
        val exp = prefs.getLong(K_EXP, 0L)
        cache = if (access != null && refresh != null) StoredTokens(access, refresh, exp) else null
        loaded = true
        return cache
    }

    @Synchronized
    override fun write(tokens: StoredTokens) {
        // commit()：轮换后的 refresh token 必须落盘，否则进程被杀会因复用旧 token 触发整族吊销。
        prefs.edit()
            .putString(K_ACCESS, tokens.accessToken)
            .putString(K_REFRESH, tokens.refreshToken)
            .putLong(K_EXP, tokens.accessExpiresAt)
            .commit()
        cache = tokens
        loaded = true
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        cache = null
        loaded = true
    }

    private companion object {
        const val FILE = "auth_tokens"
        const val K_ACCESS = "access"
        const val K_REFRESH = "refresh"
        const val K_EXP = "access_exp"
    }
}
