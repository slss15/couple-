package com.qimingnan.couple.backend.setup

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

/** Public URLs derived from the incoming request; a suggestion only, never used for security decisions. */
@Serializable
data class SuggestedUrls(val publicBaseUrl: String, val mqttUrl: String)

private val HOST = Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*|\\[[0-9A-Fa-f:.]+])(?::[0-9]{1,5})?$")

private fun firstValue(v: String?): String? = v?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Derives the public REST base and MQTT WSS URL from forwarded headers (Cloudflare / cloudflared / nginx):
 * scheme = X-Forwarded-Proto (http|https) else the request scheme; host = X-Forwarded-Host else Host.
 * publicBaseUrl = scheme://host, mqttUrl = wss://host + [mqttPath] (the app always uses MQTT over WSS).
 * Returns null when no plausible host is present.
 */
fun deriveUrls(forwardedProto: String?, requestScheme: String, forwardedHost: String?, hostHeader: String?, mqttPath: String = "/mqtt"): SuggestedUrls? {
    val proto = firstValue(forwardedProto)?.lowercase()?.takeIf { it == "http" || it == "https" }
        ?: requestScheme.lowercase().takeIf { it == "http" || it == "https" } ?: "https"
    val host = (firstValue(forwardedHost) ?: firstValue(hostHeader))?.lowercase() ?: return null
    if (host.length > 260 || !HOST.matches(host)) return null
    val path = "/" + mqttPath.trim('/')
    return SuggestedUrls(publicBaseUrl = "$proto://$host", mqttUrl = "wss://$host$path")
}

fun ApplicationCall.suggestedUrls(mqttPath: String = "/mqtt"): SuggestedUrls? = deriveUrls(
    forwardedProto = request.headers["X-Forwarded-Proto"],
    requestScheme = request.local.scheme,
    forwardedHost = request.headers["X-Forwarded-Host"],
    hostHeader = request.headers["Host"],
    mqttPath = mqttPath,
)
