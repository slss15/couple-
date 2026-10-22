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
 * - pinIsProvisional：公钥是本次试用时才首次固定的，试用失败需要一起撤销
 */
@Serializable
data class BootstrapState(
    val current: ServerConfig? = null,
    val previous: ServerConfig? = null,
    val pendingSince: Long? = null,
    val pinnedPublicKey: String? = null,
    val pinIsProvisional: Boolean = false,
)

@Serializable
data class PubkeyResponse(val alg: String, val publicKey: String)

@Serializable
data class HealthResponse(val status: String, val version: String? = null, val protocolVersion: Int? = null)
