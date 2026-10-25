package com.qimingnan.couple.data.bootstrap

import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.core.CoupleJson
import java.net.URI
import java.net.URLDecoder

/** 解析后的二维码：dBytes 是被签名的原始字节（即 base64url 解码后的 JSON UTF-8 字节）。 */
class ParsedBootstrap(
    val dBytes: ByteArray,
    val signature: ByteArray,
    val payload: BootstrapPayload,
)

sealed class BootstrapError(val code: String, message: String) : Exception(message) {
    class InvalidUri(msg: String) : BootstrapError("INVALID_URI", msg)
    class UnsupportedVersion(v: Int) : BootstrapError("UNSUPPORTED_VERSION", "不支持的配置版本 v=$v")
    class InsecureUrl(url: String) : BootstrapError("INSECURE_URL", "地址必须使用 https/wss：$url")
    class SignatureInvalid : BootstrapError("SIGNATURE_INVALID", "签名校验失败，配置可能被篡改或来自其他服务器")
    class PubkeyUnavailable(msg: String) : BootstrapError("PUBKEY_UNAVAILABLE", "无法获取服务器公钥：$msg")
    class Downgrade(cur: Long, new: Long) : BootstrapError("VERSION_DOWNGRADE", "配置版本 $new 低于当前版本 $cur")
    /** Host or key differs from the trusted one and the user did not explicitly confirm exactly this change. */
    class TrustChangeUnconfirmed(val trust: TrustCheck?, msg: String) : BootstrapError("TRUST_CHANGE_UNCONFIRMED", msg)
}

object BootstrapUriParser {
    const val SCHEME = "couple"
    const val HOST = "bootstrap"
    const val SUPPORTED_VERSION = 1

    fun parse(raw: String): ParsedBootstrap {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            throw BootstrapError.InvalidUri("无法解析链接")
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host ?: uri.authority, ignoreCase = true)) {
            throw BootstrapError.InvalidUri("不是 couple://bootstrap 链接")
        }
        val params = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val idx = it.indexOf('=')
            if (idx < 0) it to "" else it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }
        val d = params["d"] ?: throw BootstrapError.InvalidUri("缺少参数 d")
        val s = params["s"] ?: throw BootstrapError.InvalidUri("缺少参数 s")
        val dBytes = try { Base64Url.decode(d) } catch (e: Exception) { throw BootstrapError.InvalidUri("d 不是合法的 base64url") }
        val sig = try { Base64Url.decode(s) } catch (e: Exception) { throw BootstrapError.InvalidUri("s 不是合法的 base64url") }
        if (sig.size != 64) throw BootstrapError.InvalidUri("签名长度错误")
        val payload = try {
            CoupleJson.decodeFromString(BootstrapPayload.serializer(), dBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BootstrapError.InvalidUri("配置 JSON 无法解析")
        }
        if (payload.v != SUPPORTED_VERSION) throw BootstrapError.UnsupportedVersion(payload.v)
        return ParsedBootstrap(dBytes, sig, payload)
    }

    /** release 只接受 https / wss；debug 允许 http / ws 方便局域网调试。 */
    fun checkUrls(baseUrl: String, mqttUrl: String, allowCleartext: Boolean) {
        val b = baseUrl.lowercase()
        val m = mqttUrl.lowercase()
        val baseOk = b.startsWith("https://") || (allowCleartext && b.startsWith("http://"))
        val mqttOk = m.startsWith("wss://") || (allowCleartext && m.startsWith("ws://"))
        if (!baseOk) throw BootstrapError.InsecureUrl(baseUrl)
        if (!mqttOk) throw BootstrapError.InsecureUrl(mqttUrl)
    }
}
