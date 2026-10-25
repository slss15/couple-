package com.qimingnan.couple.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.qimingnan.couple.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ShizukuState { NOT_RUNNING, UNSUPPORTED, NOT_AUTHORIZED, AUTHORIZED }

sealed interface InstallOutcome {
    /** Install started/finished; the process is usually killed by the package replacement right after. */
    data object Started : InstallOutcome
    /** The system confirmation dialog was shown (or is about to be). */
    data object WaitingForUser : InstallOutcome
    /** "Install unknown apps" is off for us: the settings screen was opened. */
    data object NeedsUnknownSourcesPermission : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/** Results posted by [InstallResultReceiver] (system PackageInstaller session callbacks). */
object InstallEvents {
    private val _events = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallOutcome> = _events
    internal fun emit(o: InstallOutcome) { _events.tryEmit(o) }
}

/**
 * Installs a verified APK.
 * 1. Shizuku running + permission granted: silent `pm install -r -S <size>` with the APK streamed over stdin (the
 *    shell user cannot read our private files), executed through Shizuku's remote process, followed by
 *    `am start` so the app comes back after it is replaced. Uses the public Shizuku AIDL (IShizukuService.newProcess)
 *    because Shizuku.newProcess is private in API 13.
 * 2. Otherwise: system PackageInstaller session; Android shows its confirmation dialog (on API 31+ we ask for no
 *    user action, which the system grants when we are already the installer of record).
 */
@Singleton
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    fun shizukuState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.AUTHORIZED
            else -> ShizukuState.NOT_AUTHORIZED
        }
    } catch (e: Throwable) {
        ShizukuState.NOT_RUNNING
    }

    /** Shows Shizuku's permission prompt (result arrives through Shizuku's listener; callers re-read [shizukuState]). */
    fun requestShizukuPermission() {
        if (shizukuState() == ShizukuState.NOT_AUTHORIZED) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    suspend fun install(apk: File): InstallOutcome = withContext(Dispatchers.IO) {
        if (shizukuState() == ShizukuState.AUTHORIZED) {
            val r = runCatching { installViaShizuku(apk) }
            r.getOrNull()?.let { return@withContext it }
            Log.w(TAG, "Shizuku install failed, falling back to system installer", r.exceptionOrNull())
        }
        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return@withContext InstallOutcome.NeedsUnknownSourcesPermission
        }
        installViaSession(apk)
    }

    private fun installViaShizuku(apk: File): InstallOutcome {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: error("Shizuku binder unavailable")
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        val cmd = "pm install -r -S ${apk.length()} && am start -n $component"
        val proc = service.newProcess(arrayOf("sh", "-c", cmd), null, null) ?: error("Shizuku newProcess returned null")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(proc.outputStream).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            // If the install succeeds we are usually killed here, before reading the result.
            val output = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
            val err = ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
            val code = proc.waitFor()
            if (code == 0 || output.contains("Success")) return InstallOutcome.Started
            return InstallOutcome.Failed("静默安装失败：${(err.ifBlank { output }).trim().take(200)}")
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun installViaSession(apk: File): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                // Become the update owner: other installers (ROM app stores, file managers) then need explicit user
                // confirmation to update us (needs ENFORCE_UPDATE_OWNERSHIP; docs/ota-design.md §6).
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
            return InstallOutcome.WaitingForUser
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            return InstallOutcome.Failed("安装失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
        const val REQUEST_CODE = 4242
    }
}

/** Receives PackageInstaller session status; launches the confirmation UI when the system needs the user. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { InstallEvents.emit(InstallOutcome.Failed("无法打开安装确认界面：${it.message}")) }
                }
                InstallEvents.emit(InstallOutcome.WaitingForUser)
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.emit(InstallOutcome.Started)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.emit(InstallOutcome.Failed("已取消安装"))
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallEvents.emit(InstallOutcome.Failed("安装失败（$status）${msg?.let { "：$it" } ?: ""}"))
            }
        }
    }
}
