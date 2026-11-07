package com.qimingnan.couple.ui.anniversary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.db.AnniversaryDao
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 纪念日附加状态在本机 DataStore 持久保存；不升 Room 版本，不同步准备进度和旅行账户。 */
data class AnniversaryExtras(
    val checkins: Set<String> = emptySet(),
    val surpriseProgress: Map<String, Int> = emptyMap(),
    val travelAccountId: String? = null,
    val travelTargetCents: Long = 0,
    val travelName: String = "",
) {
    fun checkedIn(eventId: String, date: String): Boolean = "$eventId:$date" in checkins
    fun checkinCount(eventId: String): Int = checkins.count { it.substringBefore(':') == eventId }
    fun surprise(eventId: String): Int = surpriseProgress[eventId] ?: 0
}

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
    private val settingsRepo: SettingsRepository,
    private val profileDao: ProfileDao,
) : ViewModel() {
    val events: StateFlow<List<AnniversaryEvent>> = dao.observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val extras: StateFlow<AnniversaryExtras> = settingsRepo.settings
        .map { s ->
            AnniversaryExtras(
                checkins = s.checkins,
                surpriseProgress = s.surpriseProgress.mapNotNull { raw ->
                    val id = raw.substringBefore(':')
                    val v = raw.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
                    id to v
                }.toMap(),
                travelAccountId = s.travelAccountId,
                travelTargetCents = s.travelTargetCents,
                travelName = s.travelName,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnniversaryExtras())

    /** 打卡：记下这次发生日已打卡。 */
    fun checkIn(eventId: String, date: String) = viewModelScope.launch { settingsRepo.addCheckIn(eventId, date) }

    /** 神秘惊喜准备进度（0–100）。 */
    fun setSurpriseProgress(eventId: String, progress: Int) =
        viewModelScope.launch { settingsRepo.setSurpriseProgress(eventId, progress) }

    /** 旅行计划：关联账户 + 目标金额（分）；accountId 为 null 表示清除计划。 */
    fun setTravel(accountId: String?, targetCents: Long, name: String = "", onComplete: (String?) -> Unit = {}) =
        viewModelScope.launch { onComplete(runCatching { settingsRepo.setTravel(accountId, targetCents, name) }.exceptionOrNull()?.message) }

    fun save(event: AnniversaryEvent, coupleId: String? = null, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            val invalid = AnniversaryLogic.validationError(event)
            if (invalid != null) invalid else {
                val existing = dao.byId(event.id)
                dao.upsert(event.toEntity(coupleId ?: profileDao.self()?.coupleId, clock.nowMillis(), existing))
                null
            }
        }.getOrElse { it.message ?: "纪念日保存失败" }
        onComplete(error)
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun delete(id: String) = viewModelScope.launch {
        dao.softDelete(id, clock.nowMillis())
    }
}
