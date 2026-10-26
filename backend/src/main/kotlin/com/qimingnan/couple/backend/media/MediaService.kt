package com.qimingnan.couple.backend.media

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.MediaObjects
import com.qimingnan.couple.backend.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@Serializable
data class MediaView(
    val storageKey: String,
    val thumbKey: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
)

/** One row of media_objects. */
data class MediaObject(
    val storageKey: String,
    val coupleId: String,
    val purpose: String,
    val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val thumbKey: String?,
    val usedByPost: String?,
    val createdAt: Long,
)

/** Persistence for media_objects. Must be called inside a transaction. */
object MediaRepo {
    private fun toObject(r: ResultRow) = MediaObject(
        storageKey = r[MediaObjects.storageKey], coupleId = r[MediaObjects.coupleId], purpose = r[MediaObjects.purpose],
        mimeType = r[MediaObjects.mimeType], size = r[MediaObjects.size], width = r[MediaObjects.width],
        height = r[MediaObjects.height], thumbKey = r[MediaObjects.thumbKey], usedByPost = r[MediaObjects.usedByPost],
        createdAt = r[MediaObjects.createdAt],
    )

    fun byKey(key: String): MediaObject? =
        MediaObjects.selectAll().where { MediaObjects.storageKey eq key }.firstOrNull()?.let(::toObject)

    fun insert(o: MediaObject) {
        MediaObjects.insert {
            it[storageKey] = o.storageKey
            it[coupleId] = o.coupleId
            it[purpose] = o.purpose
            it[mimeType] = o.mimeType
            it[size] = o.size
            it[width] = o.width
            it[height] = o.height
            it[thumbKey] = o.thumbKey
            it[usedByPost] = o.usedByPost
            it[createdAt] = o.createdAt
        }
    }

    fun markUsed(key: String, postId: String) {
        MediaObjects.update({ MediaObjects.storageKey eq key }) { it[usedByPost] = postId }
    }

    /** Unreferenced post uploads older than [ts] (the orphan sweep's candidates). */
    fun orphansBefore(ts: Long): List<MediaObject> =
        MediaObjects.selectAll().where {
            (MediaObjects.purpose eq "post") and MediaObjects.usedByPost.isNull() and (MediaObjects.createdAt less ts)
        }.map(::toObject)

    fun deleteRow(key: String) {
        MediaObjects.deleteWhere { MediaObjects.storageKey eq key }
    }

    fun setPurposeAvatar(key: String, now: Long) {
        MediaObjects.update({ MediaObjects.storageKey eq key }) { it[purpose] = "avatar"; it[createdAt] = now }
    }

    /** Accepts either the original's key or its thumbnail's key. */
    fun byKeyOrThumbKey(key: String): MediaObject? =
        byKey(key) ?: MediaObjects.selectAll().where { MediaObjects.thumbKey eq key }.firstOrNull()?.let(::toObject)
}

/** Image ingest: whitelisted types, dimension probe, EXIF strip (JPEG/PNG re-encode), server-side thumbnail. */
object Images {
    val ALLOWED = setOf("image/jpeg", "image/png", "image/webp")
    const val MAX_BYTES = 10L * 1024 * 1024
    const val THUMB_MAX_PX = 512

    /** Header-only read; throws VALIDATION_ERROR when the bytes are not an image. */
    fun probe(bytes: ByteArray): Pair<Int, Int> {
        val iis = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReaders(iis).takeIf { it.hasNext() }?.next()
            ?: throw badRequest("VALIDATION_ERROR", "not a readable image")
        reader.input = iis
        try {
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            if (w <= 0 || h <= 0 || w > 20_000 || h > 20_000) throw badRequest("VALIDATION_ERROR", "image dimensions out of range")
            return w to h
        } finally {
            reader.dispose()
            iis.close()
        }
    }

    /**
     * JPEG and PNG are re-encoded, which drops all metadata (EXIF/GPS included); WebP has no ImageIO reader on the
     * JVM and is stored as-is (documented in the contract).
     */
    fun stripMetadata(bytes: ByteArray, mimeType: String): ByteArray {
        if (mimeType !in setOf("image/jpeg", "image/png")) return bytes
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        val out = ByteArrayOutputStream()
        val ok = ImageIO.write(img, if (mimeType == "image/png") "png" else "jpg", out)
        return if (ok) out.toByteArray() else bytes
    }

