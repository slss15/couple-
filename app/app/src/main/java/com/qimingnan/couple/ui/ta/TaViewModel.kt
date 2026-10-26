package com.qimingnan.couple.ui.ta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.mqtt.MqttManager
import com.qimingnan.couple.data.net.ApiResult
import com.qimingnan.couple.data.net.ImportantDay
import com.qimingnan.couple.data.net.TaSummary
import com.qimingnan.couple.data.net.UpdateMeRequest
import com.qimingnan.couple.data.net.UpsertDayRequest
import com.qimingnan.couple.data.net.apiCall
import com.qimingnan.couple.data.net.humanMessage
import com.qimingnan.couple.notify.Notifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 距下次「月-日」纪念的天数（今天就是 → 0）。 */
fun daysUntil(month: Int, day: Int, nowMillis: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    fun midnight(year: Int): Long = java.util.Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis
    val todayMidnight = java.util.Calendar.getInstance().apply {
        clear()
        set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
    val thisYear = midnight(cal.get(java.util.Calendar.YEAR))
    val next = if (thisYear >= todayMidnight) thisYear else midnight(cal.get(java.util.Calendar.YEAR) + 1)
    return java.util.concurrent.TimeUnit.MILLISECONDS.toDays(next - todayMidnight).toInt()
}

@javax.inject.Singleton
class TaRepository @javax.inject.Inject constructor(
    private val api: com.qimingnan.couple.data.net.CoupleApi,
) {
    suspend fun summary(): TaSummary = api.taSummary()
    suspend fun updateMe(req: UpdateMeRequest) = api.updateTaMe(req)
    suspend fun addDay(req: UpsertDayRequest): ImportantDay = api.addDay(req)
    suspend fun updateDay(id: String, req: UpsertDayRequest): ImportantDay = api.updateDay(id, req)
    suspend fun deleteDay(id: String) = api.deleteDay(id)
}

@HiltViewModel
class TaViewModel @Inject constructor(
    private val repo: TaRepository,
    private val bootstrapStore: BootstrapStore,
    private val mqtt: MqttManager,
    private val notifier: Notifier,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val summary: TaSummary? = null,
        /** 对方在线（presence 流；演示模式由 DemoBackend 注入）。 */
        val partnerOnline: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            mqtt.presence.collect { map ->
                val pid = _state.value.summary?.partner?.id ?: return@collect
                _state.update { it.copy(partnerOnline = map[pid]?.online == true) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = apiCall { repo.summary() }) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, summary = r.value) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.error.humanMessage()) }
            }
        }
    }

    fun saveMe(bio: String, birthday: String, birthYear: Int?, showBirthday: Boolean) {
        viewModelScope.launch {
            val req = UpdateMeRequest(
                bio = bio.trim().ifEmpty { null },
                birthday = birthday.takeIf { Regex("^\\d{2}-\\d{2}$").matches(it) },
                birthYear = birthYear?.takeIf { it in 1900..2100 },
                showBirthday = showBirthday,
            )
            when (val r = apiCall { repo.updateMe(req) }) {
                is ApiResult.Ok -> refresh()
                is ApiResult.Err -> _state.update { it.copy(error = r.error.humanMessage()) }
            }
        }
    }

    fun addDay(name: String, month: Int, day: Int, year: Int?) {
        viewModelScope.launch {
            when (val r = apiCall { repo.addDay(UpsertDayRequest(name.trim(), month, day, year)) }) {
                is ApiResult.Ok -> refresh()
                is ApiResult.Err -> _state.update { it.copy(error = r.error.humanMessage()) }
            }
        }
    }

    fun deleteDay(id: String) {
        viewModelScope.launch {
            apiCall { repo.deleteDay(id) }
            refresh()
        }
    }

    /** avatarUrl 是相对路径（/api/v1/media/…），拼成绝对地址给 Coil。 */
    fun mediaUrlOf(relativeOrAbsolute: String): String {
        if (relativeOrAbsolute.startsWith("http")) return relativeOrAbsolute
        val base = bootstrapStore.currentConfigBlocking()?.baseUrl?.trimEnd('/') ?: return relativeOrAbsolute
        return base + relativeOrAbsolute
    }
}
