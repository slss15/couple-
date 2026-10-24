package com.qimingnan.couple.data.update

import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Intent
import com.qimingnan.couple.MainActivity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qimingnan.couple.di.ApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads + verifies an update in WorkManager (survives backgrounding / process death; resumes with Range),
 * showing progress in a notification. Verification: size, sha256, package name, versionCode, and the signing
 * certificate rule in [UpdateLogic.checkCertificates]. Output [KEY_FILE] = path of the verified APK.
 */
@HiltWorker
class UpdateDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    @ApiClient apiClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    private val downloader = ApkDownloader(
        apiClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build(),
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)?.toHttpUrlOrNull() ?: return@withContext fail("下载地址无效")
        val versionCode = inputData.getInt(KEY_VERSION_CODE, -1)
        val versionName = inputData.getString(KEY_VERSION_NAME) ?: versionCode.toString()
        val size = inputData.getLong(KEY_SIZE, -1)
        val sha = inputData.getString(KEY_SHA256).orEmpty()
        val cert = inputData.getString(KEY_CERT).orEmpty()
        if (versionCode <= 0 || size <= 0) return@withContext fail("更新信息不完整")

        runCatching { setForeground(foregroundInfo(versionName, 0, size)) } // may be refused when started from background
        val dest = File(File(appContext.filesDir, "updates"), "couple-$versionCode.apk")
        cleanupOthers(dest)
        val file = try {
            var lastPct = -1
            downloader.download(
                url, dest, size, sha,
                onProgress = { done, total ->
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        notify(foregroundInfo(versionName, done, total))
                    }
                },
                isStopped = { isStopped },
            )
        } catch (e: ApkDownloader.DownloadException) {
            return@withContext if (e.retryable && runAttemptCount < MAX_ATTEMPTS && !isStopped) Result.retry() else fail(e.message ?: "下载失败")
        }

        // Signing-certificate + identity checks on the archive itself.
        val archive = ApkCertificates.archive(appContext, file)
        val problem = when {
            archive == null -> "无法解析安装包"
            archive.packageName != appContext.packageName -> "安装包的包名不符（${archive.packageName}）"
            archive.versionCode != versionCode.toLong() -> "安装包版本号不符（${archive.versionCode} ≠ $versionCode）"
            else -> when (val c = UpdateLogic.checkCertificates(ApkCertificates.installed(appContext), archive.certs, cert)) {
                UpdateLogic.CertCheck.Ok -> null
                is UpdateLogic.CertCheck.Rejected -> c.reason
            }
        }
        if (problem != null) {
            file.delete()
            return@withContext fail(problem)
        }
        notifyDone(versionName)
        Result.success(workDataOf(KEY_FILE to file.absolutePath, KEY_VERSION_CODE to versionCode))
    }

    private fun cleanupOthers(keep: File) {
        keep.parentFile?.listFiles()?.forEach { f ->
            if (!f.name.startsWith(keep.name)) f.delete()
        }
    }

    private fun fail(msg: String): Result {
        notificationManager().cancel(NOTIFICATION_ID)
        return Result.failure(workDataOf(KEY_ERROR to msg))
    }

    private fun notificationManager() = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW).apply { description = "下载新版本的进度" },
            )
        }
    }

    private fun foregroundInfo(versionName: String, done: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载新版本 $versionName")
            .setContentText("$pct%")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun notify(info: ForegroundInfo) {
        runCatching { notificationManager().notify(NOTIFICATION_ID, info.notification) }
    }

    private fun notifyDone(versionName: String) {
        ensureChannel()
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("新版本 $versionName 已下载")
            .setContentText("打开 App 完成安装")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext, 0,
                    Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { notificationManager().notify(NOTIFICATION_ID + 1, n) }
    }

    companion object {
        const val UNIQUE_NAME = "app-update-download"
        const val KEY_URL = "url"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_VERSION_NAME = "versionName"
        const val KEY_SIZE = "size"
        const val KEY_SHA256 = "sha256"
        const val KEY_CERT = "cert"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "app_update"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_ATTEMPTS = 5
    }
}
