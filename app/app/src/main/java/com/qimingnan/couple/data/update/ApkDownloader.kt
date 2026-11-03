package com.qimingnan.couple.data.update

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resumable APK download (HTTP Range; If-Range on the sha256 ETag only towards our own server), then size + sha256
 * verification. A partial file survives process death / WorkManager retries as `<dest>.part`; the expected sha256 is
 * stored next to it so a different release never resumes onto stale bytes.
 *
 * Multi-mirror ([downloadFrom]): the mirrors come ranked by [MirrorRace]; when the current one fails or stalls
 * ([StallDetector]) the download continues on the next-fastest from the current offset. The bytes may therefore come
 * from several hosts — that is safe because the final size + sha256 check (values from the signed manifest) is
 * mandatory and a mismatch deletes the file.
 * Pure JVM (OkHttp / [RangeTransport]) so it is unit-tested with MockWebServer and a fake transport.
 */
class ApkDownloader(private val transport: RangeTransport, private val clock: MonoClock = MonoClock.SYSTEM) {

    /** Single-client convenience (same client for authenticated and public mirrors). */
    constructor(client: OkHttpClient) : this(OkHttpRangeTransport(authenticated = client, public = client))

    /** [retryable] = worth retrying later (network trouble); otherwise the file itself is bad. */
    class DownloadException(message: String, val retryable: Boolean) : IOException(message)

    /** Switch to the next mirror (not fatal for the whole download). */
    private class MirrorFailed(message: String) : IOException(message)

    fun download(
        url: HttpUrl,
        dest: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isStopped: () -> Boolean = { false },
    ): File = downloadFrom(listOf(Probe(Mirror(url.toString(), authenticated = true), 0.0, true)), dest, expectedSize, expectedSha256, onProgress, isStopped)

    /**
     * Downloads from [ranked] mirrors in order. With a single mirror the errors are reported as before (404 = withdrawn,
     * 401/403 = no permission, ...). With several, a mirror-level failure moves on to the next mirror; only when every
     * mirror failed is a (retryable) error thrown, and the worker re-races later with backoff.
     */
    fun downloadFrom(
        ranked: List<Probe>,
        dest: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isStopped: () -> Boolean = { false },
        onSwitch: (from: Mirror, reason: String) -> Unit = { _, _ -> },
    ): File {
        val sha = UpdateLogic.normalizeSha256(expectedSha256) ?: throw DownloadException("无效的 sha256", retryable = false)
        if (ranked.isEmpty()) throw DownloadException("没有可用的下载地址", retryable = false)
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() == expectedSize && UpdateLogic.sha256Hex(dest) == sha) return dest

        val part = File(dest.path + ".part")
        val marker = File(dest.path + ".part.sha256")
        if (!marker.isFile || marker.readText().trim() != sha || part.length() > expectedSize) {
            part.delete()
            marker.writeText(sha)
        }
        // Reuse the winner's probe bytes as the start of a fresh download (saves one round trip on slow links).
        ranked.first().head?.takeIf { (part.length() == 0L) && ranked.first().rangeOk && it.size <= expectedSize }?.let { part.writeBytes(it) }

