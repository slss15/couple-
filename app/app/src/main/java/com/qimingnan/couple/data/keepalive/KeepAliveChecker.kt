package com.qimingnan.couple.data.keepalive

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class CheckStatus { OK, WARN, BAD, NA }

enum class CheckId { NOTIFICATIONS, BATTERY_OPTIMIZATION, BACKGROUND_RESTRICTED, DATA_SAVER, STANDBY_BUCKET, EXACT_ALARM }

data class CheckItem(val id: CheckId, val title: String, val status: CheckStatus, val detail: String)

/** Reads the system settings that decide whether the app survives in the background. */
class KeepAliveChecker(private val context: Context) {
    fun runAll(): List<CheckItem> = listOf(
        notifications(), batteryOptimization(), backgroundRestricted(), dataSaver(), standbyBucket(), exactAlarm(),
    )

    private fun notifications(): CheckItem {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return if (granted && enabled) {
            CheckItem(CheckId.NOTIFICATIONS, "通知权限", CheckStatus.OK, "已允许")
        } else {
            CheckItem(CheckId.NOTIFICATIONS, "通知权限", CheckStatus.BAD, "未允许：收不到消息提醒，常驻通知也无法显示")
        }
    }

    private fun batteryOptimization(): CheckItem {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return if (ignoring) {
            CheckItem(CheckId.BATTERY_OPTIMIZATION, "电池优化", CheckStatus.OK, "已忽略电池优化（Doze 时仍可联网）")
        } else {
            CheckItem(CheckId.BATTERY_OPTIMIZATION, "电池优化", CheckStatus.BAD, "受电池优化限制：息屏一段时间后连接会被冻结")
        }
    }

    private fun backgroundRestricted(): CheckItem {
        if (Build.VERSION.SDK_INT < 28) return CheckItem(CheckId.BACKGROUND_RESTRICTED, "后台限制", CheckStatus.NA, "此系统版本无此设置")
        val am = context.getSystemService(ActivityManager::class.java)
        return if (am?.isBackgroundRestricted == true) {
            CheckItem(CheckId.BACKGROUND_RESTRICTED, "后台限制", CheckStatus.BAD, "已被限制后台运行：退到后台后基本无法收消息")
        } else {
            CheckItem(CheckId.BACKGROUND_RESTRICTED, "后台限制", CheckStatus.OK, "未限制")
        }
    }

    private fun dataSaver(): CheckItem {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return when (cm?.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ->
                CheckItem(CheckId.DATA_SAVER, "流量节省", CheckStatus.BAD, "流量节省已开启且本应用不在白名单：后台无法联网")
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED ->
                CheckItem(CheckId.DATA_SAVER, "流量节省", CheckStatus.OK, "已开启，但本应用在白名单中")
            else -> CheckItem(CheckId.DATA_SAVER, "流量节省", CheckStatus.OK, "未开启")
        }
    }

    private fun standbyBucket(): CheckItem {
        if (Build.VERSION.SDK_INT < 28) return CheckItem(CheckId.STANDBY_BUCKET, "待机分组", CheckStatus.NA, "此系统版本无此设置")
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val bucket = runCatching { usm?.appStandbyBucket }.getOrNull()
            ?: return CheckItem(CheckId.STANDBY_BUCKET, "待机分组", CheckStatus.NA, "无法读取")
        val (status, name) = when {
            bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE -> CheckStatus.OK to "活跃"
            bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> CheckStatus.OK to "工作集"
            bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT -> CheckStatus.WARN to "常用"
            bucket <= UsageStatsManager.STANDBY_BUCKET_RARE -> CheckStatus.BAD to "极少使用"
            else -> CheckStatus.BAD to "受限"
        }
        return CheckItem(CheckId.STANDBY_BUCKET, "待机分组", status, "$name（$bucket）；忽略电池优化后不受分组限制")
    }

    private fun exactAlarm(): CheckItem {
        if (Build.VERSION.SDK_INT < 31) return CheckItem(CheckId.EXACT_ALARM, "精确闹钟", CheckStatus.NA, "此系统版本无需授权")
        val declared = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty()
                .any { it == Manifest.permission.SCHEDULE_EXACT_ALARM || it == "android.permission.USE_EXACT_ALARM" }
        }.getOrDefault(false)
        if (!declared) return CheckItem(CheckId.EXACT_ALARM, "精确闹钟", CheckStatus.NA, "当前版本未使用精确闹钟，不影响收消息")
        val am = context.getSystemService(AlarmManager::class.java)
        return if (am?.canScheduleExactAlarms() == true) {
            CheckItem(CheckId.EXACT_ALARM, "精确闹钟", CheckStatus.OK, "已允许")
        } else {
            CheckItem(CheckId.EXACT_ALARM, "精确闹钟", CheckStatus.WARN, "未允许：定时提醒可能延迟")
        }
    }
}
