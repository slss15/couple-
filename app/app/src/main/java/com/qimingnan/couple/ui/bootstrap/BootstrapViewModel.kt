package com.qimingnan.couple.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.bootstrap.ApplyOutcome
import com.qimingnan.couple.data.bootstrap.ApplyProgress
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BootstrapUiState {
    data object Idle : BootstrapUiState
    data object Verifying : BootstrapUiState
    data class HealthChecking(val secondsLeft: Int, val lastError: String?) : BootstrapUiState
    data object Success : BootstrapUiState
    data class RolledBack(val reason: String) : BootstrapUiState
    data class Failed(val reason: String) : BootstrapUiState
}

@HiltViewModel
class BootstrapViewModel @Inject constructor(
    private val manager: BootstrapManager,
) : ViewModel() {
    private val _state = MutableStateFlow<BootstrapUiState>(BootstrapUiState.Idle)
    val state: StateFlow<BootstrapUiState> = _state.asStateFlow()

    val busy: Boolean
        get() = _state.value is BootstrapUiState.Verifying || _state.value is BootstrapUiState.HealthChecking

    private val progress: (ApplyProgress) -> Unit = { p ->
        _state.value = when (p) {
            ApplyProgress.VerifyingSignature -> BootstrapUiState.Verifying
            is ApplyProgress.HealthChecking -> BootstrapUiState.HealthChecking(p.secondsLeft, p.lastError)
        }
    }

    fun applyUri(raw: String) {
        if (busy) return
        _state.value = BootstrapUiState.Verifying
        viewModelScope.launch { _state.value = map(manager.applyUri(raw, progress)) }
    }

    fun applyManual(baseUrl: String, mqttUrl: String) {
        if (busy) return
        _state.value = BootstrapUiState.Verifying
        viewModelScope.launch { _state.value = map(manager.applyManual(baseUrl, mqttUrl, progress)) }
    }

    fun reset() {
        if (!busy) _state.value = BootstrapUiState.Idle
    }

    private fun map(o: ApplyOutcome): BootstrapUiState = when (o) {
        is ApplyOutcome.Applied -> BootstrapUiState.Success
        is ApplyOutcome.RolledBack -> BootstrapUiState.RolledBack(o.reason)
        is ApplyOutcome.Rejected -> BootstrapUiState.Failed(o.error.message ?: o.error.code)
    }
}
