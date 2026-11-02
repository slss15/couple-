package com.qimingnan.couple.data.chat

import android.content.Context
import android.net.Uri
import com.qimingnan.couple.data.net.ChatMessage
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.parseErrorBody
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 导出格式：文件扩展名 + SAF CreateDocument 用的 MIME。 */
enum class ChatExportFormat(val query: String, val mime: String, val label: String) {
    JSON("json", "application/json", "JSON（含图片链接）"),
    TXT("txt", "text/plain", "TXT（纯文本）"),
}

/** 与后端 ChatExportService 同一套格式规则（演示后端也用它），纯函数便于 JVM 单测。 */
object ChatExportText {
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** "2026-10-01 12:00 我: text"；撤回 [已撤回]、图片 [图片]、表情 [表情]；多行文本的续行以制表符缩进。 */
    fun txtLine(m: ChatMessage, me: String, zone: ZoneId): String {
        val who = if (m.senderId == me) "我" else "TA"
        val body = when {
            m.recalledAt != null -> "[已撤回]"
            m.type == "image" -> "[图片]"
            m.type == "sticker" -> "[表情]"
            else -> m.text.replace("\r\n", "\n").replace("\n", "\n\t")
        }
        return "${TS.format(Instant.ofEpochMilli(m.createdAt).atZone(zone))} $who: $body"
    }

    fun jsonItem(m: ChatMessage, me: String): JsonObject = buildJsonObject {
        put("id", m.id); put("senderId", m.senderId); put("mine", m.senderId == me); put("type", m.type); put("text", m.text)
        put("replyTo", m.replyTo); put("clientTs", m.clientTs); put("createdAt", m.createdAt); put("recalledAt", m.recalledAt)
        put("mediaId", m.mediaId); put("width", m.width); put("height", m.height)
        put("mediaUrl", m.mediaId?.let { "/api/v1/media/$it" })
        put("thumbUrl", m.mediaId?.takeIf { m.type == "image" }?.let { "/api/v1/media/$it/thumb" })
    }

    fun fileName(format: ChatExportFormat, now: Long, zone: ZoneId): String =
        "couple-chat-${DAY.format(Instant.ofEpochMilli(now).atZone(zone))}.${format.query}"

    /** 导出失败的中文提示。 */
    fun errorMessage(status: Int, body: String?, retryAfter: String?): String {
        val err = parseErrorBody(status, body)
        return when {
            status == 429 || err.code == "RATE_LIMITED" ->
                retryAfter?.toLongOrNull()?.let { "导出太频繁，请 $it 秒后再试" } ?: "导出太频繁，请稍后再试"
            err.code == "NOT_PAIRED" -> "还没有配对，没有聊天记录可导出"
            err.code == "UNAUTHORIZED" -> "登录已失效，请重新登录"
            else -> "导出失败：${err.message}"
        }
    }
}

/**
 * 聊天记录导出：先把服务器的流式响应下载到 cacheDir/export（失败时不会留下空文件），
 * 再由 UI 通过 SAF CreateDocument 让用户选位置，最后复制过去并删掉缓存。
 */
@Singleton
class ChatExportRepository @Inject constructor(
    private val api: CoupleApi,
    @ApplicationContext private val context: Context,
) {
    data class Downloaded(val file: File, val format: ChatExportFormat, val suggestedName: String)

    suspend fun download(format: ChatExportFormat, zone: ZoneId = ZoneId.systemDefault()): Result<Downloaded> = try {
        val r = api.exportChat(format.query, zone.id)
        if (!r.isSuccessful) {
            Result.failure(IllegalStateException(ChatExportText.errorMessage(r.code(), r.errorBody()?.string(), r.headers()["Retry-After"])))
        } else {
            val name = ChatExportText.fileName(format, System.currentTimeMillis(), zone)
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // 只保留最新一份
                val f = File(dir, name)
                val body = r.body() ?: throw IOException("空响应")
                body.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            }
            Result.success(Downloaded(file, format, name))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Result.failure(IllegalStateException("网络不可用，导出失败", e))
    } catch (e: Exception) {
        Result.failure(IllegalStateException("导出失败：${e.message ?: e.javaClass.simpleName}", e))
    }

    /** 复制到用户在 SAF 里选的位置；无论成败都删掉缓存。 */
    suspend fun saveTo(d: Downloaded, uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置")
            val n = out.use { o -> d.file.inputStream().use { it.copyTo(o) } }
            Result.success(n)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("保存失败：${e.message ?: e.javaClass.simpleName}", e))
        } finally {
            d.file.delete()
        }
    }

    fun discard(d: Downloaded) { d.file.delete() }
}
