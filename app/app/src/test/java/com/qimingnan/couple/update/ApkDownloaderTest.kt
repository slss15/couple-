package com.qimingnan.couple.update

import com.qimingnan.couple.data.update.ApkDownloader
import com.qimingnan.couple.data.update.UpdateLogic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ApkDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private val downloader = ApkDownloader(OkHttpClient())

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("dl").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun full() = MockResponse().setResponseCode(200).setBody(Buffer().write(body))
    private fun partial(from: Int) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
        .setBody(Buffer().write(body.copyOfRange(from, body.size)))

    @Test
    fun fullDownloadVerifies() {
        server.enqueue(full())
        val dest = File(dir, "a.apk")
        var last = 0L
        val f = downloader.download(server.url("/apk"), dest, body.size.toLong(), sha, onProgress = { d, _ -> last = d })
        assertArrayEquals(body, f.readBytes())
        assertEquals(body.size.toLong(), last)
        assertNull(server.takeRequest().getHeader("Range"))
        assertFalse(File(dest.path + ".part").exists())
        // Already present + valid: no request at all.
        downloader.download(server.url("/apk"), dest, body.size.toLong(), sha)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun resumesWithRangeAndIfRange() {
        val dest = File(dir, "b.apk")
        File(dest.path + ".part").writeBytes(body.copyOfRange(0, 100_000))
        File(dest.path + ".part.sha256").writeText(sha)
        server.enqueue(partial(100_000))
        val f = downloader.download(server.url("/apk"), dest, body.size.toLong(), sha)
        assertArrayEquals(body, f.readBytes())
        val req = server.takeRequest()
        assertEquals("bytes=100000-", req.getHeader("Range"))
        assertEquals("\"$sha\"", req.getHeader("If-Range"))
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() {
        val dest = File(dir, "c.apk")
        File(dest.path + ".part").writeBytes(ByteArray(5000) { 9 })
        File(dest.path + ".part.sha256").writeText(sha)
        server.enqueue(full()) // e.g. If-Range did not match -> whole file
        val f = downloader.download(server.url("/apk"), dest, body.size.toLong(), sha)
        assertArrayEquals(body, f.readBytes())
    }

    @Test
    fun partialFromDifferentReleaseIsDiscarded() {
        val dest = File(dir, "d.apk")
        File(dest.path + ".part").writeBytes(ByteArray(5000) { 1 })
        File(dest.path + ".part.sha256").writeText("0".repeat(64))
        server.enqueue(full())
        downloader.download(server.url("/apk"), dest, body.size.toLong(), sha)
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun shaMismatchDeletesFile() {
        server.enqueue(full())
        val dest = File(dir, "e.apk")
        try {
            downloader.download(server.url("/apk"), dest, body.size.toLong(), "1".repeat(64))
            fail("expected rejection")
        } catch (e: ApkDownloader.DownloadException) {
            assertFalse(e.retryable)
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun truncatedTransferIsRetryableAndKeepsPart() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body.copyOfRange(0, 1000))))
        val dest = File(dir, "f.apk")
        try {
            downloader.download(server.url("/apk"), dest, body.size.toLong(), sha)
            fail("expected interruption")
        } catch (e: ApkDownloader.DownloadException) {
            assertTrue(e.retryable)
        }
        assertEquals(1000L, File(dest.path + ".part").length())
        server.enqueue(partial(1000))
        assertArrayEquals(body, downloader.download(server.url("/apk"), dest, body.size.toLong(), sha).readBytes())
        assertEquals(sha, UpdateLogic.sha256Hex(dest))
    }

    @Test
    fun notFoundIsPermanent() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            downloader.download(server.url("/apk"), File(dir, "g.apk"), body.size.toLong(), sha)
            fail()
        } catch (e: ApkDownloader.DownloadException) {
            assertFalse(e.retryable)
        }
    }
}
