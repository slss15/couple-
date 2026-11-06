package com.qimingnan.couple.ui.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.core.Ulid
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.db.PeriodDao
import com.qimingnan.couple.data.db.PeriodLogEntity
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.period.PeriodLog
import com.qimingnan.couple.data.period.PeriodLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PeriodViewModel @Inject constructor(
    private val dao: PeriodDao,
    session: SessionManager,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val logs: List<PeriodLog> = emptyList(),
        val stats: PeriodLogic.Stats? = null,
        /** 界面上可用的 owner（未登录为空 → 空态）。 */
        val ready: Boolean = false,
    )

    private val selfId = MutableStateFlow<String?>(null)

    init {
        val s = session.state.value
        selfId.value = (s as? SessionState.LoggedIn)?.user?.id
    }

    val saving = MutableStateFlow(false)
    val savingFlow: StateFlow<Boolean> = saving.asStateFlow()

    val state: StateFlow<UiState> = combine(
        dao.observeAll(), selfId,
    ) { all, self ->
        val logs = all.filter { self == null || it.ownerId == self }.map { it.toModel() }
        UiState(
            loading = false,
            logs = logs,
            stats = PeriodLogic.stats(logs, LocalDate.now()),
            ready = self != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun addLog(start: LocalDate, end: LocalDate?, note: String) {
        val ownerId = selfId.value ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            saving.value = true
            dao.upsert(
                PeriodLogEntity(
                    id = Ulid.next(now),
                    ownerId = ownerId,
                    startDate = start.toString(),
                    endDate = end?.toString(),
                    note = note.trim(),
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    pendingSync = false,
                ),
            )
            saving.value = false
        }
    }

    fun deleteLog(id: String) {
        viewModelScope.launch { dao.softDelete(id, System.currentTimeMillis()) }
    }
}
