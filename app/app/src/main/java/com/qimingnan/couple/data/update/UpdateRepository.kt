package com.qimingnan.couple.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.util.Log
import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.auth.DeviceIdentity
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.net.humanMessage
import com.qimingnan.couple.di.ApplicationScope
import com.qimingnan.couple.di.PlainClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet6Address
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
    /** Why the server offers/forces this update (MIN_SUPPORTED | PEER_PROTOCOL | PEER_NEWER), or null. */
    val reason: String? = null,
    val peer: PeerInfo? = null,
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
    @PlainClient private val plainClient: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Set when the user asked to update: install as soon as the download is verified. */
    @Volatile private var installWhenReady = false

    /** APK waiting for the user to allow "install unknown apps". */
    @Volatile private var pendingReadyFile: String? = null

    @Volatile private var healthWatchStarted = false

    init {
        installCrashMarker()
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
        // Anti-downgrade high-water mark: never lower than anything installed on this device.
        val seen = UpdateLogic.nextHighestSeen(prefs.getInt(KEY_HIGHEST_SEEN, 0), code, null)
        prefs.edit().putInt(KEY_HIGHEST_SEEN, seen).apply()
        val cached = prefs.getString(KEY_RESPONSE, null)?.let { runCatching { UpdateLogic.parseResponse(it) }.getOrNull() }
        // The cache lives in app-private storage but is re-verified anyway (same rules as a fresh response).
        val latest = cached?.latest?.takeIf { vet(it, code) is Vetted.Ok }
        return UpdateState(
            currentVersionName = name,
            currentVersionCode = code,
            lastCheckAt = prefs.getLong(KEY_LAST_CHECK, 0),
            available = latest,
            force = latest != null && cached?.force == true,
            reason = cached?.reason?.takeIf { latest != null },
            peer = cached?.peer,
            shizuku = installer.shizukuState(),
        )
    }

    private sealed interface Vetted {
        data class Ok(val verifiedVersionCode: Int?, val manifest: SignedManifest? = null) : Vetted
        data class Rejected(val message: String) : Vetted
    }

    /**
     * OTA v2 checks on an offer (docs/ota-design.md §2, §3): signed manifest against the build-time pinned key, then
     * anti-downgrade against the highest versionCode installed or seen in a verified manifest.
     */
    private fun vet(info: UpdateInfo, installed: Int): Vetted {
        if (!UpdateLogic.isNewer(info, installed)) return Vetted.Rejected("已是最新版本")
        val pkg = ManifestVerifier.releasePackageOf(context.packageName)
        var signed: SignedManifest? = null
        val verified = when (val r = ManifestVerifier.verify(info, BuildConfig.OTA_PUBLIC_KEY, pkg)) {
            is ManifestVerifier.Result.Verified -> r.manifest.also { signed = it }.versionCode
            ManifestVerifier.Result.Skipped -> {
                Log.w(TAG, "OTA manifest verification skipped: no OTA_PUBLIC_KEY pinned in this build")
                null
            }
            is ManifestVerifier.Result.Rejected -> {
                Log.w(TAG, "Rejected update ${info.versionCode}: ${r.reason}")
                return Vetted.Rejected(r.reason + "，已忽略")
            }
        }
        val highest = prefs.getInt(KEY_HIGHEST_SEEN, 0)
        if (!UpdateLogic.acceptOffer(info.versionCode, installed, highest)) {
            Log.w(TAG, "Rejected update ${info.versionCode}: below highest seen $highest (anti-downgrade)")
            return Vetted.Rejected("服务器提供的版本低于已知的最新版本，已拒绝")
        }
        return Vetted.Ok(verified, signed)
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
        val current0 = _state.value.currentVersionCode
        val base0 = bootstrapStore.currentConfigBlocking()?.baseUrl
        val isDemo = base0?.let { com.qimingnan.couple.data.demo.DemoBackend.isDemo(it) } ?: false
        if (!isDemo) {
            // 服务器路径（快、附带设备上报）与 GitHub 直连路径并行起跑：
            // 服务器给出确定结果（Ok，含「已是最新」）就用它；服务器失败（未登录/未配对/网络）时用 GitHub 结果。
            val serverDeferred = scope.async { runCatching { checkServer(current0, manual) } }
            val githubDeferred = scope.async {
                runCatching { checkGithub(current0, manual) }.getOrElse { GithubResult.Failed("GitHub 检查异常") }
            }
            val server = serverDeferred.await()
            if (server is ServerOutcome.Definitive) {
                githubDeferred.cancel()
                return server.message
            }
            val github = githubDeferred.await()
            return when (github) {
                is GithubResult.Available -> {
                    if (!manual) prefetchIfCheap()
                    "发现新版本 ${github.info.versionName}"
                }
                GithubResult.UpToDate -> "已是最新版本"
                is GithubResult.Failed ->
                    (server as? ServerOutcome.Fallback)?.message ?: github.message
            }
        }
        checkServer(current0, manual).message
    }

    private sealed interface ServerOutcome {
        val message: String

        /** ApiResult.Ok：结果确定（含「已是最新版本」），直接采用。 */
        data class Definitive(override val message: String) : ServerOutcome

        /** ApiResult.Err：服务器路径失败，让位给 GitHub 直连结果。 */
        data class Fallback(override val message: String) : ServerOutcome
    }

    /** 服务器路径：GET /app/update（附带设备上报与伴侣协议信息）。 */
    private suspend fun checkServer(current0: Int, manual: Boolean): ServerOutcome {
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl
            ?: return ServerOutcome.Fallback("尚未配置服务器")
        _state.update { it.copy(checking = true, shizuku = installer.shizukuState()) }
        val current = _state.value.currentVersionCode
        val result = apiCall {
            api.checkUpdate(current, device.deviceId, BuildConfig.PEER_PROTOCOL_VERSION, BuildConfig.MIN_PEER_PROTOCOL, Build.VERSION.SDK_INT)
        }
        val now = clock.nowMillis()
        val msg = when (result) {
            is ApiResult.Ok -> {
                val r = result.value
                prefs.edit()
                    .putLong(KEY_LAST_CHECK, now)
                    .putString(KEY_RESPONSE, CoupleJson.encodeToString(UpdateResponse.serializer(), r))
                    .apply()
                val latest = r.latest?.takeIf { UpdateLogic.isNewer(it, current) }
                val vetted = latest?.let { vet(it, current) }
                if (vetted is Vetted.Ok && vetted.verifiedVersionCode != null) {
                    val next = UpdateLogic.nextHighestSeen(prefs.getInt(KEY_HIGHEST_SEEN, 0), current, vetted.verifiedVersionCode)
                    prefs.edit().putInt(KEY_HIGHEST_SEEN, next).apply()
                }
                val trusted = latest?.takeIf { vetted is Vetted.Ok }
                val signed = (vetted as? Vetted.Ok)?.manifest
                val valid = trusted?.takeIf { UpdateLogic.downloadMirrors(base, it, signed).isNotEmpty() }
                _state.update {
                    it.copy(
                        checking = false, lastCheckAt = now, available = valid, force = valid != null && r.force,
                        reason = r.reason.takeIf { valid != null }, peer = r.peer,
                        dismissedVersionCode = if (manual) null else it.dismissedVersionCode,
                    )
                }
                when {
                    latest == null -> "已是最新版本"
                    vetted is Vetted.Rejected -> vetted.message
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
        // OTA §9-4：自动检查（非手动）发现可选更新时，Wi-Fi + 电量充足就顺手预下载，用户点更新时已就绪。
        if (!manual) prefetchIfCheap()
        return when (result) {
            is ApiResult.Ok -> ServerOutcome.Definitive(msg)
            is ApiResult.Err -> ServerOutcome.Fallback(msg)
        }
    }

    private sealed interface GithubResult {
        data class Available(val info: UpdateInfo, val force: Boolean, val reason: String?) : GithubResult
        data object UpToDate : GithubResult
        data class Failed(val message: String, val signatureBad: Boolean = false) : GithubResult
    }

    private fun hasIpv6(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return false
        props.linkAddresses.any { addr ->
            val ip = addr.address
            ip is Inet6Address && !ip.isLinkLocalAddress && !ip.isLoopbackAddress
        }
    }.getOrDefault(false)

    private fun githubClient(): OkHttpClient = plainClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 候选并行竞速：同时发所有候选，最先返回 200 且带体的获胜，其余取消。
     * 旧实现是串行 for 循环，一个被墙的候选烧一次连接超时，五个候选最坏一分多钟。
     * 全部候选都失败（或 45s 总兜底超时）返回 null。
     */
    private suspend fun fetchFirstOk(client: OkHttpClient, urls: List<String>, maxBytes: Int): Pair<String, String>? =
        coroutineScope {
            val winner = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
            val done = kotlinx.coroutines.CompletableDeferred<Pair<String, String>?>()
            val remaining = java.util.concurrent.atomic.AtomicInteger(urls.size)
            urls.map { url ->
                launch(Dispatchers.IO) {
                    val got = runCatching {
                        val req = Request.Builder().url(url)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "couple-app-update")
                            .build()
                        client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) null else r.body?.string()?.takeIf { it.length <= maxBytes }
                        }
                    }.getOrNull()?.let { url to it }
                    if (got != null && winner.compareAndSet(null, got)) done.complete(got)
                    if (remaining.decrementAndGet() == 0 && winner.get() == null) done.complete(null)
                }
            }
            runCatching { kotlinx.coroutines.withTimeout(45_000) { done.await() } }.getOrNull()
        }

    /**
     * Direct GitHub update check (no server, no login, no token), rebased on
     * PR #8: PR #8 already races mirrors and downloads with a token-free
     * client, but its check still requires a server. This path is the
     * server-independent decision step: GitHub Releases list (direct API
     * only) -> release.json + release.json.sig through gh-proxy candidates
     * -> signed-manifest verification via the existing [vet] path.
     */
    private suspend fun checkGithub(current: Int, manual: Boolean): GithubResult {
        _state.update { it.copy(checking = true, shizuku = installer.shizukuState()) }
        val now = clock.nowMillis()
        val client = githubClient()
        val ipv6 = hasIpv6()
        val lastHost = prefs.getString(KEY_LAST_MIRROR_HOST, null)
        // API 列表并行竞速：官方直连 + gh-proxy API 代理同时发，最先 200 的赢。
        // 旧实现只打直连一个，被墙时干等 15s 连接超时，是「检查更新慢」的主因。
        val firstError = java.util.concurrent.atomic.AtomicInteger(0)
        val listResult: Pair<Int, String?>? = coroutineScope {
            val winner = java.util.concurrent.atomic.AtomicReference<Pair<Int, String?>?>(null)
            val done = kotlinx.coroutines.CompletableDeferred<Pair<Int, String?>?>()
            val urls = MirrorNodes.apiCandidates(ipv6, lastHost)
            val remaining = java.util.concurrent.atomic.AtomicInteger(urls.size)
            urls.map { url ->
                launch(Dispatchers.IO) {
                    val got = runCatching {
                        val req = Request.Builder().url(url)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "couple-app-update")
                            .build()
                        client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) {
                                firstError.compareAndSet(0, r.code)
                                null
                            } else r.code to r.body?.string()
                        }
                    }.getOrNull()
                    if (got != null && got.second != null && winner.compareAndSet(null, got)) done.complete(got)
                    if (remaining.decrementAndGet() == 0 && winner.get() == null) done.complete(null)
                }
            }
            runCatching { kotlinx.coroutines.withTimeout(30_000) { done.await() } }.getOrNull()
        }
        if (listResult == null) {
            _state.update { it.copy(checking = false) }
            val code = firstError.get()
            return GithubResult.Failed(
                when (code) {
                    403, 429 -> "GitHub 检查限流，稍后再试"
                    in 400..599 -> "GitHub 检查失败：HTTP $code（已尝试镜像）"
                    else -> "GitHub 检查失败：网络错误（已尝试镜像）"
                },
            )
        }
        val listBody: String = listResult.second ?: return GithubResult.Failed("GitHub 检查失败：空响应")
        val release = GithubUpdate.pickLatest(GithubUpdate.parseReleases(listBody))
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("GitHub 上暂无正式版本")
            }
        val manifestAsset = GithubUpdate.manifestAsset(release)
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("GitHub 版本缺少更新清单")
            }
        val sigAsset = GithubUpdate.sigAsset(release)
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("GitHub 版本缺少更新签名")
            }
        val manifestGot = fetchFirstOk(client, MirrorNodes.candidates(manifestAsset.browser_download_url, ipv6, lastHost), 96 * 1024)
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("更新清单下载失败，已尝试镜像和直连")
            }
        val sigGot = fetchFirstOk(client, MirrorNodes.candidates(sigAsset.browser_download_url, ipv6, lastHost), 16 * 1024)
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("更新签名下载失败，已尝试镜像和直连")
            }
        MirrorNodes.lastSuccessOf(manifestGot.first)?.let { prefs.edit().putString(KEY_LAST_MIRROR_HOST, it).apply() }
            ?: prefs.edit().remove(KEY_LAST_MIRROR_HOST).apply()
        val manifest = runCatching {
            CoupleJson.decodeFromString(SignedManifest.serializer(), manifestGot.second)
        }.getOrNull()
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("更新清单无法解析", signatureBad = true)
            }
        val apkAsset = GithubUpdate.apkAsset(release, manifest.versionName)
            ?: run {
                _state.update { it.copy(checking = false) }
                return GithubResult.Failed("GitHub 版本缺少安装包")
            }
        val info = UpdateInfo(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            sha256 = manifest.sha256,
            size = manifest.size,
            signingCertSha256 = manifest.signingCertSha256,
            notes = manifest.notes,
            downloadUrl = apkAsset.browser_download_url,
            mirrors = MirrorNodes.candidates(apkAsset.browser_download_url, ipv6, prefs.getString(KEY_LAST_MIRROR_HOST, null)),
            publishedAt = manifest.publishedAt,
            manifest = java.util.Base64.getEncoder().encodeToString(manifestGot.second.toByteArray(Charsets.UTF_8)),
            manifestSig = sigGot.second.trim(),
            protocolVersion = manifest.protocolVersion,
            minPeerProtocol = manifest.minPeerProtocol,
        )
        if (!UpdateLogic.isNewer(info, current)) {
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            _state.update { it.copy(checking = false, lastCheckAt = now, available = null, force = false, reason = null, peer = null, lastCheckMessage = "已是最新版本") }
            return GithubResult.UpToDate
        }
        val vetted = vet(info, current)
        if (vetted !is Vetted.Ok) {
            _state.update { it.copy(checking = false) }
            val msg = (vetted as? Vetted.Rejected)?.message ?: "更新被拒绝"
            return GithubResult.Failed(msg, signatureBad = true)
        }
        if (vetted.verifiedVersionCode != null) {
            val next = UpdateLogic.nextHighestSeen(prefs.getInt(KEY_HIGHEST_SEEN, 0), current, vetted.verifiedVersionCode)
            prefs.edit().putInt(KEY_HIGHEST_SEEN, next).apply()
        }
        val force = current < manifest.minSupportedVersionCode
        val reason = if (force) "MIN_SUPPORTED" else null
        val response = UpdateResponse(latest = info, force = force, reason = reason, peer = null)
        prefs.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putString(KEY_RESPONSE, CoupleJson.encodeToString(UpdateResponse.serializer(), response))
            .apply()
        _state.update {
            it.copy(
                checking = false, lastCheckAt = now, available = info, force = force,
                reason = reason, peer = null,
                dismissedVersionCode = if (manual) null else it.dismissedVersionCode,
            )
        }
        _state.update { it.copy(lastCheckMessage = "发现新版本 ${info.versionName}") }
        return GithubResult.Available(info, force, reason)
    }

    /** Show the prompt again (「查看更新」 in 我的). */
    fun reopen() = _state.update { it.copy(dismissedVersionCode = null) }

    /**
     * OTA §9-4 后台预下载：自动检查发现可选更新且当前 非计费网络 + 电量不低 时，只下载不安装
     * （[installWhenReady] 保持 false，下载校验完成后停在 Ready，用户点「立即更新」才安装）。
     */
    fun prefetchIfCheap() {
        val s = _state.value
        val info = s.available ?: return
        if (s.force) return // 强更弹窗本身就会引导下载
        if (s.phase is UpdatePhase.Downloading || s.phase is UpdatePhase.Ready) return
        if (!isUnmetered()) return
        if (!batteryNotLow()) return
        Log.i(TAG, "prefetching v${info.versionCode} in the background (unmetered, battery ok)")
        startDownloadOnly(info)
    }

    private fun isUnmetered(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        cm.isActiveNetworkMetered == false
    }.getOrDefault(false)

    private fun batteryNotLow(): Boolean = runCatching {
        val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return true
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        level <= 0 || level >= 30 // 未知(-1)视为不低
    }.getOrDefault(true)

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
        enqueueDownload(info, running = phase is UpdatePhase.Downloading)
    }

    /** 预下载：同 [enqueueDownload]，但绝不置 installWhenReady。 */
    private fun startDownloadOnly(info: UpdateInfo) {
        installWhenReady = false
        enqueueDownload(info, running = _state.value.phase is UpdatePhase.Downloading)
    }

    private fun enqueueDownload(info: UpdateInfo, running: Boolean) {
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl
        // Re-verify to get the signed mirror list (cheap; `available` only ever holds an offer that passed vet()).
        val signed = (ManifestVerifier.verify(info, BuildConfig.OTA_PUBLIC_KEY, ManifestVerifier.releasePackageOf(context.packageName))
            as? ManifestVerifier.Result.Verified)?.manifest
        val mirrors = base?.let { UpdateLogic.downloadMirrors(it, info, signed) }.orEmpty()
            .ifEmpty {
                // GitHub-direct check (PR1, rebased on PR #8): no server base, but
                // info.mirrors holds the gh-proxy candidate list for the same
                // signed APK asset. These are https and token-free, and the
                // downloaded bytes are still verified by size + sha256 + cert.
                info.mirrors.mapNotNull { it.toHttpUrlOrNull() }
                    .filter { it.isHttps }
                    .map { Mirror(it.toString(), authenticated = false) }
            }
        if (mirrors.isEmpty()) {
            _state.update { it.copy(phase = UpdatePhase.Failed("下载地址无效")) }
            return
        }
        val req = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UpdateDownloadWorker.KEY_URLS to mirrors.map { it.url }.toTypedArray(),
                    UpdateDownloadWorker.KEY_AUTH to mirrors.map { it.authenticated }.toBooleanArray(),
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
        // Remember what we are about to install (synchronously: a successful install kills this process) so the new
        // version can send its post-install health report.
        _state.value.available?.versionCode?.let { to ->
            prefs.edit()
                .putInt(KEY_PENDING_FROM, _state.value.currentVersionCode)
                .putInt(KEY_PENDING_TO, to)
                .putLong(KEY_PENDING_AT, clock.nowMillis())
                .remove(KEY_CRASH_VC).remove(KEY_CRASH_DETAIL)
                .commit()
        }
        scope.launch {
            val o = installer.install(file)
            _state.update { it.copy(phase = phaseOf(o, UpdatePhase.Ready(file.absolutePath))) }
        }
    }

    // ---------------- post-install health report (docs/ota-design.md §5) ----------------

    private fun readPending(): HealthLogic.Pending? {
        val to = prefs.getInt(KEY_PENDING_TO, 0).takeIf { it > 0 } ?: return null
        return HealthLogic.Pending(prefs.getInt(KEY_PENDING_FROM, 0), to, prefs.getLong(KEY_PENDING_AT, 0))
    }

    private fun clearPending() {
        prefs.edit().remove(KEY_PENDING_FROM).remove(KEY_PENDING_TO).remove(KEY_PENDING_AT)
            .remove(KEY_CRASH_VC).remove(KEY_CRASH_DETAIL).apply()
    }

    /** While a freshly installed version is being verified, an uncaught crash leaves a marker for the next start. */
    private fun installCrashMarker() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val p = readPending()
                if (p != null && p.to == _state.value.currentVersionCode) {
                    prefs.edit().putInt(KEY_CRASH_VC, p.to).putString(KEY_CRASH_DETAIL, HealthLogic.crashDetail(e)).commit()
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * Called once per process when the app is configured + signed in. If this process is the first run(s) of a
     * version we installed ourselves: report CRASH (marker from a previous run), OK on the first MQTT connect, or
     * FAILED after [HealthLogic.CONNECT_TIMEOUT_MS] without one. The record is kept until the server accepted it.
     */
    fun startHealthWatch(mqttConnected: Flow<Boolean>) {
        if (healthWatchStarted) return
        healthWatchStarted = true
        scope.launch {
            val pending = readPending()
            val installed = _state.value.currentVersionCode
            val crash = prefs.getInt(KEY_CRASH_VC, 0).takeIf { it > 0 }
            when (HealthLogic.onStart(pending, installed, crash, clock.nowMillis())) {
                HealthLogic.StartAction.NONE -> Unit
                HealthLogic.StartAction.CLEAR -> clearPending()
                HealthLogic.StartAction.REPORT_CRASH ->
                    if (sendHealth(pending!!, "CRASH", null, prefs.getString(KEY_CRASH_DETAIL, null))) clearPending()
                HealthLogic.StartAction.WATCH -> {
                    val ok = withTimeoutOrNull(HealthLogic.CONNECT_TIMEOUT_MS) { mqttConnected.first { it } } != null
                    // A crash during the wait kills the process; the marker is reported on the next start instead.
                    if (sendHealth(pending!!, if (ok) "OK" else "FAILED", ok, if (ok) null else "no MQTT connection within 10 min")) clearPending()
                }
            }
        }
    }

    private suspend fun sendHealth(p: HealthLogic.Pending, status: String, mqtt: Boolean?, detail: String?): Boolean {
        val body = HealthReport(
            versionCode = p.to, fromVersionCode = p.from, status = status, mqttConnected = mqtt, detail = detail,
            protocolVersion = BuildConfig.PEER_PROTOCOL_VERSION, minPeerProtocol = BuildConfig.MIN_PEER_PROTOCOL,
            sdkInt = Build.VERSION.SDK_INT,
        )
        val r = apiCall { api.healthReport(body) }
        Log.i(TAG, "health report $status for ${p.to}: $r")
        // 4xx will never succeed on retry; drop it rather than retrying on every start.
        return r is ApiResult.Ok && (r.value.isSuccessful || r.value.code() in 400..499)
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
        private const val KEY_HIGHEST_SEEN = "highest_seen_version_code"
        private const val KEY_PENDING_FROM = "health_pending_from"
        private const val KEY_PENDING_TO = "health_pending_to"
        private const val KEY_PENDING_AT = "health_pending_at"
        private const val KEY_CRASH_VC = "health_crash_version_code"
        private const val KEY_CRASH_DETAIL = "health_crash_detail"
        private const val KEY_LAST_MIRROR_HOST = "last_mirror_host"
        private const val TAG = "UpdateRepository"

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
