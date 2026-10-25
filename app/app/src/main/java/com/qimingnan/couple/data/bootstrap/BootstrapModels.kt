package com.qimingnan.couple.data.bootstrap

import kotlinx.serialization.Serializable

/** 二维码里 d 字段解码后的 JSON（contract: {v:1, baseUrl, mqttUrl, configVersion, issuedAt}）。 */
@Serializable
data class BootstrapPayload(
    val v: Int,
    val baseUrl: String,
    val mqttUrl: String,
    val configVersion: Long,
    val issuedAt: Long,
)

/** 已应用到本机的服务器配置。 */
@Serializable
data class ServerConfig(
    val baseUrl: String,
    val mqttUrl: String,
    val configVersion: Long,
    val issuedAt: Long,
    /** false = 调试用手动输入，未经签名校验 */
    val signed: Boolean,
    val appliedAt: Long,
)

/**
 * 持久化的引导状态。
 * - current：当前生效（或正在试用）的配置
 * - previous：试用期内可回滚到的上一份配置
 * - pendingSince：非空表示 current 还在 30 秒健康检查试用期内
 * - pinnedPublicKey：TOFU 固定的 Ed25519 公钥（raw 32 字节的标准 base64）
 * - pinIsProvisional：公钥是本次试用时才首次固定（或经用户明确确认后更换）的，试用失败需要一起撤销
 * - previousPinnedPublicKey：用户确认更换公钥时被替换掉的旧公钥，试用失败时恢复
 * - trustedHost / trustedKeyFingerprint：最近一次健康检查通过、已确认生效的服务器主机名与公钥指纹。
 *   之后扫码或手动配置若主机名或公钥不同，必须先强提醒并让用户明确确认。
 */
@Serializable
data class BootstrapState(
    val current: ServerConfig? = null,
    val previous: ServerConfig? = null,
    val pendingSince: Long? = null,
    val pinnedPublicKey: String? = null,
    val pinIsProvisional: Boolean = false,
    val previousPinnedPublicKey: String? = null,
    val trustedHost: String? = null,
    val trustedKeyFingerprint: String? = null,
) {
    /** Trust anchors, falling back to the committed config / pin for state written by older app versions. */
    fun effectiveTrustedHost(): String? =
        trustedHost ?: current?.takeIf { pendingSince == null }?.let { TrustedHost.of(it.baseUrl) }

    fun effectiveTrustedFingerprint(): String? =
        trustedKeyFingerprint ?: pinnedPublicKey?.takeIf { !pinIsProvisional }
            ?.let { runCatching { KeyFingerprint.of(com.qimingnan.couple.core.Base64Url.decode(it)) }.getOrNull() }
}

@Serializable
data class PubkeyResponse(val alg: String, val publicKey: String)

@Serializable
data class HealthResponse(val status: String, val version: String? = null, val protocolVersion: Int? = null)
