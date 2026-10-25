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

    /**
     * Reads a couple://bootstrap link without storing anything: verifies the signature (against the pinned key, or —
     * to be able to show the user what changed — against the key the config's server now serves) and compares host
     * and key fingerprint with what this phone trusts. The confirm page shows the result.
     */
    suspend fun inspectUri(raw: String): BootstrapPreview = mutex.withLock {
        val parsed = BootstrapUriParser.parse(raw)
        val p = parsed.payload
        BootstrapUriParser.checkUrls(p.baseUrl, p.mqttUrl, allowCleartext)
        val state = store.load()
        val newHost = TrustedHost.of(p.baseUrl) ?: throw BootstrapError.InvalidUri("无法识别服务器主机名")
        val pinned = state.pinnedPublicKey?.let(Base64Url::decode)
        val key = when {
            pinned != null && Ed25519Verifier.verify(pinned, parsed.dBytes, parsed.signature) -> pinned
            else -> {
                val fetched = probe.fetchPublicKey(p.baseUrl)
                if (!Ed25519Verifier.verify(fetched, parsed.dBytes, parsed.signature)) throw BootstrapError.SignatureInvalid()
                fetched
            }
        }
        val fp = KeyFingerprint.of(key)
        BootstrapPreview(
            baseUrl = p.baseUrl.trimEnd('/'),
            mqttUrl = p.mqttUrl,
            host = newHost,
            fingerprint = fp,
            configVersion = p.configVersion,
            signed = true,
            trust = TrustCheck.evaluate(state.effectiveTrustedHost(), state.effectiveTrustedFingerprint(), newHost, fp),
        )
    }

    /** Same as [inspectUri] for the debug manual form (no signature, so only the host can change). */
    suspend fun inspectManual(baseUrl: String, mqttUrl: String): BootstrapPreview = mutex.withLock {
        BootstrapUriParser.checkUrls(baseUrl, mqttUrl, allowCleartext)
        val state = store.load()
        val newHost = TrustedHost.of(baseUrl) ?: throw BootstrapError.InvalidUri("无法识别服务器主机名")
        BootstrapPreview(
            baseUrl = baseUrl.trim().trimEnd('/'),
            mqttUrl = mqttUrl.trim(),
            host = newHost,
            fingerprint = null,
            configVersion = null,
            signed = false,
            trust = TrustCheck.evaluate(state.effectiveTrustedHost(), state.effectiveTrustedFingerprint(), newHost, null),
        )
    }

    /**
     * @param confirmation what the user confirmed on the confirm page. Required whenever host or key differ from the
     * trusted ones (otherwise [BootstrapError.TrustChangeUnconfirmed]); a different key is only ever accepted with
     * acceptChange=true and the exact fingerprint the user saw. Without it a config signed by another key is rejected
     * as before (contract: TOFU pin).
     */
    suspend fun applyUri(
        raw: String,
        confirmation: TrustConfirmation? = null,
        onProgress: (ApplyProgress) -> Unit = {},
    ): ApplyOutcome {
        onProgress(ApplyProgress.VerifyingSignature)
        val staged = try {
            mutex.withLock { stageSigned(raw, confirmation) }
        } catch (e: BootstrapError) {
            return ApplyOutcome.Rejected(e)
        }
        return verifyPending(onProgress) ?: ApplyOutcome.Applied(staged)
    }

    /** 调试入口：手动填写地址，不做签名校验，但同样走主机名确认、健康检查与回滚。 */
    suspend fun applyManual(
        baseUrl: String,
        mqttUrl: String,
        confirmation: TrustConfirmation? = null,
        onProgress: (ApplyProgress) -> Unit = {},
    ): ApplyOutcome {
        val staged = try {
            mutex.withLock {
                BootstrapUriParser.checkUrls(baseUrl, mqttUrl, allowCleartext)
                val state = store.load()
                val newHost = TrustedHost.of(baseUrl) ?: throw BootstrapError.InvalidUri("无法识别服务器主机名")
                val trust = TrustCheck.evaluate(state.effectiveTrustedHost(), state.effectiveTrustedFingerprint(), newHost, null)
                requireConfirmed(trust, newHost, null, confirmation)
                val cfg = ServerConfig(
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    mqttUrl = mqttUrl.trim(),
                    configVersion = state.current?.configVersion ?: 0,
                    issuedAt = clock.nowMillis(),
                    signed = false,
                    appliedAt = clock.nowMillis(),
                )
                stage(state, cfg, state.pinnedPublicKey, state.pinIsProvisional, state.previousPinnedPublicKey)
                cfg
            }
        } catch (e: BootstrapError) {
            return ApplyOutcome.Rejected(e)
        }
        return verifyPending(onProgress) ?: ApplyOutcome.Applied(staged)
    }

    private fun requireConfirmed(trust: TrustCheck, host: String, fp: String?, c: TrustConfirmation?) {
        if (c != null) {
            // The config must still be exactly what the user looked at.
            val sameHost = c.host == host
            val sameKey = c.fingerprint == null || fp == null ||
                KeyFingerprint.normalize(c.fingerprint) == KeyFingerprint.normalize(fp)
            if (!sameHost || !sameKey) throw BootstrapError.TrustChangeUnconfirmed(trust, "配置与确认页显示的不一致，请重新扫码")
        }
        if (trust.needsExplicitConfirm && c?.acceptChange != true) {
            throw BootstrapError.TrustChangeUnconfirmed(trust, "服务器地址或公钥与已信任的不同，需要明确确认")
        }
    }

    private suspend fun stageSigned(raw: String, confirmation: TrustConfirmation?): ServerConfig {
        val parsed = BootstrapUriParser.parse(raw)
        val p = parsed.payload
        BootstrapUriParser.checkUrls(p.baseUrl, p.mqttUrl, allowCleartext)
        val state = store.load()
        val newHost = TrustedHost.of(p.baseUrl) ?: throw BootstrapError.InvalidUri("无法识别服务器主机名")

        // TOFU：已固定就只认它；否则从配置里的服务器取公钥并（暂定地）固定。
        // 唯一的例外：用户在确认页看到“公钥已变化”的强提醒并明确确认了新指纹（等同于先重置再首次信任）。
        val pinned = state.pinnedPublicKey
        var previousPin = state.previousPinnedPublicKey
        val keyBytes: ByteArray
        val provisional: Boolean
        if (pinned != null) {
            val pinnedBytes = Base64Url.decode(pinned)
            if (Ed25519Verifier.verify(pinnedBytes, parsed.dBytes, parsed.signature)) {
                keyBytes = pinnedBytes
                provisional = state.pinIsProvisional
            } else if (confirmation?.acceptChange == true && confirmation.fingerprint != null) {
                val fetched = probe.fetchPublicKey(p.baseUrl)
                if (KeyFingerprint.normalize(KeyFingerprint.of(fetched)) != KeyFingerprint.normalize(confirmation.fingerprint)) {
                    throw BootstrapError.TrustChangeUnconfirmed(null, "服务器公钥与确认页显示的指纹不一致，已拒绝")
                }
                if (!Ed25519Verifier.verify(fetched, parsed.dBytes, parsed.signature)) throw BootstrapError.SignatureInvalid()
                keyBytes = fetched
                provisional = true
                // Keep the confirmed key to restore if this trial fails (and not a pin that was itself only provisional).
                previousPin = if (state.pinIsProvisional) state.previousPinnedPublicKey else pinned
            } else {
                throw BootstrapError.SignatureInvalid()
            }
        } else {
            keyBytes = probe.fetchPublicKey(p.baseUrl)
            if (!Ed25519Verifier.verify(keyBytes, parsed.dBytes, parsed.signature)) throw BootstrapError.SignatureInvalid()
            provisional = true
        }
        val fp = KeyFingerprint.of(keyBytes)
        val trust = TrustCheck.evaluate(state.effectiveTrustedHost(), state.effectiveTrustedFingerprint(), newHost, fp)
        requireConfirmed(trust, newHost, fp, confirmation)

        val cur = state.current
        // configVersion only increases per server key; a confirmed key change starts a new sequence.
        if (!trust.keyChanged && cur != null && cur.signed && p.configVersion < cur.configVersion) {
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
        stage(state, cfg, Base64Url.encodeStd(keyBytes), provisional, previousPin)
        return cfg
    }

    private suspend fun stage(
        state: BootstrapState,
        cfg: ServerConfig,
        pin: String?,
        provisional: Boolean,
        previousPin: String?,
    ) {
        // 若上一份还在试用期，就保留更早的“已确认”配置作为回滚目标。
        val rollbackTarget = if (state.pendingSince != null) state.previous else state.current
        store.save(
            state.copy(
                current = cfg,
                previous = rollbackTarget,
                pendingSince = clock.nowMillis(),
                pinnedPublicKey = pin,
                pinIsProvisional = provisional,
                previousPinnedPublicKey = if (provisional) previousPin else null,
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
                    val committed = store.load()
                    store.save(
                        committed.copy(
                            pendingSince = null,
                            previous = null,
                            pinIsProvisional = false,
                            previousPinnedPublicKey = null,
                            // Now trusted: later scans/configs with another host or key get the strong warning.
                            trustedHost = TrustedHost.of(cfg.baseUrl) ?: committed.trustedHost,
                            trustedKeyFingerprint = committed.pinnedPublicKey
                                ?.let { runCatching { KeyFingerprint.of(Base64Url.decode(it)) }.getOrNull() }
                                ?: committed.trustedKeyFingerprint,
                        ),
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
                // 公钥若是这次才首次固定（或刚被用户确认更换）的，从未被确认过：恢复到之前的公钥（首次则为空）。
                pinnedPublicKey = if (state.pinIsProvisional) state.previousPinnedPublicKey else state.pinnedPublicKey,
                pinIsProvisional = false,
                previousPinnedPublicKey = null,
            ),
        )
        return ApplyOutcome.RolledBack(reason, restored)
    }

    /** 调试/换服务器：清除固定的公钥（需用户在“我的”里明确操作）。 */
    suspend fun resetPin() = mutex.withLock {
        // trustedHost / trustedKeyFingerprint stay: the next scan of another server still gets the strong warning.
        store.save(store.load().copy(pinnedPublicKey = null, pinIsProvisional = false, previousPinnedPublicKey = null))
    }

    companion object {
        const val HEALTH_WINDOW_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
