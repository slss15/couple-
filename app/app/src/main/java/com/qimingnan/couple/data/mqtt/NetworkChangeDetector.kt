package com.qimingnan.couple.data.mqtt

/**
 * Decides when a ConnectivityManager default-network event should force an MQTT reconnect.
 *
 * A WebSocket is bound to the network it was opened on; after a Wi-Fi <-> cellular switch the old socket is dead but
 * the client only notices at the next keepalive (up to 1.5 x keepAlive). Reconnect as soon as the default network
 * changes identity or its transport changes, and when a network comes back after having none.
 */
class NetworkChangeDetector {
    enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

    data class NetworkInfo(val id: String, val transports: Set<Transport>, val validated: Boolean)

    sealed interface Action {
        data object None : Action
        /**
         * @param force the current socket is bound to a network that is gone (switch / transport change): drop it even
         * if it still looks connected. false: only reconnect if not connected (network came back or became usable).
         */
        data class Reconnect(val reason: String, val force: Boolean) : Action
        data object Lost : Action
    }

    private var current: NetworkInfo? = null
    private var everHadNetwork = false

    /**
     * Feed onCapabilitiesChanged of the default-network callback here (since API 26 it always follows onAvailable and
     * carries the transports, so onAvailable itself is not needed).
     */
    @Synchronized
    fun onAvailable(info: NetworkInfo): Action {
        val prev = current
        current = info
        val wasEver = everHadNetwork
        everHadNetwork = true
        return when {
            prev == null && wasEver -> Action.Reconnect("网络已恢复（${label(info.transports)}）", force = true)
            prev == null -> Action.None // first callback right after registering
            prev.id != info.id ->
                Action.Reconnect("网络切换：${label(prev.transports)} → ${label(info.transports)}", force = true)
            prev.transports != info.transports ->
                Action.Reconnect("网络类型变化：${label(prev.transports)} → ${label(info.transports)}", force = true)
            !prev.validated && info.validated -> Action.Reconnect("网络已可用（${label(info.transports)}）", force = false)
            else -> Action.None
        }
    }

    /** Call once at registration when there is no default network, so the first network to appear reconnects. */
    @Synchronized
    fun markOffline() {
        current = null
        everHadNetwork = true
    }

    /** Only the loss of the current default network matters; a lost Wi-Fi after cellular took over is ignored. */
    @Synchronized
    fun onLost(id: String): Action {
        if (current?.id != id) return Action.None
        current = null
        return Action.Lost
    }

    companion object {
        fun label(t: Set<Transport>): String = when {
            Transport.WIFI in t -> "Wi-Fi"
            Transport.CELLULAR in t -> "移动数据"
            Transport.ETHERNET in t -> "有线"
            Transport.VPN in t -> "VPN"
            t.isEmpty() -> "无"
            else -> "其他"
        }
    }
}
