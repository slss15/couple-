package com.qimingnan.couple.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.concurrent.LinkedBlockingQueue

@Serializable
data class DeployRecord(
    val version: String? = null,
    val sha256: String? = null,
    val sha: String? = null,
    val source: String? = null,
    /** queued | deploying | success | noop | failed | rolled_back | rollback_failed */
    val state: String? = null,
    val error: String? = null,
    val previous: String? = null,
    val requestedAt: Long? = null,
    val finishedAt: Long? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class State(
    val current: Map<String, String?> = mapOf("server" to null),
    val currentSha256: String? = null,
    val bad: Map<String, List<String>> = mapOf("server" to emptyList()),
    val deploys: Map<String, DeployRecord> = emptyMap(),
    val lastCheck: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val channel: String = "edge",
)

/** Non-sensitive view served at GET /_deploy/status and posted to the backend (same shape as the Python updater). */
@Serializable
data class PublicStatus(
    val target: String = "windows",
    val channel: String,
    val enabled: Boolean,
    val current: Map<String, String?>,
    val deploys: Map<String, DeployRecord>,
    val lastCheck: Long?,
    val lastResult: String?,
    val lastError: String?,
)

val StateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/**
 * Deploy engine for the Windows package. Both triggers (signed webhook, poller) feed one worker queue, so deploys
 * never overlap. Deploy = download -> verify sha256 -> extract -> stop -> render service config + CURRENT -> start ->
 * health check; on failure roll back to the previous release and mark the sha256 bad (the poller skips it).
 */
class Updater(
    val cfg: Config,
    private val service: ServiceControl,
    private val health: HealthProbe,
    private val downloader: Downloader,
    private val source: ReleaseSource,
    private val postStatus: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val lock = Any()
    private val statePath: Path = cfg.stateDir.resolve("updater.json")
    val releases = Releases(cfg)
    val replay: ReplayCache
    private val jobs = LinkedBlockingQueue<() -> Unit>()

    @Volatile var state: State
        private set

    init {
        Files.createDirectories(cfg.stateDir)
        Files.createDirectories(cfg.releasesDir)
        state = runCatching { StateJson.decodeFromString(State.serializer(), Files.readString(statePath)) }.getOrDefault(State())
            .copy(channel = cfg.channel)
        replay = ReplayCache(cfg.stateDir.resolve("hook-replay.json"), cfg.hookMaxSkew)
        // CURRENT (written by install.ps1 or a previous deploy) is the truth about what runs.
        releases.current()?.let { cur -> if (state.current["server"] != cur) state = state.copy(current = mapOf("server" to cur)) }
    }

    fun currentVersion(): String? = state.current["server"]

    fun publicStatus(): PublicStatus = synchronized(lock) {
        PublicStatus(
            channel = cfg.channel, enabled = cfg.enabled, current = state.current, deploys = state.deploys,
            lastCheck = state.lastCheck, lastResult = state.lastResult, lastError = state.lastError,
        )
    }

    fun publicStatusJson(): String = StateJson.encodeToString(PublicStatus.serializer(), publicStatus())

    private fun update(f: (State) -> State) {
        val json = synchronized(lock) {
            state = f(state)
            val tmp = statePath.resolveSibling("updater.json.tmp")
            Files.writeString(tmp, StateJson.encodeToString(State.serializer(), state))
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            publicStatusJson()
        }
        postStatus(json)
    }

    private fun setDeploy(f: (DeployRecord) -> DeployRecord) = update { s ->
        s.copy(deploys = s.deploys + ("server" to f(s.deploys["server"] ?: DeployRecord()).copy(updatedAt = clock())))
    }

    private fun finish(st: String, error: String?) {
        update { s ->
            s.copy(lastResult = "server:$st", lastError = if (error != null) "server:$error" else s.lastError)
        }
        setDeploy { it.copy(state = st, error = error, finishedAt = clock()) }
    }

    private fun markBad(sha256: String) = update { s ->
        val list = (s.bad["server"].orEmpty() + sha256).distinct().takeLast(20)
        s.copy(bad = s.bad + ("server" to list))
    }

    private fun waitHealthy(): Boolean {
        val deadline = clock() + cfg.healthTimeoutSeconds * 1000
        while (clock() < deadline) {
            if (health.status(cfg.healthUrl) == 200) return true
            sleep(cfg.healthPollMillis)
        }
        return false
    }

    /** Runs one deploy synchronously and returns the terminal state. */
    fun deploy(job: DeployJob, source: String): String {
        val prev = currentVersion()
        setDeploy { DeployRecord(version = job.version, sha256 = job.sha256, sha = job.sha, source = source, state = "deploying", previous = prev, requestedAt = it.requestedAt) }
        if (prev == job.version && (state.currentSha256 == null || state.currentSha256 == job.sha256)) {
            // Version names are unique per build (X.Y.Z tags, edge-<sha>); an unknown sha256 means install.ps1 put it there.
            if (state.currentSha256 == null) update { it.copy(currentSha256 = job.sha256) }
            finish("noop", null); return "noop"
        }
        if (prev == job.version) {
            // Same version name but different bytes (e.g. re-run of a tag build): never overwrite the running copy.
            finish("failed", "version_in_use"); return "failed"
        }
        // 1) download + verify (nothing on the running system is touched yet)
        val dl = cfg.stateDir.resolve("downloads").also { Files.createDirectories(it) }.resolve("server-${job.version}.zip")
        try {
            downloader.download(job.assetUrl, dl)
            val got = Files2.sha256(dl)
            if (got != job.sha256) {
                log("sha256 mismatch for ${job.version}: got $got")
                markBad(job.sha256)
                finish("failed", "sha256_mismatch"); return "failed"
            }
            releases.install(dl, job.version, keep = prev)
        } catch (e: Exception) {
            log("download/extract ${job.version} failed: ${e.message}")
            finish("failed", if (e is java.io.IOException && e.message?.contains("incomplete") == true) "bad_package" else "download_failed")
            return "failed"
        } finally {
            runCatching { Files.deleteIfExists(dl) }
        }
        // 2) switch
        val error: String = run {
            if (!service.stop()) log("stop reported failure; continuing with switch")
            try {
                releases.render(job.version)
                releases.setCurrent(job.version)
            } catch (e: Exception) {
                log("render ${job.version} failed: ${e.message}")
                return@run "render_failed"
            }
            if (!service.start()) return@run "start_failed"
            if (waitHealthy()) {
                update { it.copy(current = mapOf("server" to job.version), currentSha256 = job.sha256) }
                finish("success", null)
                log("server -> ${job.version} ($source) healthy")
                runCatching { releases.prune(alsoKeep = prev) }
                return "success"
            }
            "health_timeout"
        }
        // 3) roll back
        log("server ${job.version} failed ($error); rolling back to $prev")
        markBad(job.sha256)
        if (prev == null) {
            finish("failed", error); return "failed"
        }
        service.stop()
        val restored = runCatching {
            releases.render(prev)
            releases.setCurrent(prev)
        }.isSuccess && service.start() && waitHealthy()
        update { it.copy(current = mapOf("server" to prev)) }
        return if (restored) {
            finish("rolled_back", error); "rolled_back"
        } else {
            finish("rollback_failed", error); "rollback_failed"
        }
    }

    fun pollOnce(now: LocalTime = LocalTime.now()) {
        update { it.copy(lastCheck = clock()) }
        if (!cfg.enabled || !inWindow(cfg.window, now)) return
        val remote = source.latest(cfg.channel)
        if (remote == null) {
            update { it.copy(lastError = "server:remote_unavailable") }
            return
        }
        if (remote.version == currentVersion() && (state.currentSha256 == null || state.currentSha256 == remote.sha256)) return
        if (remote.sha256 in state.bad["server"].orEmpty()) {
            log("remote ${remote.version} (${remote.sha256.take(12)}) is marked bad; waiting for a newer build")
            return
        }
        val err = assetUrlError(cfg, remote.assetUrl)
        if (err != null) {
            update { it.copy(lastError = "server:$err") }
            return
        }
        deploy(DeployJob(remote.version, remote.assetUrl, remote.sha256, remote.sha), "poll")
    }

    fun enqueueHook(job: DeployJob) {
        setDeploy { DeployRecord(version = job.version, sha256 = job.sha256, sha = job.sha, source = "hook", state = "queued", requestedAt = clock()) }
        jobs.put { deploy(job, "hook") }
    }

    fun enqueuePoll() = jobs.put { pollOnce() }

    fun workerLoop() {
        while (true) {
            val job = jobs.take()
            try {
                job()
            } catch (e: Exception) {
                log("job crashed: $e")
                update { it.copy(lastError = "internal:${e.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        /** "HH:MM-HH:MM" local time, may wrap midnight; empty = always. */
        fun inWindow(window: String, now: LocalTime): Boolean {
            if (window.isBlank()) return true
            val m = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$").find(window.trim()) ?: return true
            val (h1, m1, h2, m2) = m.destructured
            val a = h1.toInt() * 60 + m1.toInt()
            val b = h2.toInt() * 60 + m2.toInt()
            val cur = now.hour * 60 + now.minute
            return if (a <= b) cur in a until b else (cur >= a || cur < b)
        }
    }
}
