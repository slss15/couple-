package com.qimingnan.couple.data.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.core.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ApplyOutcome {
    data class Applied(val config: ServerConfig) : ApplyOutcome
    /** 健康检查失败，已回滚到 restored（可能为 null：首次配置失败）。 */
    data class RolledBack(val reason: String, val restored: ServerConfig?) : ApplyOutcome
    data class Rejected(val error: BootstrapError) : ApplyOutcome
}

/** 进度回调：UI 用来显示“正在校验签名 / 健康检查剩余秒数”。 */
sealed interface ApplyProgress {
    data object VerifyingSignature : ApplyProgress
    data class HealthChecking(val secondsLeft: Int, val lastError: String?) : ApplyProgress
}

/** 试用期调度：真实实现用 WorkManager 兜底（进程被杀后仍会完成回滚判断）。 */
fun interface PendingVerificationScheduler {
    fun schedule(delayMillis: Long)
}

/**
 * 引导配置的完整生命周期：
 * 解析 → TOFU 固定公钥 → 校验签名 → 防降级 → 暂存（保留上一份）→ 30 秒健康检查 → 提交或回滚。
 */
class BootstrapManager(
    private val store: BootstrapStore,
    private val probe: ServerProbe,
    private val clock: Clock,
    private val allowCleartext: Boolean,
    private val scheduler: PendingVerificationScheduler = PendingVerificationScheduler { },
    private val healthWindowMillis: Long = HEALTH_WINDOW_MILLIS,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    /** 实际的挂起等待；单测里用 runTest 的虚拟时间。 */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    suspend fun applyUri(raw: String, onProgress: (ApplyProgress) -> Unit = {}): ApplyOutcome {
        onProgress(ApplyProgress.VerifyingSignature)
        val staged = try {
            mutex.withLock { stageSigned(raw) }
        } catch (e: BootstrapError) {
            return ApplyOutcome.Rejected(e)
        }
        return verifyPending(onProgress) ?: ApplyOutcome.Applied(staged)
    }

    /** 调试入口：手动填写地址，不做签名校验，但同样走健康检查与回滚。 */
    suspend fun applyManual(baseUrl: String, mqttUrl: String, onProgress: (ApplyProgress) -> Unit = {}): ApplyOutcome {
        val staged = try {
            mutex.withLock {
                BootstrapUriParser.checkUrls(baseUrl, mqttUrl, allowCleartext)
                val state = store.load()
                val cfg = ServerConfig(
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    mqttUrl = mqttUrl.trim(),
                    configVersion = state.current?.configVersion ?: 0,
                    issuedAt = clock.nowMillis(),
                    signed = false,
                    appliedAt = clock.nowMillis(),
                )
                stage(state, cfg, state.pinnedPublicKey, state.pinIsProvisional)
                cfg
            }
        } catch (e: BootstrapError) {
            return ApplyOutcome.Rejected(e)
        }
        return verifyPending(onProgress) ?: ApplyOutcome.Applied(staged)
    }

    private suspend fun stageSigned(raw: String): ServerConfig {
        val parsed = BootstrapUriParser.parse(raw)
        val p = parsed.payload
        BootstrapUriParser.checkUrls(p.baseUrl, p.mqttUrl, allowCleartext)
        val state = store.load()

        // TOFU：已固定就只认它；否则从配置里的服务器取公钥并（暂定地）固定。
        val pinned = state.pinnedPublicKey
        val (keyBytes, provisional) = if (pinned != null) {
            Base64Url.decode(pinned) to state.pinIsProvisional
        } else {
            probe.fetchPublicKey(p.baseUrl) to true
        }
        if (!Ed25519Verifier.verify(keyBytes, parsed.dBytes, parsed.signature)) {
            throw BootstrapError.SignatureInvalid()
        }
        val cur = state.current
        if (cur != null && cur.signed && p.configVersion < cur.configVersion) {
            throw BootstrapError.Downgrade(cur.configVersion, p.configVersion)
        }
        val cfg = ServerConfig(
            baseUrl = p.baseUrl.trimEnd('/'),
            mqttUrl = p.mqttUrl,
            configVersion = p.configVersion,
            issuedAt = p.issuedAt,
            signed = true,
            appliedAt = clock.nowMillis(),
        )
        stage(state, cfg, Base64Url.encodeStd(keyBytes), provisional)
        return cfg
    }

    private suspend fun stage(state: BootstrapState, cfg: ServerConfig, pin: String?, provisional: Boolean) {
        // 若上一份还在试用期，就保留更早的“已确认”配置作为回滚目标。
        val rollbackTarget = if (state.pendingSince != null) state.previous else state.current
        store.save(
            state.copy(
                current = cfg,
                previous = rollbackTarget,
                pendingSince = clock.nowMillis(),
                pinnedPublicKey = pin,
                pinIsProvisional = provisional,
            ),
        )
        scheduler.schedule(healthWindowMillis + 5_000)
    }

    /**
     * 若有处于试用期的配置，在剩余窗口内轮询 /health。
     * 返回 null 表示没有试用中的配置或已确认成功；否则返回回滚结果。
     * 幂等：App 启动时和 WorkManager 兜底任务都会调用。
     */
    suspend fun verifyPending(onProgress: (ApplyProgress) -> Unit = {}): ApplyOutcome.RolledBack? = mutex.withLock {
        val state = store.load()
        val since = state.pendingSince ?: return@withLock null
        val cfg = state.current ?: run {
            store.save(state.copy(pendingSince = null))
            return@withLock null
        }
        val deadline = since + healthWindowMillis
        var lastError: String? = null
        var first = true
        while (first || clock.nowMillis() < deadline) {
            first = false
            val left = ((deadline - clock.nowMillis()).coerceAtLeast(0) + 999) / 1000
            onProgress(ApplyProgress.HealthChecking(left.toInt(), lastError))
            when (val r = probe.health(cfg.baseUrl)) {
                is HealthResult.Ok -> {
                    store.save(
                        store.load().copy(pendingSince = null, previous = null, pinIsProvisional = false),
                    )
                    return@withLock null
                }
                is HealthResult.Incompatible -> return@withLock rollback(r.reason)
                is HealthResult.Unreachable -> lastError = r.reason
            }
            val remaining = deadline - clock.nowMillis()
            if (remaining <= 0) break
            sleeper(minOf(pollIntervalMillis, remaining))
        }
        rollback("30 秒内无法连接 /health：${lastError ?: "超时"}")
    }

    private suspend fun rollback(reason: String): ApplyOutcome.RolledBack {
        val state = store.load()
        val restored = state.previous
        store.save(
            state.copy(
                current = restored,
                previous = null,
                pendingSince = null,
                // 公钥若是这次才首次固定的，从未被确认过，一并撤销。
                pinnedPublicKey = if (state.pinIsProvisional) null else state.pinnedPublicKey,
                pinIsProvisional = false,
            ),
        )
        return ApplyOutcome.RolledBack(reason, restored)
    }

    /** 调试/换服务器：清除固定的公钥（需用户在“我的”里明确操作）。 */
    suspend fun resetPin() = mutex.withLock {
        store.save(store.load().copy(pinnedPublicKey = null, pinIsProvisional = false))
    }

    companion object {
        const val HEALTH_WINDOW_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
