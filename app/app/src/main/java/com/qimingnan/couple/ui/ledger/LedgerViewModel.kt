package com.qimingnan.couple.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.db.LedgerDao
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

/** Built-in categories (PR4). Deleting a category is not offered in v1: entries keep their category id and the UI shows “已删除分类” for unknown ids. */
object LedgerCategories {
    val EXPENSE = listOf("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "教育", "其他")
    val INCOME = listOf("工资", "兼职", "理财收益", "红包", "退款", "其他")
}

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val dao: LedgerDao,
    private val profileDao: ProfileDao,
    private val clock: Clock,
) : ViewModel() {
    private val selfId: StateFlow<String?> = profileDao.observeSelf()
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Personal ledger: when a user is logged in, only that user's entries
    // are shown. Before login, local entries are shown (single-device use).
    val accounts: StateFlow<List<LedgerAccount>> = combine(dao.observeAccounts(), selfId) { list, self ->
        list.filter { self == null || it.ownerId == self }.map { it.toModel() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val entries: StateFlow<List<LedgerEntry>> = combine(dao.observeEntries(), selfId) { list, self ->
        list.filter { self == null || it.ownerId == self }.map { it.toModel() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun currentOwnerId(): String = profileDao.self()?.id ?: "local"

    val month = kotlinx.coroutines.flow.MutableStateFlow(YearMonth.from(LocalDate.now()))

    val monthTotals: StateFlow<LedgerLogic.MonthTotals> = combine(entries, month) { e, m ->
        LedgerLogic.monthTotals(e, m)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LedgerLogic.MonthTotals(0, 0))

    fun setMonth(m: YearMonth) {
        month.value = m
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun saveAccount(account: LedgerAccount) = viewModelScope.launch {
        val ownerId = currentOwnerId()
        dao.upsertAccount(account.toEntity(ownerId, clock.nowMillis(), dao.accountById(account.id)))
    }

    fun saveEntry(entry: LedgerEntry) = viewModelScope.launch {
        val withOwner = entry.copy(ownerId = currentOwnerId())
        if (LedgerLogic.validate(withOwner) != null) return@launch
        dao.upsertEntry(withOwner.toEntity(clock.nowMillis()))
    }

    fun deleteEntry(id: String) = viewModelScope.launch {
        dao.softDeleteEntry(id, clock.nowMillis())
    }

    fun balance(account: LedgerAccount, entries: List<LedgerEntry>): Long =
        LedgerLogic.balance(account, entries)
}
