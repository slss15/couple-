package com.qimingnan.couple.data.bootstrap

import java.net.URI
import java.security.MessageDigest

/**
 * Bootstrap public key fingerprint: SHA-256 of the raw 32-byte Ed25519 key, first 8 bytes (the same bytes the admin
 * dashboard shows), as lowercase hex in groups of 4 so two people can read it to each other: `ab12 cd34 ef56 7890`.
 */
object KeyFingerprint {
    const val BYTES = 8

    fun of(rawKey: ByteArray): String = format(MessageDigest.getInstance("SHA-256").digest(rawKey).copyOf(BYTES))

    fun format(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")

    /** Accepts any separator/case ("AB:12:cd…", "ab12cd…") so fingerprints from different screens compare equal. */
    fun normalize(fp: String): String = fp.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
}

/** Host part we trust: lowercase host, plus the port when it is not the scheme default. */
object TrustedHost {
    fun of(url: String): String? = runCatching {
        val u = URI(url.trim())
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val defaultPort = when (u.scheme?.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> -1
        }
        if (u.port == -1 || u.port == defaultPort) host else "$host:${u.port}"
    }.getOrNull()
}

/** How a scanned/entered config relates to what this phone already trusts. */
sealed interface TrustCheck {
    /** Nothing trusted yet (first setup, or trust data from an older app version is missing). */
    data object FirstTime : TrustCheck
    data object Same : TrustCheck
    data class HostChanged(val oldHost: String, val newHost: String) : TrustCheck
    data class KeyChanged(val oldFingerprint: String, val newFingerprint: String) : TrustCheck
    data class HostAndKeyChanged(
        val oldHost: String,
        val newHost: String,
        val oldFingerprint: String,
        val newFingerprint: String,
    ) : TrustCheck

    /** A change from what is trusted: needs the strong warning and an explicit confirmation. */
    val needsExplicitConfirm: Boolean get() = this !is FirstTime && this !is Same
    val keyChanged: Boolean get() = this is KeyChanged || this is HostAndKeyChanged

    companion object {
        /**
         * @param newFingerprint null for an unsigned manual (debug) config, which cannot change the key.
         */
        fun evaluate(trustedHost: String?, trustedFingerprint: String?, newHost: String, newFingerprint: String?): TrustCheck {
            if (trustedHost == null && trustedFingerprint == null) return FirstTime
            val hostChanged = trustedHost != null && trustedHost != newHost
            val keyChanged = trustedFingerprint != null && newFingerprint != null &&
                KeyFingerprint.normalize(trustedFingerprint) != KeyFingerprint.normalize(newFingerprint)
            return when {
                hostChanged && keyChanged -> HostAndKeyChanged(trustedHost!!, newHost, trustedFingerprint!!, newFingerprint!!)
                hostChanged -> HostChanged(trustedHost!!, newHost)
                keyChanged -> KeyChanged(trustedFingerprint!!, newFingerprint!!)
                else -> Same
            }
        }
    }
}

/** What the confirm page shows before anything is stored. */
data class BootstrapPreview(
    val baseUrl: String,
    val mqttUrl: String,
    val host: String,
    /** null for an unsigned manual config */
    val fingerprint: String?,
    val configVersion: Long?,
    val signed: Boolean,
    val trust: TrustCheck,
) {
    fun confirmation(acceptChange: Boolean) = TrustConfirmation(host, fingerprint, acceptChange)
}

/**
 * What the user confirmed on the confirm page. Applying re-checks the config against it, so a server that swaps its
 * key between preview and apply is refused.
 */
data class TrustConfirmation(val host: String, val fingerprint: String?, val acceptChange: Boolean)
