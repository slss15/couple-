package com.qimingnan.couple.data.keepalive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** On/off switch for [KeepAliveService] (off by default: it shows a permanent notification). */
@Singleton
class KeepAliveController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("keepalive", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
        if (on) start() else context.stopService(Intent(context, KeepAliveService::class.java))
    }

    /** Called on app start; harmless if the system refuses a background start (the next foreground start retries). */
    fun startIfEnabled() {
        if (_enabled.value) start()
    }

    private fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.w(KeepAliveService.TAG, "could not start keep-alive service", e)
        }
    }

    private companion object {
        const val KEY = "fgs_enabled"
    }
}
