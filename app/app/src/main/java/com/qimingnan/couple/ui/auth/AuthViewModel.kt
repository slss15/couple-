package com.qimingnan.couple.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.humanMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val registerMode: Boolean = false,
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
    val inviteCode: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = !loading && username.isNotBlank() && password.length >= 8 &&
            (!registerMode || (displayName.isNotBlank() && inviteCode.isNotBlank()))
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun toggleMode() = _state.update { it.copy(registerMode = !it.registerMode, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, error = null) }
    fun onInviteCode(v: String) = _state.update { it.copy(inviteCode = v, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = if (s.registerMode) {
                session.register(s.username, s.password, s.displayName, s.inviteCode)
            } else {
                session.login(s.username, s.password)
            }
            _state.update {
                when (r) {
                    is ApiResult.Ok -> it.copy(loading = false, password = "")
                    is ApiResult.Err -> it.copy(loading = false, error = r.error.humanMessage())
                }
            }
        }
    }
}
