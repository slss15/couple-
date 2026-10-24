package com.qimingnan.couple.backend

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/**
 * Builds a structurally valid APK-like zip with an APK Signing Block holding a v2 (or v3) signer whose first
 * certificate is [cert]. Signatures are not real: the backend only extracts the certificate.
 */
fun fakeSignedApk(cert: ByteArray, payload: String = "hello", schemeId: Int = 0x7109871a): ByteArray {
    val zipBytes = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml"))
            z.write(payload.toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex"))
            z.write(ByteArray(4096) { (it % 251).toByte() })
            z.closeEntry()
        }
    }.toByteArray()

    fun lp(vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
    }
    val certs = lp(lp(cert))
    val digests = lp()
    val signedData = lp(digests, certs, lp())
    val signer = lp(signedData, lp(), lp())
    val schemeValue = lp(signer)
    val pairValue = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(schemeId).array() + schemeValue
    val pair = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(pairValue.size.toLong()).array() + pairValue
    val blockSize = pair.size + 8 + 16L
    val le8 = { v: Long -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array() }
    val block = le8(blockSize) + pair + le8(blockSize) + "APK Sig Block 42".toByteArray()

    // Split zip at the central directory and insert the block before it; patch the EOCD's CD offset.
    val bb = ByteBuffer.wrap(zipBytes).order(ByteOrder.LITTLE_ENDIAN)
    var eocd = -1
    for (i in zipBytes.size - 22 downTo 0) if (bb.getInt(i) == 0x06054b50) { eocd = i; break }
    val cdOffset = bb.getInt(eocd + 16)
    val out = zipBytes.copyOfRange(0, cdOffset) + block + zipBytes.copyOfRange(cdOffset, zipBytes.size)
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd + block.size + 16, cdOffset + block.size)
    return out
}

/** A fake GitHub API + asset host serving one release. Mutable so tests can tweak what is served. */
class FakeGitHub(
    var version: String = "1.0.1",
    var versionCode: Int = 10001,
    var apk: ByteArray = fakeSignedApk("CERT-A".toByteArray()),
    var manifestOverride: ((String) -> String)? = null,
) : AutoCloseable {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"
    var claimedCert: String = sha256Hex("CERT-A".toByteArray())
    var minSupported = 0
    var requests = mutableListOf<String>()

    fun manifest(): String {
        val m = """{"versionName":"$version","versionCode":$versionCode,"sha256":"${sha256Hex(apk)}","size":${apk.size},""" +
            """"signingCertSha256":"$claimedCert","minSupportedVersionCode":$minSupported,"notes":"修复若干问题","publishedAt":1760000000000}"""
        return manifestOverride?.invoke(m) ?: m
    }

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            requests += path + (if (ex.requestHeaders.getFirst("Authorization") != null) " [auth]" else "")
            val (code, body) = when {
                path == "/repos/o/r/releases" -> 200 to """[
                    {"tag_name":"v9.9.9","draft":true,"prerelease":false,"assets":[]},
                    {"tag_name":"v$version","draft":false,"prerelease":false,"assets":[
                      {"name":"release.json","url":"$base/api-asset/json","browser_download_url":"$base/dl/release.json","size":1},
                      {"name":"couple-$version.apk","url":"$base/api-asset/apk","browser_download_url":"$base/dl/couple-$version.apk","size":${apk.size}}
                    ]}]""".toByteArray()
                path == "/dl/release.json" || path == "/api-asset/json" -> 200 to manifest().toByteArray()
                path == "/dl/couple-$version.apk" || path == "/api-asset/apk" -> {
                    ex.responseHeaders.add("Location", "$base/blob/apk")
                    302 to ByteArray(0)
                }
                path == "/blob/apk" -> 200 to apk
                else -> 404 to "{}".toByteArray()
            }
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
