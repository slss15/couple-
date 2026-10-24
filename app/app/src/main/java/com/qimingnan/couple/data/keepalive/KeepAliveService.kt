package com.qimingnan.couple.data.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.qimingnan.couple.MainActivity
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.mqtt.MqttState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Optional foreground service (我的 → 后台保活 → 常驻连接) that keeps the process — and with it the MQTT connection —
 * alive. Type specialUse (C5): none of the predefined FGS types fits "keep a private messaging socket open"; the
 * subtype is declared in the manifest (PROPERTY_SPECIAL_USE_FGS_SUBTYPE). Low-importance channel: no sound, no
 * heads-up, collapsed in the shade.
 */
@AndroidEntryPoint
class KeepAliveService : Service() {
    @Inject lateinit var mqtt: MqttManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(mqtt.state.value), type)
        } catch (e: Exception) {
            // Android 12+: starting a foreground service from the background can be refused.
            Log.w(TAG, "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        watcher?.cancel()
        watcher = scope.launch {
            mqtt.state.map(::statusText).distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(mqtt.state.value))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: MqttState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("正在保持连接")
            .setContentText(statusText(state))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    private fun statusText(s: MqttState): String = when (s) {
        MqttState.Idle -> "未连接"
        MqttState.Connecting -> "连接中…"
        is MqttState.Connected -> "实时连接正常"
        is MqttState.Failed -> "连接中断，正在重试"
    }

    companion object {
        const val TAG = "CoupleKeepAlive"
        const val CHANNEL_ID = "keepalive"
        const val NOTIFICATION_ID = 0x4b41

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台常驻连接", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持与服务器的实时连接，不会发出声音"
                    setShowBadge(false)
                },
            )
        }
    }
}
