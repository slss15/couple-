package com.qimingnan.couple.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.bootstrap.ApplyOutcome
import com.qimingnan.couple.data.bootstrap.ApplyProgress
import com.qimingnan.couple.data.bootstrap.BootstrapError
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import com.qimingnan.couple.data.bootstrap.BootstrapPreview
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.demo.DemoBackend
import com.qimingnan.couple.data.net.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a config on the confirm page came from. */
sealed interface PendingSource {
    data class Uri(val raw: String) : PendingSource
    data class Manual(val baseUrl: String, val mqttUrl: String) : PendingSource
}

sealed interface BootstrapUiState {
    data object Idle : BootstrapUiState
    data object Inspecting : BootstrapUiState
    /** Confirm page: host + key fingerprint; strong warning when they differ from what is trusted. */
    data class Confirm(val preview: BootstrapPreview, val source: PendingSource) : BootstrapUiState
    data object Verifying : BootstrapUiState
    data class HealthChecking(val secondsLeft: Int, val lastError: String?) : BootstrapUiState
    data object Success : BootstrapUiState
    data class RolledBack(val reason: String) : BootstrapUiState
    data class Failed(val reason: String) : BootstrapUiState
}

@HiltViewModel
class BootstrapViewModel @Inject constructor(
    private val manager: BootstrapManager,
    private val store: BootstrapStore,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow<BootstrapUiState>(BootstrapUiState.Idle)
    val state: StateFlow<BootstrapUiState> = _state.asStateFlow()

    val busy: Boolean
        get() = when (_state.value) {
            BootstrapUiState.Inspecting, BootstrapUiState.Verifying, is BootstrapUiState.HealthChecking,
            is BootstrapUiState.Confirm -> true
            else -> false
        }

    private val progress: (ApplyProgress) -> Unit = { p ->
        _state.value = when (p) {
            ApplyProgress.VerifyingSignature -> BootstrapUiState.Verifying
            is ApplyProgress.HealthChecking -> BootstrapUiState.HealthChecking(p.secondsLeft, p.lastError)
        }
    }

    /** Scan result / deep link / pasted link: verify and show the confirm page; nothing is stored yet. */
    fun inspectUri(raw: String) {
        if (busy) return
        _state.value = BootstrapUiState.Inspecting
        viewModelScope.launch {
            _state.value = try {
                BootstrapUiState.Confirm(manager.inspectUri(raw), PendingSource.Uri(raw))
            } catch (e: BootstrapError) {
                BootstrapUiState.Failed(e.message ?: e.code)
            } catch (e: Exception) {
                BootstrapUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun inspectManual(baseUrl: String, mqttUrl: String) {
        if (busy) return
        _state.value = BootstrapUiState.Inspecting
        viewModelScope.launch {
            _state.value = try {
                BootstrapUiState.Confirm(manager.inspectManual(baseUrl, mqttUrl), PendingSource.Manual(baseUrl, mqttUrl))
            } catch (e: BootstrapError) {
                BootstrapUiState.Failed(e.message ?: e.code)
            }
        }
    }

    /** The user pressed confirm; [acceptChange] is the explicit "trust the changed server" checkbox. */
    fun confirm(acceptChange: Boolean) {
        val c = _state.value as? BootstrapUiState.Confirm ?: return
        if (c.preview.trust.needsExplicitConfirm && !acceptChange) return
        _state.value = BootstrapUiState.Verifying
        val confirmation = c.preview.confirmation(acceptChange)
        viewModelScope.launch {
            _state.value = map(
                when (val s = c.source) {
                    is PendingSource.Uri -> manager.applyUri(s.raw, confirmation, progress)
                    is PendingSource.Manual -> manager.applyManual(s.baseUrl, s.mqttUrl, confirmation, progress)
                },
            )
        }
    }

    fun cancelConfirm() {
        if (_state.value is BootstrapUiState.Confirm) _state.value = BootstrapUiState.Idle
    }

    /**
     * debug 包的「调试账号」：不经扫码、不连服务器，直接切到进程内假后端（[DemoBackend]）并登录。
     * 之后在「我的 → 重新扫码」就能换回真实服务器。
     */
    fun enterDemo() {
        if (!DemoBackend.available || busy) return
        _state.value = BootstrapUiState.Verifying
        viewModelScope.launch {
            session.clearLocal()
            val st = store.load()
            val now = System.currentTimeMillis()
            val keepPrevious = st.current?.takeUnless { DemoBackend.isDemo(it.baseUrl) } ?: st.previous
            store.save(st.copy(current = DemoBackend.config(now), previous = keepPrevious, pendingSince = null))
            DemoBackend.paired = true
            delay(300) // 让“已切换服务器”的清理先跑完，再登录
            _state.value = when (val r = session.login(DemoBackend.USERNAME, DemoBackend.PASSWORD)) {
                is ApiResult.Ok -> BootstrapUiState.Success
                is ApiResult.Err -> BootstrapUiState.Failed(r.error.message)
            }
        }
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
