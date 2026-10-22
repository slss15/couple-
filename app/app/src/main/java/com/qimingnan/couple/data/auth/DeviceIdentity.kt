package com.qimingnan.couple.data.auth

import android.content.Context
import android.os.Build
import com.qimingnan.couple.core.Ulid
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 本机 deviceId（首次启动生成的 ULID，随 refresh token 一起做设备绑定）。 */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    val deviceId: String by lazy {
        prefs.getString(KEY, null) ?: Ulid.next().also { prefs.edit().putString(KEY, it).apply() }
    }

    val deviceName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }
            .joinToString(" ").ifBlank { "Android" }

    private companion object {
        const val KEY = "device_id"
    }
}
