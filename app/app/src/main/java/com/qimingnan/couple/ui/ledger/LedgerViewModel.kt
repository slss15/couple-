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
import com.qimingnan.couple.data.ledger.LoanRecord
import com.qimingnan.couple.data.ledger.LoanKind
import com.qimingnan.couple.data.ledger.LoanLedgerRepository
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

/** 详细类型：大类下面的二级选择，例如餐饮 -> 早饭/午饭/晚饭。 */
object LedgerSubcategories {
    private val MAP = mapOf(
        "餐饮" to listOf("早饭", "午饭", "晚饭", "夜宵", "零食", "饮料", "外卖", "其他"),
        "交通" to listOf("公交", "地铁", "打车", "加油", "停车", "其他"),
        "购物" to listOf("衣服", "日用品", "数码", "其他"),
        "居住" to listOf("房租", "水电", "物业", "其他"),
        "娱乐" to listOf("电影", "游戏", "运动", "其他"),
        "医疗" to listOf("挂号", "药品", "其他"),
        "教育" to listOf("书籍", "课程", "其他"),
        "其他" to listOf("其他"),
        "工资" to listOf("月薪", "奖金", "其他"),
        "兼职" to listOf("工资", "其他"),
        "理财收益" to listOf("利息", "分红", "其他"),
        "红包" to listOf("红包", "其他"),
        "退款" to listOf("退款", "其他"),
    )
    fun forCategory(category: String): List<String> = MAP[category] ?: listOf("其他")
}

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val dao: LedgerDao,
    private val profileDao: ProfileDao,
    private val clock: Clock,
    private val loanLedger: LoanLedgerRepository,
) : ViewModel() {
    val operationError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    init { viewModelScope.launch { operationError.value = runCatching { loanLedger.importLegacy(currentOwnerId()) }.exceptionOrNull()?.message } }

    fun addLoan(record: LoanRecord, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        onComplete(runCatching { loanLedger.saveLoan(record, currentOwnerId()) }.exceptionOrNull()?.message)
    }
    fun recordRepayment(entryId: String, loanId: String, cashAccountId: String, cents: Long,
        date: String, note: String, expectedKind: LoanKind, onComplete: (String?) -> Unit) = viewModelScope.launch {
        onComplete(runCatching { loanLedger.repay(entryId, loanId, cashAccountId, cents, date, note, currentOwnerId(), expectedKind) }.exceptionOrNull()?.message)
    }
    fun deleteLoan(id: String, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        onComplete(runCatching { loanLedger.deleteLoan(id, currentOwnerId()) }.exceptionOrNull()?.message)
    }
    private val selfId: StateFlow<String?> = profileDao.observeSelf()
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Personal ledger: when a user is logged in, only that user's entries
    // are shown. Before login, local entries are shown (single-device use).
    val accounts: StateFlow<List<LedgerAccount>> = combine(dao.observeAccounts(), selfId) { list, self ->
        list.filter { it.ownerId == (self ?: "local") }.map { it.toModel() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val entries: StateFlow<List<LedgerEntry>> = combine(dao.observeEntries(), selfId) { list, self ->
        list.filter { it.ownerId == (self ?: "local") }.map { it.toModel() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val loans: StateFlow<List<LoanRecord>> = combine(dao.observeLoans(), selfId) { list, self ->
        list.filter { it.ownerId == (self ?: "local") }.mapNotNull { LoanRecord.decode(it.payload) }
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

    fun saveAccount(account: LedgerAccount, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            require(account.name.isNotBlank()) { "请填写账户名称" }
            val ownerId = currentOwnerId()
            val existing = dao.accountById(account.id)
            require(existing == null || existing.ownerId == ownerId) { "不能修改其他用户的账户" }
            require(existing == null || existing.type == account.type.name || !existing.toModel().type.isLoan && !account.type.isLoan) { "借贷账户不能改为资金账户，资金账户也不能改为借贷账户" }
            require(!account.type.isLoan || account.initialBalanceCents == 0L) { "借贷余额来自明细，不填写期初余额" }
            dao.upsertAccount(account.toEntity(ownerId, clock.nowMillis(), existing))
        }.exceptionOrNull()?.let { it.message ?: "账户保存失败" }
        onComplete(error)
    }

    fun saveEntry(entry: LedgerEntry, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            require(entry.loanId == null && entry.loanAction == null) { "借贷还款请使用还钱流程" }
            val ownerId = currentOwnerId()
            val ids = if (entry.type == com.qimingnan.couple.data.ledger.LedgerEntryType.TRANSFER) listOf(entry.fromAccountId, entry.toAccountId) else listOf(entry.accountId)
            ids.forEach { id ->
                val account = dao.accountById(id ?: "")
                require(account != null && !account.deleted && account.ownerId == ownerId && !account.toModel().type.isLoan) { "请选择有效的资金账户" }
            }
            val withOwner = entry.copy(ownerId = ownerId)
            val invalid = LedgerLogic.validate(withOwner)
            if (invalid != null) invalid else {
                dao.upsertEntry(withOwner.toEntity(clock.nowMillis()))
                null
            }
        }.getOrElse { it.message ?: "记账保存失败" }
        onComplete(error)
    }

    fun deleteEntry(id: String) = viewModelScope.launch {
        operationError.value = runCatching { loanLedger.deleteEntry(id, currentOwnerId()) }.exceptionOrNull()?.message
    }

    fun deleteAccount(id: String) = viewModelScope.launch {
        operationError.value = runCatching {
            loanLedger.deleteAccount(id, currentOwnerId())
        }.exceptionOrNull()?.message
    }

    fun balance(account: LedgerAccount, entries: List<LedgerEntry>): Long =
        LedgerLogic.balance(account, entries, loans.value)
}
