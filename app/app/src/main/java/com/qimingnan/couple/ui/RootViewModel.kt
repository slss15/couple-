package com.qimingnan.couple.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.couple.UnpairPrompt
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.humanMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 根据 配置 → 登录 → 配对 的状态决定顶层页面。 */
sealed interface RootDestination {
    data object Loading : RootDestination
    data object Bootstrap : RootDestination
    data object Auth : RootDestination
    data object Pairing : RootDestination
    data object Main : RootDestination
    data class Error(val message: String) : RootDestination
}

@HiltViewModel
class RootViewModel @Inject constructor(
    bootstrapStore: BootstrapStore,
    private val session: SessionManager,
    private val couple: CoupleRepository,
) : ViewModel() {

    val messages: SharedFlow<String> = session.messages

    val destination: StateFlow<RootDestination> = combine(
        bootstrapStore.state, session.state, couple.state,
    ) { b, s, c ->
        when {
            b == null -> RootDestination.Loading
            b.current == null -> RootDestination.Bootstrap
            s is SessionState.Loading -> RootDestination.Loading
            s is SessionState.LoggedOut -> RootDestination.Auth
            c is CoupleState.Unknown -> RootDestination.Loading
            c is CoupleState.NotPaired -> RootDestination.Pairing
            c is CoupleState.Paired -> RootDestination.Main
            c is CoupleState.Error -> RootDestination.Error(c.error.humanMessage())
            else -> RootDestination.Loading
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, RootDestination.Loading)

    init {
        bootstrapStore.let { store -> viewModelScope.launch { store.load() } }
        // 登录后、或配对状态未知时拉一次 /couple
        viewModelScope.launch {
            combine(session.state, couple.state) { s, c -> s is SessionState.LoggedIn && c is CoupleState.Unknown }
                .distinctUntilChanged()
                .collect { need -> if (need) couple.refresh() }
        }
    }

    /** 对方发起的解绑申请（MQTT 推送），弹确认框。 */
    val unpairPrompt: StateFlow<UnpairPrompt?> = couple.unpairPrompt

    fun confirmUnpair(requestId: String) {
        viewModelScope.launch {
            couple.dismissUnpairPrompt()
            val r = couple.confirmUnpair(requestId)
            if (r is ApiResult.Err) session.postMessage(r.error.humanMessage())
        }
    }

    fun dismissUnpairPrompt() = couple.dismissUnpairPrompt()

    fun retry() {
        viewModelScope.launch { couple.refresh() }
    }
}