    /** ≤512 px JPEG preview; null when the original is small enough or the format has no writer. */
    fun thumbnail(bytes: ByteArray, mimeType: String): ByteArray? {
        if (mimeType == "image/webp") return null
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val maxSide = maxOf(img.width, img.height)
        if (maxSide <= THUMB_MAX_PX) return null
        val scale = THUMB_MAX_PX.toDouble() / maxSide
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val thumb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = thumb.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        return if (ImageIO.write(thumb, "jpg", out)) out.toByteArray() else null
    }
}

class MediaService(
    private val db: Db,
    private val config: AppConfig,
    private val storage: StorageManager,
) {
    private val log = LoggerFactory.getLogger(MediaService::class.java)

    /** The caller's couple (DB is the source of truth, never the JWT claim); null when unpaired. */
    suspend fun coupleIdOf(userId: String): String? =
        db.tx { Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.coupleId) }

    /** Uploads one image (bytes already read from the multipart part). */
    suspend fun upload(caller: AuthUser, coupleId: String?, fileName: String?, contentType: String?, bytes: ByteArray): MediaView {
        val mime = normalizeMime(contentType, fileName)
        if (mime !in Images.ALLOWED) throw badRequest("VALIDATION_ERROR", "only ${Images.ALLOWED} are accepted")
        if (bytes.isEmpty()) throw badRequest("VALIDATION_ERROR", "empty upload")
        if (bytes.size > Images.MAX_BYTES) throw badRequest("VALIDATION_ERROR", "image too large (max 10 MB)")
        val cid = coupleId ?: throw forbidden("NOT_PAIRED", "media uploads need a couple")
        val (w, h) = Images.probe(bytes)
        val cleaned = Images.stripMetadata(bytes, mime)
        val thumb = Images.thumbnail(cleaned, mime)
        val key = Ids.newId()
        storage.put(key, mime, cleaned)
        var thumbKey: String? = null
        if (thumb != null) {
            thumbKey = Ids.newId()
            storage.put(thumbKey, "image/jpeg", thumb)
        }
        val now = config.clock()
        val size = cleaned.size.toLong()
        db.tx { MediaRepo.insert(MediaObject(key, cid, "post", mime, size, w, h, thumbKey, null, now)) }
        return MediaView(key, thumbKey, mime, w, h, size)
    }

    /**
     * Server-side ingest of a downloaded chat sticker: same probe / EXIF strip / thumbnail as uploads, GIF allowed
     * (kept as-is so animation survives), and the object is marked used by [messageId] right away.
     */
    suspend fun ingestSticker(coupleId: String, mimeType: String, bytes: ByteArray): MediaView {
        val mime = mimeType.lowercase()
        if (mime !in Images.ALLOWED && mime != "image/gif") throw badRequest("STICKER_REJECTED", "unsupported sticker type $mime")
        if (bytes.isEmpty() || bytes.size > Images.MAX_BYTES) throw badRequest("STICKER_REJECTED", "bad sticker size")
        val (w, h) = Images.probe(bytes)
        val cleaned = Images.stripMetadata(bytes, mime)
        val key = Ids.newId()
        storage.put(key, mime, cleaned)
        val now = config.clock()
        db.tx { MediaRepo.insert(MediaObject(key, coupleId, "post", mime, cleaned.size.toLong(), w, h, null, null, now)) }
        return MediaView(key, null, mime, w, h, cleaned.size.toLong())
    }

    /** 复制一个媒体对象（文件 + 缩略图 + 行），新对象未被占用。重复的定时消息每次发送用一份副本。 */
    suspend fun duplicate(key: String): MediaView? {
        val o = db.tx { MediaRepo.byKey(key) } ?: return null
        val bytes = storage.get(o.storageKey) ?: return null
        val newKey = Ids.newId()
        storage.put(newKey, o.mimeType, bytes)
        val newThumb = o.thumbKey?.let { tk ->
            storage.get(tk)?.let { tb -> Ids.newId().also { storage.put(it, "image/jpeg", tb) } }
        }
        val now = config.clock()
        db.tx { MediaRepo.insert(o.copy(storageKey = newKey, thumbKey = newThumb, usedByPost = null, createdAt = now)) }
        return MediaView(newKey, newThumb, o.mimeType, o.width, o.height, o.size)
    }

    /** Deletes a media object (row + files), e.g. when a chat image is recalled. */
    suspend fun deleteObject(key: String) {
        val o = db.tx { MediaRepo.byKey(key)?.also { MediaRepo.deleteRow(key) } } ?: return
        runCatching { storage.delete(o.storageKey); o.thumbKey?.let { storage.delete(it) } }
    }

    /** The bytes of a media object after the ACL check (contract: same couple, or an avatar of the couple). */
    suspend fun bytesForReading(caller: AuthUser, key: String, thumb: Boolean): Pair<MediaObject, ByteArray> {
        val o = db.tx { MediaRepo.byKeyOrThumbKey(key) } ?: throw notFound("MEDIA_NOT_FOUND", "no media $key")
        val myCouple = coupleIdOf(caller.userId)
        val allowed = when (o.purpose) {
            "avatar" -> myCouple != null && db.tx { avatarInCouple(key, myCouple) }
            else -> myCouple != null && o.coupleId == myCouple
        }
        if (!allowed) throw forbidden("MEDIA_FORBIDDEN", "media belongs to another couple")
        val wantKey = if (thumb) (o.thumbKey ?: key) else key
        val bytes = storage.get(wantKey) ?: throw notFound("MEDIA_NOT_FOUND", "media file missing: $wantKey")
        return o to bytes
    }

    /** Sets the caller's avatar; deletes the previous avatar object (row + file). */
    suspend fun setAvatar(caller: AuthUser, storageKey: String) {
        val o = db.tx { MediaRepo.byKey(storageKey) } ?: throw notFound("MEDIA_NOT_FOUND", "no media $storageKey")
        if (o.coupleId != coupleIdOf(caller.userId)) throw forbidden("MEDIA_FORBIDDEN", "media belongs to another couple")
        val now = config.clock()
        val old = db.tx {
            val prev = Users.selectAll().where { Users.id eq caller.userId }.firstOrNull()?.get(Users.avatarUrl)
            Users.update({ Users.id eq caller.userId }) { it[avatarUrl] = "/api/v1/media/$storageKey" }
            prev?.removePrefix("/api/v1/media/")
        }
        if (old != null && old != storageKey) {
            db.tx {
                val prev = MediaRepo.byKey(old)
                if (prev != null && prev.purpose == "avatar") MediaRepo.deleteRow(old)
            }
            runCatching { storage.delete(old) }
        }
        // An upload promoted to avatar outlives the orphan sweep.
        db.tx { MediaRepo.setPurposeAvatar(storageKey, now) }
    }

    /** Deletes unreferenced uploads older than 24 h (files + rows). Returns deleted keys. */
    suspend fun orphanSweep(): List<String> {
        val cutoff = config.clock() - 24 * 60 * 60_000L
        val stale = db.tx { MediaRepo.orphansBefore(cutoff) }
        val deleted = mutableListOf<String>()
        for (o in stale) {
            db.tx { MediaRepo.deleteRow(o.storageKey) }
            runCatching {
                storage.delete(o.storageKey)
                o.thumbKey?.let { storage.delete(it) }
            }.onFailure { log.warn("orphan sweep: could not delete files for {}: {}", o.storageKey, it.message) }
            deleted += o.storageKey
        }
        if (deleted.isNotEmpty()) log.info("orphan sweep deleted {} uploads", deleted.size)
        return deleted
    }

    /** True when the media key is the current avatar of a member of [coupleId]. */
    private fun avatarInCouple(key: String, coupleId: String): Boolean {
        val row = Couples.selectAll().where { (Couples.id eq coupleId) and Couples.deletedAt.isNull() }.firstOrNull()
            ?: return false
        val urls = listOf(row[Couples.userA], row[Couples.userB])
            .mapNotNull { uid -> Users.selectAll().where { Users.id eq uid }.firstOrNull()?.get(Users.avatarUrl) }
        return urls.any { it == "/api/v1/media/$key" }
    }

    private fun normalizeMime(contentType: String?, fileName: String?): String {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (ct != null && ct != "application/octet-stream") return ct
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> ct ?: "application/octet-stream"
        }
    }
}
