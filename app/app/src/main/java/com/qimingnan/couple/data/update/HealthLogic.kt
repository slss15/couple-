package com.qimingnan.couple.data.update

import kotlinx.serialization.Serializable

/** POST /app/health-report body (contract "Auto-update", docs/ota-design.md §5). */
@Serializable
data class HealthReport(
    val versionCode: Int,
    val fromVersionCode: Int? = null,
    /** OK | CRASH | FAILED */
    val status: String,
    val mqttConnected: Boolean? = null,
    val detail: String? = null,
    val protocolVersion: Int,
    val minPeerProtocol: Int,
    val sdkInt: Int,
)

/**
 * Post-install health state machine, kept pure for unit tests. Persisted state: [Pending] is written right before an
 * install is handed to the system; a crash marker (versionCode) is written by the uncaught-exception handler while a
 * pending update is being verified.
 */
object HealthLogic {
    /** First MQTT connect must happen within this window after the new version starts, otherwise FAILED. */
    const val CONNECT_TIMEOUT_MS = 10 * 60 * 1000L
    /** A pending install that never happened is forgotten after this long. */
    const val STALE_PENDING_MS = 7L * 24 * 60 * 60 * 1000

    data class Pending(val from: Int, val to: Int, val at: Long)

    enum class StartAction {
        /** Nothing pending. */
        NONE,
        /** Forget the pending record (installed something else / never installed). */
        CLEAR,
        /** The new version crashed on a previous start: report CRASH, then clear. */
        REPORT_CRASH,
        /** The new version is running: wait for the first MQTT connect (OK) or the timeout (FAILED). */
        WATCH,
    }

    fun onStart(pending: Pending?, installed: Int, crashMarker: Int?, now: Long): StartAction = when {
        pending == null -> StartAction.NONE
        installed > pending.to -> StartAction.CLEAR
        installed < pending.to -> if (now - pending.at > STALE_PENDING_MS || now < pending.at) StartAction.CLEAR else StartAction.NONE
        crashMarker == installed -> StartAction.REPORT_CRASH
        else -> StartAction.WATCH
    }

    /** Crash detail sent to the server: exception class names only, never messages (they may contain user data). */
    fun crashDetail(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(3).map { it.javaClass.name }.toList()
        return chain.joinToString(" <- ").take(300)
    }
}
