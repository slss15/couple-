package com.qimingnan.couple.ui.anniversary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.db.AnniversaryDao
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.db.toModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Anniversary plugin view model (PR3).
 *
 * Reads only the plugin's own table; it never touches chat, token or MQTT
 * state. Add/edit form data flows through [save]/[delete] here so the
 * screen stays a pure display over [AnniversaryEvent] rules.
 */
@HiltViewModel
class AnniversaryViewModel @Inject constructor(
    private val dao: AnniversaryDao,
    private val clock: Clock,
) : ViewModel() {
    val events: StateFlow<List<AnniversaryEvent>> = dao.observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(event: AnniversaryEvent, coupleId: String?) = viewModelScope.launch {
        val existing = dao.byId(event.id)
        dao.upsert(event.toEntity(coupleId, clock.nowMillis(), existing))
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun delete(id: String) = viewModelScope.launch {
        dao.softDelete(id, clock.nowMillis())
    }
}
