package com.qimingnan.couple.data.update

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resumable APK download (HTTP Range + If-Range on the sha256 ETag the backend sends), then size + sha256
 * verification. A partial file survives process death / WorkManager retries as `<dest>.part`; the expected sha256 is
 * stored next to it so a different release never resumes onto stale bytes.
 * Pure JVM (OkHttp only) so it is unit-tested with MockWebServer.
 */
class ApkDownloader(private val client: OkHttpClient) {

    /** [retryable] = worth retrying later (network trouble); otherwise the file itself is bad. */
    class DownloadException(message: String, val retryable: Boolean) : IOException(message)

    fun download(
        url: HttpUrl,
        dest: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isStopped: () -> Boolean = { false },
    ): File {
        val sha = UpdateLogic.normalizeSha256(expectedSha256) ?: throw DownloadException("无效的 sha256", retryable = false)
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() == expectedSize && UpdateLogic.sha256Hex(dest) == sha) return dest

        val part = File(dest.path + ".part")
        val marker = File(dest.path + ".part.sha256")
        if (!marker.isFile || marker.readText().trim() != sha || part.length() > expectedSize) {
            part.delete()
            marker.writeText(sha)
        }

        var attemptedFresh = false
        while (true) {
            val have = if (part.isFile) part.length() else 0L
            if (have < expectedSize) {
                val req = Request.Builder().url(url).apply {
                    if (have > 0) {
                        header("Range", "bytes=$have-")
                        header("If-Range", "\"$sha\"")
                    }
                }.build()
                val resp = try {
                    client.newCall(req).execute()
                } catch (e: IOException) {
                    throw DownloadException("下载失败：${e.message ?: "网络错误"}", retryable = true)
                }
                var restart = false
                resp.use { r ->
                    when {
                        r.code == 416 && !attemptedFresh -> {
                            part.delete(); attemptedFresh = true; restart = true
                        }
                        r.code == 206 && have > 0 -> {
                            val start = r.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                            if (start != have) {
                                part.delete()
                                if (attemptedFresh) throw DownloadException("服务器返回的断点位置不正确", retryable = true)
                                attemptedFresh = true
                                restart = true
                            } else copy(r.body?.byteStream(), part, append = true, have, expectedSize, onProgress, isStopped)
                        }
                        r.code == 200 -> copy(r.body?.byteStream(), part, append = false, 0, expectedSize, onProgress, isStopped)
                        r.code == 404 -> throw DownloadException("版本已撤回或不存在", retryable = false)
                        r.code == 401 || r.code == 403 -> throw DownloadException("没有下载权限（登录已失效？）", retryable = false)
                        else -> throw DownloadException("下载失败：HTTP ${r.code}", retryable = r.code >= 500 || r.code == 429)
                    }
                }
                if (restart) continue
                if (!part.isFile || part.length() < expectedSize) throw DownloadException("下载中断，稍后继续", retryable = true)
            }
            break
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
        input: java.io.InputStream?,
        part: File,
        append: Boolean,
        startAt: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isStopped: () -> Boolean,
    ) {
        input ?: throw DownloadException("空响应", retryable = true)
        var done = startAt
        var lastReport = 0L
        try {
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isStopped()) throw DownloadException("下载已暂停", retryable = true)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (done + n > total) throw DownloadException("安装包比声明的大小更大", retryable = false).also { part.delete() }
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 256 * 1024 || done == total) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            throw DownloadException("下载中断：${e.message ?: "网络错误"}", retryable = true)
        }
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