        val single = ranked.size == 1
        var idx = 0
        var attemptedFresh = false
        var lastError = "下载中断，稍后继续"
        while ((if (part.isFile) part.length() else 0L) < expectedSize) {
            if (idx >= ranked.size) throw DownloadException(if (single) lastError else "所有下载镜像都失败了（$lastError），稍后重试", retryable = true)
            val probe = ranked[idx]
            val m = probe.mirror
            val have = if (part.isFile) part.length() else 0L
            try {
                val resp = try {
                    transport.open(m, have, null, if (have > 0 && m.authenticated) "\"$sha\"" else null)
                } catch (e: IOException) {
                    throw MirrorFailed("下载失败：${e.message ?: "网络错误"}")
                }
                var restart = false
                resp.use { r ->
                    when {
                        r.code == 416 && !attemptedFresh -> {
                            part.delete(); attemptedFresh = true; restart = true
                        }
                        r.code == 206 && have > 0 -> {
                            if (r.contentRangeStart != have) {
                                if (!single) throw MirrorFailed("镜像返回的断点位置不正确")
                                part.delete()
                                if (attemptedFresh) throw DownloadException("服务器返回的断点位置不正确", retryable = true)
                                attemptedFresh = true
                                restart = true
                            } else copy(r, part, append = true, have, expectedSize, probe.bytesPerSec, onProgress, isStopped)
                        }
                        r.code == 200 || (r.code == 206 && have == 0L && (r.contentRangeStart ?: 0L) == 0L) ->
                            copy(r, part, append = false, 0, expectedSize, probe.bytesPerSec, onProgress, isStopped)
                        // Our own server is authoritative about withdrawal / auth; a third-party mirror's 404 is not.
                        r.code == 404 && m.authenticated -> throw DownloadException("版本已撤回或不存在", retryable = false)
                        (r.code == 401 || r.code == 403) && m.authenticated -> throw DownloadException("没有下载权限（登录已失效？）", retryable = false)
                        single -> throw DownloadException("下载失败：HTTP ${r.code}", retryable = r.code >= 500 || r.code == 429 || r.code == 404 || r.code == 403)
                        else -> throw MirrorFailed("HTTP ${r.code}")
                    }
                }
                if (restart) continue
                if (!part.isFile || part.length() < expectedSize) throw MirrorFailed("下载中断，稍后继续")
            } catch (e: MirrorFailed) {
                lastError = e.message ?: "下载失败"
                if (single && e.message?.startsWith("下载") == true) throw DownloadException(lastError, retryable = true)
                onSwitch(m, lastError)
                idx++
            }
        }

        if (part.length() != expectedSize) {
            part.delete()
            throw DownloadException("安装包大小不符（${part.length()} ≠ $expectedSize）", retryable = false)
        }
        val actual = UpdateLogic.sha256Hex(part)
        if (actual != sha) {
            part.delete()
            marker.delete()
            throw DownloadException("安装包校验失败（sha256 不符），已删除", retryable = false)
        }
        dest.delete()
        if (!part.renameTo(dest)) throw DownloadException("无法保存安装包", retryable = true)
        marker.delete()
        return dest
    }

    private fun copy(
        r: RangeResponse,
        part: File,
        append: Boolean,
        startAt: Long,
        total: Long,
        probeBytesPerSec: Double,
        onProgress: (Long, Long) -> Unit,
        isStopped: () -> Boolean,
    ) {
        var done = startAt
        var lastReport = 0L
        val stall = StallDetector(probeBytesPerSec, clock)
        try {
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isStopped()) throw DownloadException("下载已暂停", retryable = true)
                    val n = r.read(buf)
                    if (n < 0) break
                    if (done + n > total) throw DownloadException("安装包比声明的大小更大", retryable = false).also { part.delete() }
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 256 * 1024 || done == total) {
                        lastReport = done
                        onProgress(done, total)
                    }
                    if (done < total && stall.onRead(n)) throw MirrorFailed("下载变慢：${stall.reason}")
                }
            }
        } catch (e: DownloadException) {
            throw e
        } catch (e: MirrorFailed) {
            throw e
        } catch (e: IOException) {
            // Includes the read timeout (≈15 s without a byte) → stalled → next mirror, resuming from `done`.
            throw MirrorFailed("下载中断：${e.message ?: "网络错误"}")
        }
    }
}

/**
 * OkHttp implementation of [RangeTransport]. [authenticated] carries the access token (same-origin requests only);
 * [public] is token-free and used for every other mirror, so the token can never leak to a third-party host.
 */
class OkHttpRangeTransport(private val authenticated: OkHttpClient, private val public: OkHttpClient) : RangeTransport {
    override fun open(mirror: Mirror, from: Long, toInclusive: Long?, ifRange: String?): RangeResponse {
        val req = Request.Builder().url(mirror.url).apply {
            if (from > 0 || toInclusive != null) header("Range", "bytes=$from-${toInclusive ?: ""}")
            if (ifRange != null && mirror.authenticated) header("If-Range", ifRange)
        }.build()
        val resp = (if (mirror.authenticated) authenticated else public).newCall(req).execute()
        return OkHttpRangeResponse(resp)
    }

    private class OkHttpRangeResponse(private val resp: Response) : RangeResponse {
        private val stream = resp.body?.byteStream()
        override val code: Int get() = resp.code
        override val contentRangeStart: Long? =
            if (resp.code == 206) resp.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() } else null
        override fun read(buf: ByteArray): Int = stream?.read(buf) ?: throw IOException("空响应")
        override fun close() = resp.close()
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
