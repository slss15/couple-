package com.qimingnan.couple.data.mqtt

/**
 * Stable MQTT client id. The broker binds persistent sessions (cleanSession=false) to the client id and only accepts
 * ids that belong to the authenticated user: `{userId}`, `{userId}-…` or the app's `a-{userId}-…`
 * (backend MqttAccessService.clientIdAllowed). One id per (user, device) keeps the session — and its queued QoS1
 * messages — across reconnects, network switches and app restarts.
 */
object MqttClientIds {
    const val MAX_LENGTH = 64

    fun forDevice(userId: String, deviceId: String): String {
        require(userId.isNotBlank() && deviceId.isNotBlank()) { "userId and deviceId are required" }
        val id = "a-$userId-$deviceId"
        // Truncation keeps the "a-{userId}-" prefix the broker checks, as long as the userId itself fits.
        return if (id.length <= MAX_LENGTH) id else id.take(MAX_LENGTH)
    }
}
