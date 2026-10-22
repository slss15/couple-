package com.qimingnan.couple.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.data.bootstrap.BootstrapState
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.bootstrap.HealthResult
import com.qimingnan.couple.data.bootstrap.ServerConfig
import com.qimingnan.couple.data.bootstrap.ServerProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class TestSigner {
    private val kp = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    val priv = kp.private as Ed25519PrivateKeyParameters
    val pubRaw: ByteArray = (kp.public as Ed25519PublicKeyParameters).encoded

    fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, priv); update(data, 0, data.size); generateSignature()
    }

    /** 与后端/管理后台相同的格式：couple://bootstrap?d=<b64url(json)>&s=<b64url(sig)> */
    fun uri(baseUrl: String, mqttUrl: String, configVersion: Long, issuedAt: Long = 1_700_000_000_000): String {
        val json = """{"v":1,"baseUrl":"$baseUrl","mqttUrl":"$mqttUrl","configVersion":$configVersion,"issuedAt":$issuedAt}"""
        val d = json.toByteArray()
        return "couple://bootstrap?d=${Base64Url.encode(d)}&s=${Base64Url.encode(sign(d))}"
    }
}

class FakeStore(initial: BootstrapState = BootstrapState()) : BootstrapStore {
    private val flow = MutableStateFlow<BootstrapState?>(initial)
    override val state: StateFlow<BootstrapState?> = flow
    override suspend fun load(): BootstrapState = flow.value!!
    override suspend fun save(state: BootstrapState) { flow.value = state }
    override fun currentConfigBlocking(): ServerConfig? = flow.value?.current
    val value: BootstrapState get() = flow.value!!
}

class FakeProbe(var pubkey: ByteArray) : ServerProbe {
    /** baseUrl -> 健康检查结果脚本（依次返回，最后一个保持） */
    val health = mutableMapOf<String, MutableList<HealthResult>>()
    var pubkeyCalls = 0
    var healthCalls = 0

    override suspend fun fetchPublicKey(baseUrl: String): ByteArray { pubkeyCalls++; return pubkey }

    override suspend fun health(baseUrl: String): HealthResult {
        healthCalls++
        val script = health[baseUrl] ?: return HealthResult.Unreachable("no route")
        return if (script.size > 1) script.removeAt(0) else script.first()
    }
}
