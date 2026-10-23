package com.qimingnan.couple.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.auth.DeviceIdentity
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.net.humanMessage
import com.qimingnan.couple.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Downloading(val done: Long, val total: Long) : UpdatePhase
    data class Ready(val file: String) : UpdatePhase
    data object Installing : UpdatePhase
    data object WaitingForUser : UpdatePhase
    data object NeedsUnknownSources : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

data class UpdateState(
    val currentVersionName: String = "",
    val currentVersionCode: Int = 0,
    val checking: Boolean = false,
    val lastCheckAt: Long = 0,
    val lastCheckMessage: String? = null,
    val available: UpdateInfo? = null,
    val force: Boolean = false,
    /** Dismissed for this process (non-forced updates only). */
    val dismissedVersionCode: Int? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
) {
    val showDialog: Boolean get() = available != null && (force || dismissedVersionCode != available.versionCode)
}

/**
 * App self-update: checks GET /app/update (on start, at most every 6 h, or manually), downloads the APK through
 * [UpdateDownloadWorker] and installs it with [ApkInstaller]. The last response is cached so a forced update keeps
 * blocking the UI across restarts even when the 6 h debounce skips the network check.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: CoupleApi,
    private val device: DeviceIdentity,
    private val bootstrapStore: BootstrapStore,
    private val installer: ApkInstaller,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Set when the user asked to update: install as soon as the download is verified. */
    @Volatile private var installWhenReady = false

    /** APK waiting for the user to allow "install unknown apps". */
    @Volatile private var pendingReadyFile: String? = null

    init {
        scope.launch {
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UpdateDownloadWorker.UNIQUE_NAME).collect { infos ->
                onWorkInfo(infos.lastOrNull())
            }
        }
        scope.launch {
            InstallEvents.events.collect { o ->
                _state.update { it.copy(phase = phaseOf(o, it.phase)) }
            }
        }
    }

    private fun initialState(): UpdateState {
        val (name, code) = currentVersion()
        val cached = prefs.getString(KEY_RESPONSE, null)?.let { runCatching { UpdateLogic.parseResponse(it) }.getOrNull() }
        val latest = cached?.latest?.takeIf { UpdateLogic.isNewer(it, code) }
        return UpdateState(
            currentVersionName = name,
            currentVersionCode = code,
            lastCheckAt = prefs.getLong(KEY_LAST_CHECK, 0),
            available = latest,
            force = latest != null && cached?.force == true,
            shizuku = installer.shizukuState(),
        )
    }

    private fun currentVersion(): Pair<String, Int> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        return (info.versionName ?: "?") to code
    }

    fun refreshShizuku() = _state.update { it.copy(shizuku = installer.shizukuState()) }

    fun requestShizukuPermission() {
        installer.requestShizukuPermission()
        refreshShizuku()
    }

    /** Automatic check on app start / login; skipped if the last check was < 6 h ago. */
    fun checkIfDue() {
        if (UpdateLogic.shouldAutoCheck(prefs.getLong(KEY_LAST_CHECK, 0), clock.nowMillis())) {
            scope.launch { check(manual = false) }
        }
    }

    /** Returns a short Chinese message describing the result (used by 「检查更新」). */
    suspend fun check(manual: Boolean): String = checkMutex.withLock {
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl ?: return "尚未配置服务器"
        _state.update { it.copy(checking = true, shizuku = installer.shizukuState()) }
        val current = _state.value.currentVersionCode
        val result = apiCall { api.checkUpdate(current, device.deviceId) }
        val now = clock.nowMillis()
        val msg = when (result) {
            is ApiResult.Ok -> {
                val r = result.value
                prefs.edit()
                    .putLong(KEY_LAST_CHECK, now)
                    .putString(KEY_RESPONSE, CoupleJson.encodeToString(UpdateResponse.serializer(), r))
                    .apply()
                val latest = r.latest?.takeIf { UpdateLogic.isNewer(it, current) }
                val valid = latest?.takeIf { UpdateLogic.resolveDownloadUrl(base, it.downloadUrl) != null }
                _state.update {
                    it.copy(
                        checking = false, lastCheckAt = now, available = valid, force = valid != null && r.force,
                        dismissedVersionCode = if (manual) null else it.dismissedVersionCode,
                    )
                }
                when {
                    latest == null -> "已是最新版本"
                    valid == null -> "服务器返回的下载地址无效"
                    else -> "发现新版本 ${latest.versionName}"
                }
            }
            is ApiResult.Err -> {
                _state.update { it.copy(checking = false) }
                "检查更新失败：${result.error.humanMessage()}"
            }
        }
        _state.update { it.copy(lastCheckMessage = msg) }
        msg
    }

    /** Show the prompt again (「查看更新」 in 我的). */
    fun reopen() = _state.update { it.copy(dismissedVersionCode = null) }

    fun dismiss() = _state.update { s ->
        if (s.force || s.available == null) s else s.copy(dismissedVersionCode = s.available.versionCode)
    }

    /** 「立即更新」: install right away if already downloaded, else start/continue the background download. */
    fun startUpdate() {
        val s = _state.value
        val info = s.available ?: return
        installWhenReady = true
        val phase = s.phase
        if (phase is UpdatePhase.Ready && File(phase.file).isFile) {
            install()
            return
        }
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl
        val url = base?.let { UpdateLogic.resolveDownloadUrl(it, info.downloadUrl) }
        if (url == null) {
            _state.update { it.copy(phase = UpdatePhase.Failed("下载地址无效")) }
            return
        }
        val req = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_URL to url.toString(),
                    UpdateDownloadWorker.KEY_VERSION_CODE to info.versionCode,
                    UpdateDownloadWorker.KEY_VERSION_NAME to info.versionName,
                    UpdateDownloadWorker.KEY_SIZE to info.size,
                    UpdateDownloadWorker.KEY_SHA256 to info.sha256,
                    UpdateDownloadWorker.KEY_CERT to info.signingCertSha256,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("v${info.versionCode}")
            .build()
        val running = phase is UpdatePhase.Downloading
        WorkManager.getInstance(context).enqueueUniqueWork(
            UpdateDownloadWorker.UNIQUE_NAME,
            if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            req,
        )
        if (!running) _state.update { it.copy(phase = UpdatePhase.Downloading(0, info.size)) }
    }

    fun install() {
        val phase = _state.value.phase
        val file = (phase as? UpdatePhase.Ready)?.file?.let(::File)
        if (file == null || !file.isFile) {
            _state.update { it.copy(phase = UpdatePhase.Failed("安装包不存在，请重新下载")) }
            return
        }
        _state.update { it.copy(phase = UpdatePhase.Installing) }
        scope.launch {
            val o = installer.install(file)
            _state.update { it.copy(phase = phaseOf(o, UpdatePhase.Ready(file.absolutePath))) }
        }
    }

    /** Returning from the "install unknown apps" settings screen. */
    fun onResume() {
        refreshShizuku()
        val s = _state.value
        if (s.phase is UpdatePhase.NeedsUnknownSources && installer.canRequestPackageInstalls()) {
            pendingReadyFile?.let { f -> _state.update { it.copy(phase = UpdatePhase.Ready(f)) }; install() }
        }
    }

    private fun phaseOf(o: InstallOutcome, readyFallback: UpdatePhase): UpdatePhase = when (o) {
        InstallOutcome.Started -> UpdatePhase.Installing
        InstallOutcome.WaitingForUser -> UpdatePhase.WaitingForUser
        InstallOutcome.NeedsUnknownSourcesPermission -> UpdatePhase.NeedsUnknownSources.also {
            pendingReadyFile = (readyFallback as? UpdatePhase.Ready)?.file ?: pendingReadyFile
        }
        is InstallOutcome.Failed -> UpdatePhase.Failed(o.message)
    }

    private suspend fun onWorkInfo(info: WorkInfo?) {
        info ?: return
        val expectedVc = _state.value.available?.versionCode
        val phase: UpdatePhase? = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val done = info.progress.getLong(UpdateDownloadWorker.KEY_DONE, 0)
                val total = info.progress.getLong(UpdateDownloadWorker.KEY_TOTAL, _state.value.available?.size ?: 0)
                UpdatePhase.Downloading(done, total)
            }
            WorkInfo.State.SUCCEEDED -> {
                val path = info.outputData.getString(UpdateDownloadWorker.KEY_FILE)
                val vc = info.outputData.getInt(UpdateDownloadWorker.KEY_VERSION_CODE, -1)
                val exists = path != null && withContext(Dispatchers.IO) { File(path).isFile }
                if (exists && vc == expectedVc) UpdatePhase.Ready(path!!) else null
            }
            // Only surface failures of a download we are watching (not a stale one from a previous launch).
            WorkInfo.State.FAILED -> info.outputData.getString(UpdateDownloadWorker.KEY_ERROR)
                ?.takeIf { _state.value.phase is UpdatePhase.Downloading }?.let { UpdatePhase.Failed(it) }
            WorkInfo.State.CANCELLED -> UpdatePhase.Idle
        }
        if (phase == null) return
        val prev = _state.value.phase
        // Do not clobber install progress with the (already finished) download state.
        if (phase is UpdatePhase.Ready && prev !is UpdatePhase.Downloading && prev !is UpdatePhase.Idle && prev !is UpdatePhase.Failed) return
        _state.update { it.copy(phase = phase) }
        if (phase is UpdatePhase.Ready && installWhenReady) install()
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_RESPONSE = "last_response"

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
