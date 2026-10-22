package com.qimingnan.couple.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.couple.CoupleRepository
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
                    _state.update { it.copy(generating = false, myCode = r.value) }
                    startTicker(r.value)
                }
                is ApiResult.Err -> _state.update { it.copy(generating = false, error = r.error.humanMessage()) }
            }
        }
    }

    /** 倒计时 + 每 3 秒查询一次 GET /couple（对方输入成功后这里会变成已配对）。 */
    private fun startTicker(code: PairCode) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val left = ((code.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                _state.update { it.copy(secondsLeft = left) }
                if (left <= 0) break
                if (tick % 3 == 0 && repo.pollPaired()) {
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

fun formatCountdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
