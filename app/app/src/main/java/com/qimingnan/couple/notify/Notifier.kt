package com.qimingnan.couple.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.qimingnan.couple.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知子系统（P1 一次建好，聊天/插件/通话复用）：
 * - 渠道：feed（动态）、chat（P1a 聊天用，现在建好）、calls（P4 用，现在建好）；
 * - 免打扰时段（quiet window，本机时间 HH:mm-HH:mm，可跨午夜；空 = 关闭）——由「我的」页设置写入
 *   [PREFS]，默认关闭；
 * - 可见性：由 UI 层（FeedScreen 等 DisposableEffect）写 [Notifier.screenVisible]，前台时不打扰。
 */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    /** 前台可见性登记：key = screen（"feed"/"chat"/…），value = 是否当前可见。 */
    private val visible = mutableMapOf<String, Boolean>()

    fun setScreenVisible(screen: String, visibleNow: Boolean) {
        visible[screen] = visibleNow
    }

    fun isScreenVisible(screen: String): Boolean = visible[screen] == true

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_FEED, context.getString(R.string.notify_channel_feed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notify_channel_feed_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CHAT, context.getString(R.string.notify_channel_chat), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notify_channel_chat_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, context.getString(R.string.notify_channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notify_channel_calls_desc)
            },
        )
    }

    /** 免打扰窗口内返回 true（窗口为空 = 永不静音）。 */
    fun inQuietWindow(nowMillis: Long = System.currentTimeMillis(), window: String = quietWindow()): Boolean {
        val m = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$").find(window.trim()) ?: return false
        val (h1, mi1, h2, mi2) = m.destructured
        val start = h1.toInt() * 60 + mi1.toInt()
        val end = h2.toInt() * 60 + mi2.toInt()
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start <= end) cur in start until end else (cur >= start || cur < end)
    }

    private fun quietWindow(): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUIET, "") ?: ""

    /** 发通知；权限未授予/渠道关闭/免打扰时静默跳过。返回是否真的发了。 */
    fun notify(channel: String, id: Int, title: String, text: String, quietOverride: Boolean = false): Boolean {
        if (!canNotify()) return false
        if (!quietOverride && inQuietWindow()) return false
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 占位；换图标随 App 图标批次更新
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, n)
            true
        }.getOrDefault(false)
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val CH_FEED = "feed"
        const val CH_CHAT = "chat"
        const val CH_CALLS = "calls"
        const val PREFS = "notify"
        const val KEY_QUIET = "quiet_window"
        const val ID_FEED = 2001
        const val ID_CHAT = 2002
    }
}
