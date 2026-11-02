package com.qimingnan.couple.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.PairCode
import com.qimingnan.couple.data.net.humanMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val myCode: PairCode? = null,
    /** Local-clock deadline of [myCode] (see [pairCodeDeadline]); 0 when there is no code. */
    val expiresAtLocal: Long = 0,
    val secondsLeft: Long = 0,
    val generating: Boolean = false,
    val input: String = "",
    val joining: Boolean = false,
    val error: String? = null,
    val paired: Boolean = false,
) {
    val codeExpired: Boolean get() = myCode != null && secondsLeft <= 0
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repo: CoupleRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()
    private var ticker: Job? = null

    fun generate() {
        if (_state.value.generating) return
        _state.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.createCode()) {
                is ApiResult.Ok -> {
                    val deadline = pairCodeDeadline(r.value.expiresAt, System.currentTimeMillis())
                    _state.update { it.copy(generating = false, myCode = r.value, expiresAtLocal = deadline) }
                    startTicker(deadline)
                }
                is ApiResult.Err -> _state.update { it.copy(generating = false, error = r.error.humanMessage()) }
            }
        }
    }

    /**
     * 倒计时。配对成功主要靠 MQTT `paired` 推送（AppCoordinator → CoupleRepository.state 变成 Paired）；
     * 每 [FALLBACK_POLL_SECONDS] 秒兜底查询一次 GET /couple（MQTT 未连上时）。
     */
    private fun startTicker(deadlineLocal: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val left = ((deadlineLocal - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                _state.update { it.copy(secondsLeft = left) }
                if (left <= 0) break
                if (repo.state.value is CoupleState.Paired && !(repo.state.value as CoupleState.Paired).fromCache) {
                    _state.update { it.copy(paired = true) }
                    break
                }
                if (tick > 0 && tick % FALLBACK_POLL_SECONDS == 0 && repo.pollPaired()) {
                    _state.update { it.copy(paired = true) }
                    break
                }
                tick++
                delay(1_000)
            }
        }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v.filter(Char::isDigit).take(6), error = null) }

    fun join() {
        val code = _state.value.input
        if (code.length != 6 || _state.value.joining) return
        _state.update { it.copy(joining = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.join(code)) {
                is ApiResult.Ok -> _state.update { it.copy(joining = false, paired = true) }
                is ApiResult.Err -> _state.update { it.copy(joining = false, error = r.error.humanMessage()) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }
}

/** MQTT 推送失效时的兜底轮询间隔（原为 3 秒轮询）。 */
const val FALLBACK_POLL_SECONDS = 15

fun formatCountdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** contract: a pairing code lives 10 minutes. */
const val PAIR_CODE_LIFETIME_MILLIS = 10 * 60_000L

/**
 * Deadline of a pairing code on this phone's clock. expiresAt is on the server's clock; if the two clocks disagree so
 * much that the code would look already expired or valid for longer than its 10-minute lifetime, fall back to
 * "received now + 10 minutes" (the code was just issued).
 */
fun pairCodeDeadline(expiresAtServer: Long, receivedAtLocal: Long, lifetimeMillis: Long = PAIR_CODE_LIFETIME_MILLIS): Long {
    val delta = expiresAtServer - receivedAtLocal
    return if (delta in 1..lifetimeMillis) expiresAtServer else receivedAtLocal + lifetimeMillis
}
