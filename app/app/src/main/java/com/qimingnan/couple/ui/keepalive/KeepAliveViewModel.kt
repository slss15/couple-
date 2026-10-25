package com.qimingnan.couple.ui.keepalive

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.keepalive.CheckItem
import com.qimingnan.couple.data.keepalive.KeepAliveChecker
import com.qimingnan.couple.data.keepalive.KeepAliveController
import com.qimingnan.couple.data.keepalive.Oem
import com.qimingnan.couple.data.keepalive.OemKeepAlive
import com.qimingnan.couple.data.keepalive.OemPage
import com.qimingnan.couple.data.keepalive.OemIntentLauncher
import com.qimingnan.couple.data.keepalive.ShizukuAccess
import com.qimingnan.couple.data.keepalive.ShizukuKeepAliveCommands
import com.qimingnan.couple.data.keepalive.ShizukuRunner
import com.qimingnan.couple.data.mqtt.LatencyStats
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.mqtt.MqttState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KeepAliveUi(
    val checks: List<CheckItem> = emptyList(),
    val oem: Oem = Oem.OTHER,
    val device: String = "",
    val shizuku: ShizukuAccess = ShizukuAccess.NOT_RUNNING,
    val shizukuBusy: Boolean = false,
    val shizukuLog: String? = null,
    val fgsEnabled: Boolean = false,
    val mqtt: MqttState = MqttState.Idle,
    val stats: LatencyStats.Snapshot? = null,
    val lastEvent: String? = null,
    val message: String? = null,
)

@HiltViewModel
class KeepAliveViewModel @Inject constructor(
    private val app: Application,
    private val controller: KeepAliveController,
    private val mqtt: MqttManager,
) : AndroidViewModel(app) {
    private val checker = KeepAliveChecker(app)
    private val local = MutableStateFlow(
        KeepAliveUi(
            oem = OemKeepAlive.detect(Build.MANUFACTURER, Build.BRAND),
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
        ),
    )

    val state: StateFlow<KeepAliveUi> = combine(local, controller.enabled, mqtt.state, mqtt.stats, mqtt.lastEvent) { l, fgs, m, st, ev ->
        l.copy(fgsEnabled = fgs, mqtt = m, stats = st, lastEvent = ev)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Re-reads every check (called on resume, i.e. after the user comes back from a settings page). */
    fun refresh() {
        viewModelScope.launch {
            val checks = withContext(Dispatchers.Default) { checker.runAll() }
            local.update { it.copy(checks = checks, shizuku = ShizukuRunner.access()) }
        }
    }

    fun openOemPage(page: OemPage) {
        val opened = OemIntentLauncher.open(app, OemKeepAlive.targets(local.value.oem, page))
        local.update { it.copy(message = "已打开：$opened") }
    }

    fun requestShizuku() = ShizukuRunner.requestPermission()

    /** Exempts the app from Doze and background-run restrictions through Shizuku, then re-checks. */
    fun fixWithShizuku() {
        if (local.value.shizukuBusy) return
        local.update { it.copy(shizukuBusy = true, shizukuLog = null) }
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) {
                runCatching {
                    ShizukuKeepAliveCommands.commands(app.packageName).map(ShizukuRunner::run)
                }.fold(
                    onSuccess = { results ->
                        results.joinToString("\n") { r ->
                            (if (r.exitCode == 0) "✓ " else "✗ (${r.exitCode}) ") + r.command +
                                (r.output.takeIf { it.isNotBlank() }?.let { "\n   $it" } ?: "")
                        }
                    },
                    onFailure = { "执行失败：${it.message ?: it.javaClass.simpleName}" },
                )
            }
            local.update { it.copy(shizukuBusy = false, shizukuLog = log) }
            refresh()
        }
    }

    fun setFgs(on: Boolean) = controller.setEnabled(on)

    fun reconnect() = mqtt.reconnectNow()

    fun consumeMessage() = local.update { it.copy(message = null) }
}
