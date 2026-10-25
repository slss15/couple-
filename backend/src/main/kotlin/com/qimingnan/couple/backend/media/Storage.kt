package com.qimingnan.couple.backend.media

import com.qimingnan.couple.backend.config.AppConfig
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One uploaded object. [key] is a ULID; thumbnails share the namespace with a distinct key.
 */
data class StoredObject(val key: String, val bytes: ByteArray, val contentType: String)

/**
 * Object storage behind the media endpoints (contract "Media storage"). Buckets are always private —
 * bytes are only ever served through the backend's Bearer-authenticated proxy.
 */
interface StorageProvider {
    suspend fun put(key: String, contentType: String, bytes: ByteArray)
    /** Null when the object does not exist. */
    suspend fun get(key: String): ByteArray?
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean = get(key) != null
}

/** Local filesystem storage (Windows-native default): <mediaDir>/<key>, thumbnails under thumbs/. */
class LocalStorage(private val root: Path) : StorageProvider {
    init {
        Files.createDirectories(root)
    }

    private fun file(key: String): Path {
        val name = key.filter { it.isLetterOrDigit() }
        require(name.length == 26) { "bad storage key" }
        return root.resolve(name)
    }

    override suspend fun put(key: String, contentType: String, bytes: ByteArray) {
        val f = file(key)
        val tmp = f.resolveSibling(f.fileName.toString() + ".part")
        Files.write(tmp, bytes)
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun get(key: String): ByteArray? {
        val f = file(key)
        return if (Files.isRegularFile(f)) Files.readAllBytes(f) else null
    }

    override suspend fun delete(key: String) {
        Files.deleteIfExists(file(key))
    }
}

/**
 * Minimal S3-compatible client (MinIO / Cloudflare R2): PutObject / GetObject / DeleteObject with AWS SigV4,
 * no SDK dependency (self-use scale). HTTPS only; the secret never leaves the signing function.
 */
class S3Storage(
    private val endpoint: String,
    private val bucket: String,
    private val region: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
) : StorageProvider {

    init {
        require(endpoint.startsWith("https://")) { "storage.endpoint must be https" }
        require(bucket.isNotEmpty() && accessKeyId.isNotEmpty() && secretAccessKey.isNotEmpty()) { "storage config incomplete" }
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    private fun url(key: String): URI = URI.create("${endpoint.trimEnd('/')}/$bucket/${java.net.URLEncoder.encode(key, "UTF-8")}")

    override suspend fun put(key: String, contentType: String, bytes: ByteArray) {
        val res = request("PUT", url(key), bytes, contentType)
        if (res.statusCode() !in 200..299) throw StorageException("S3 PUT ${res.statusCode()}")
    }

    override suspend fun get(key: String): ByteArray? {
        val res = request("GET", url(key), null, null)
        return when (res.statusCode()) {
            in 200..299 -> res.body()
            404 -> null
            else -> throw StorageException("S3 GET ${res.statusCode()}")
        }
    }

    override suspend fun delete(key: String) {
        val res = request("DELETE", url(key), null, null)
        if (res.statusCode() !in 200..299 && res.statusCode() != 404) throw StorageException("S3 DELETE ${res.statusCode()}")
    }

    private fun request(method: String, uri: URI, body: ByteArray?, contentType: String?): HttpResponse<ByteArray> {
        val now = Instant.now()
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
        val dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now)
        val payloadHash = sha256Hex(body ?: ByteArray(0))
        val path = uri.rawPath
        val query = uri.rawQuery ?: ""
        val canonical = buildString {
            append(method).append('\n')
            append(path).append('\n')
            append(query).append('\n')
            append("host:").append(uri.authority).append('\n')
            append("x-amz-content-sha256:").append(payloadHash).append('\n')
            append("x-amz-date:").append(amzDate).append('\n')
            append('\n')
            append("host;x-amz-content-sha256;x-amz-date").append('\n')
            append(payloadHash)
        }
        val scope = "$dateStamp/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val signingKey = hmac(hmac(hmac(hmac(("AWS4$secretAccessKey").toByteArray(), dateStamp), region), "s3"), "aws4_request")
        val signature = hex(hmac(signingKey, toSign))
        val b = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature")
        when (method) {
            "PUT" -> b.header("Content-Type", contentType ?: "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0)))
            "DELETE" -> b.DELETE()
            else -> b.GET()
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {
        fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.toByteArray()) }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun sha256Hex(bytes: ByteArray) = hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

        /** Builds the provider from the admin config's `storage` section; null when unconfigured/unsupported. */
        fun fromStorageSection(config: AppConfig, storageJson: kotlinx.serialization.json.JsonObject?): StorageProvider? {
            if (storageJson == null) return null
            val provider = storageJson["provider"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return null
            if (provider == "local") {
                val dir = config.mediaDir ?: return null
                return LocalStorage(File(dir).toPath())
            }
            fun str(k: String) = storageJson[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            val secret = when (val v = storageJson["secretAccessKey"]) {
                is kotlinx.serialization.json.JsonPrimitive -> if (v is kotlinx.serialization.json.JsonNull) "" else v.content
                else -> ""
            }
            val endpoint = str("endpoint")
            val bucket = str("bucket")
            val region = str("region").ifEmpty { "auto" }
            val accessKey = str("accessKeyId")
            if (endpoint.isEmpty() || bucket.isEmpty() || accessKey.isEmpty() || secret.isEmpty()) return null
            return S3Storage(endpoint, bucket, region, accessKey, secret)
        }
    }
}

class StorageException(msg: String) : RuntimeException(msg)

/**
 * Resolve the provider per call: admin config can change at runtime (provider/keys), so the storage section is
 * read on every operation through [storageSectionProvider]. Null provider (unconfigured) makes uploads fail with
 * STORAGE_NOT_CONFIGURED while reads of already-stored media still work for local files.
 */
class StorageManager(private val config: AppConfig, private val storageSectionProvider: suspend () -> kotlinx.serialization.json.JsonObject?) {
    private val localFallback: StorageProvider? by lazy {
        config.mediaDir?.let { LocalStorage(File(it).toPath()) }
    }

    suspend fun provider(): StorageProvider =
        S3Storage.fromStorageSection(config, storageSectionProvider())
            ?: localFallback
            ?: throw com.qimingnan.couple.backend.common.ApiException(
                io.ktor.http.HttpStatusCode.ServiceUnavailable,
                "STORAGE_NOT_CONFIGURED",
                "No storage provider configured (admin config storage section)",
            )

    suspend fun put(key: String, contentType: String, bytes: ByteArray) = provider().put(key, contentType, bytes)
    suspend fun get(key: String): ByteArray? = runCatching { provider().get(key) }.getOrNull()
    suspend fun delete(key: String) = runCatching { provider().delete(key) }
}
