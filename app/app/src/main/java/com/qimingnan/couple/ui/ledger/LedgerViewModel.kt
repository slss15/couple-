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
import com.qimingnan.couple.data.settings.SettingsRepository
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
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    /** 借贷记录（信用与负债）：存在设置 DataStore 里，不动数据库版本，见 LoanRecord。 */
    val loans: StateFlow<List<LoanRecord>> = settingsRepo.settings
        .map { s -> s.loans.mapNotNull(LoanRecord::decode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addLoan(record: LoanRecord, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        onComplete(runCatching { settingsRepo.addLoan(record) }.exceptionOrNull()?.message)
    }
    fun repayLoan(id: String, addCents: Long, date: String = LocalDate.now().toString(), note: String = "", onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            if (!settingsRepo.repayLoan(id, addCents, date, note)) "金额无效、超过未结清金额或记录已变更" else null
        }.getOrElse { it.message ?: "还款保存失败" }
        onComplete(error)
    }
    fun deleteLoan(id: String) = viewModelScope.launch { settingsRepo.deleteLoan(id) }
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

    fun saveAccount(account: LedgerAccount, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            require(account.name.isNotBlank()) { "请填写账户名称" }
            val ownerId = currentOwnerId()
            dao.upsertAccount(account.toEntity(ownerId, clock.nowMillis(), dao.accountById(account.id)))
        }.exceptionOrNull()?.let { it.message ?: "账户保存失败" }
        onComplete(error)
    }

    fun saveEntry(entry: LedgerEntry, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val error = runCatching {
            val withOwner = entry.copy(ownerId = currentOwnerId())
            val invalid = LedgerLogic.validate(withOwner)
            if (invalid != null) invalid else {
                dao.upsertEntry(withOwner.toEntity(clock.nowMillis()))
                null
            }
        }.getOrElse { it.message ?: "记账保存失败" }
        onComplete(error)
    }

    fun deleteEntry(id: String) = viewModelScope.launch {
        dao.softDeleteEntry(id, clock.nowMillis())
    }

    fun deleteAccount(id: String) = viewModelScope.launch {
        dao.softDeleteAccount(id, clock.nowMillis())
    }

    fun balance(account: LedgerAccount, entries: List<LedgerEntry>): Long =
        LedgerLogic.balance(account, entries)
}
