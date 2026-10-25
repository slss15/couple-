package com.qimingnan.couple.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qimingnan.couple.data.mqtt.MqttManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug builds only. `adb shell am broadcast -a com.qimingnan.couple.debug.MQTT_PROBE -p <pkg> --es tag <tag>`
 * publishes one presence probe (see [MqttManager.publishProbe]).
 */
@AndroidEntryPoint
class MqttProbeReceiver : BroadcastReceiver() {
    @Inject lateinit var mqtt: MqttManager

    override fun onReceive(context: Context, intent: Intent) {
        mqtt.publishProbe(intent.getStringExtra("tag"))
    }
}
