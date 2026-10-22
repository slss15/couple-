package com.qimingnan.couple.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.core.Base64Url
import com.qimingnan.couple.core.fingerprint
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.bootstrap.ServerConfig
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.mqtt.MqttState
import com.qimingnan.couple.data.mqtt.Presence
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.Couple
import com.qimingnan.couple.data.net.User
import com.qimingnan.couple.data.net.humanMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UnpairUi(
    val requestedId: String? = null,
    val confirmInput: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class MeUiState(
    val me: User? = null,
    val couple: Couple? = null,
    val coupleFromCache: Boolean = false,
    val partner: User? = null,
    val partnerPresence: Presence? = null,
    val config: ServerConfig? = null,
    val pinFingerprint: String? = null,
    val mqtt: MqttState = MqttState.Idle,
    val unpair: UnpairUi = UnpairUi(),
)

@HiltViewModel
class MeViewModel @Inject constructor(
    private val session: SessionManager,
    private val coupleRepo: CoupleRepository,
    bootstrapStore: BootstrapStore,
    private val mqtt: MqttManager,
) : ViewModel() {
    private val unpair = MutableStateFlow(UnpairUi())

    val state: StateFlow<MeUiState> = combine(
        session.state, coupleRepo.state, bootstrapStore.state, combine(mqtt.state, mqtt.presence, ::Pair), unpair,
    ) { s, c, b, (mq, presence), u ->
        val me = (s as? SessionState.LoggedIn)?.user
        val couple = (c as? CoupleState.Paired)?.couple
        val partner = couple?.members?.firstOrNull { it.id != me?.id }
        MeUiState(
            me = me,
            couple = couple,
            coupleFromCache = (c as? CoupleState.Paired)?.fromCache == true,
            partner = partner,
            partnerPresence = partner?.let { presence[it.id] },
            config = b?.current,
            pinFingerprint = b?.pinnedPublicKey?.let { runCatching { fingerprint(Base64Url.decode(it)) }.getOrNull() },
            mqtt = mq,
            unpair = u,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

    fun refresh() {
        viewModelScope.launch { coupleRepo.refresh() }
    }

    fun requestUnpair() {
        unpair.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = coupleRepo.requestUnpair()) {
                is ApiResult.Ok -> unpair.update { it.copy(busy = false, requestedId = r.value) }
                is ApiResult.Err -> unpair.update { it.copy(busy = false, error = r.error.humanMessage()) }
            }
        }
    }

    fun onConfirmInput(v: String) = unpair.update { it.copy(confirmInput = v.trim(), error = null) }

    fun confirmUnpair() {
        unpair.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = coupleRepo.confirmUnpair(unpair.value.confirmInput)) {
                is ApiResult.Ok -> unpair.value = UnpairUi()
                is ApiResult.Err -> unpair.update { it.copy(busy = false, error = unpairMessage(r.error.code, r.error.humanMessage())) }
            }
        }
    }

    fun reconnectMqtt() = mqtt.reconnectNow()

    fun logout() {
        viewModelScope.launch { session.logout() }
    }

    private fun unpairMessage(code: String, fallback: String) = when (code) {
        "NOT_OTHER_MEMBER" -> "解绑申请需要由 TA 来确认"
        "UNPAIR_REQUEST_NOT_FOUND" -> "没有找到待确认的解绑申请"
        "UNPAIR_REQUEST_EXPIRED" -> "解绑申请已过期"
        "UNPAIR_REQUEST_CLOSED" -> "解绑申请已处理或已取消"
        else -> fallback
    }
}
