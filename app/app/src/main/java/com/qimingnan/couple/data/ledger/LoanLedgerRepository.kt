package com.qimingnan.couple.data.ledger

import androidx.room.withTransaction
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.data.db.AppDatabase
import com.qimingnan.couple.data.db.toEntity
import com.qimingnan.couple.data.db.toModel
import com.qimingnan.couple.data.settings.SettingsRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Loan progress and the corresponding cash movement always commit in the same Room transaction. */
@Singleton
class LoanLedgerRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val dao get() = db.ledgerDao()

    suspend fun importLegacy(ownerId: String) {
        val legacy = settings.legacyLoansForImport()
        db.withTransaction {
            legacy.groupBy { it.kind }.forEach { (kind, records) ->
                val accountId = "legacy-loan-$ownerId-${kind.name}"
                val type = if (kind == LoanKind.LENT) LedgerAccountType.LENT else LedgerAccountType.BORROWED
                dao.insertAccountIfAbsent(LedgerAccount(accountId, kind.label + "账户", type, 0).toEntity(ownerId, clock.nowMillis()))
                records.forEach { loan ->
                    // No historical cash entry is generated: older users may already have entered that money.
                    dao.insertLoanIfAbsent(loan.copy(loanAccountId = accountId).toEntity(ownerId))
                }
            }
        }
        settings.markLegacyLoansImported()
    }

    suspend fun saveLoan(record: LoanRecord, ownerId: String) = db.withTransaction {
        val account = dao.accountById(record.loanAccountId ?: "")
        require(account != null && !account.deleted && account.ownerId == ownerId && account.toModel().type.loanKind == record.kind) { "请选择对应的借入或借出账户" }
        val existingEntity = dao.loanById(record.id)
        require(existingEntity == null || existingEntity.ownerId == ownerId) { "不能修改其他用户的借贷" }
        val existing = existingEntity?.let { LoanRecord.decode(it.payload) }
        var updated = record.copy(counterparty = record.counterparty.trim(), label = record.label.trim())
        if (existing != null) {
            require(existing.kind == record.kind && existing.loanAccountId == record.loanAccountId) { "已有借贷不能改变账户或借入/借出方向" }
            // An editor opened before another repayment must not erase the committed progress.
            updated = updated.copy(repaidCents = existing.repaidCents, repayments = existing.repayments,
                accountId = existing.accountId, occurredOn = existing.occurredOn)
        } else {
            require(updated.counterparty.isNotBlank()) { "请填写借款人或个人/平台名称" }
        }
        require(updated.counterparty.isNotBlank() || existing != null && existing.counterparty.isBlank()) { "请填写个人或平台名称" }
        require(updated.validationError() == null) { updated.validationError() ?: "借贷无效" }
        val openingId = "loan-opening-${record.id}"
        val opening = dao.entryById(openingId)
        if ((existing == null && updated.accountId != null) || (opening != null && !opening.deleted)) {
            val cash = dao.accountById(updated.accountId ?: "")
            val changesPrincipal = existing == null || existing.amountCents != updated.amountCents
            // Closing a cash account must not prevent correcting loan metadata. Changing the
            // principal still requires a live account because it changes the linked cash balance.
            require(cash != null && cash.ownerId == ownerId && !cash.toModel().type.isLoan &&
                (!cash.deleted || !changesPrincipal)) { "请选择有效的实际资金账户" }
            val action = if (updated.kind == LoanKind.BORROWED) LoanAction.BORROW else LoanAction.LEND
            val entry = cashEntry(openingId, ownerId, updated, updated.accountId!!, updated.amountCents,
                updated.occurredOn ?: LocalDate.now().toString(), updated.label, action)
            dao.upsertEntry(entry.toEntity(clock.nowMillis(), opening))
        }
        dao.upsertLoan(updated.toEntity(ownerId))
    }

    suspend fun repay(
        entryId: String, loanId: String, cashAccountId: String, cents: Long,
        date: String, note: String, ownerId: String, expectedKind: LoanKind,
    ) = db.withTransaction {
        val entity = dao.loanById(loanId)
        require(entity != null && entity.ownerId == ownerId) { "借贷已删除或不可用" }
        val loan = requireNotNull(LoanRecord.decode(entity.payload)) { "借贷记录损坏" }
        require(loan.kind == expectedKind) { "还款方向不匹配，请重新选择借贷账户" }
        val loanAccount = dao.accountById(loan.loanAccountId ?: "")
        require(loanAccount != null && !loanAccount.deleted && loanAccount.ownerId == ownerId &&
            loanAccount.toModel().type.loanKind == expectedKind) { "借贷账户已删除或不可用，请重新选择" }
        val cash = dao.accountById(cashAccountId)
        require(cash != null && !cash.deleted && cash.ownerId == ownerId && !cash.toModel().type.isLoan) { "请选择有效的收款/付款账户" }
        val action = if (loan.kind == LoanKind.BORROWED) LoanAction.REPAY_BORROWED else LoanAction.COLLECT_LENT
        val prior = dao.entryById(entryId)
        if (prior != null) {
            require(!prior.deleted && prior.ownerId == ownerId && prior.loanAction == action.name &&
                prior.loanId == loanId && prior.accountId == cashAccountId && prior.amountCents == cents &&
                prior.occurredOn == date && prior.note == note.trim()) { "本次保存编号已使用，请重新打开记账" }
            return@withTransaction // Idempotent retry: never increment repaidCents twice.
        }
        val updated = loan.repay(cents, date, note, entryId, entryId, cashAccountId)
        requireNotNull(updated) { "金额无效、超过未结清金额或记录已变更" }
        val entry = cashEntry(entryId, ownerId, loan, cashAccountId, cents, date, note, action)
        dao.upsertEntry(entry.toEntity(clock.nowMillis()))
        dao.upsertLoan(updated.toEntity(ownerId))
    }

    suspend fun deleteEntry(id: String, ownerId: String) = db.withTransaction {
        val entry = dao.entryById(id) ?: return@withTransaction
        require(entry.ownerId == ownerId) { "不能删除其他用户的账目" }
        if (entry.deleted) return@withTransaction
        val loanId = entry.loanId
        if (loanId != null) {
            val entity = requireNotNull(dao.loanById(loanId)) { "关联借贷不可用" }
            val loan = requireNotNull(LoanRecord.decode(entity.payload))
            require(entity.ownerId == ownerId)
            if (entry.loanAction == LoanAction.BORROW.name || entry.loanAction == LoanAction.LEND.name) {
                error("借入/借出本金请在对应借贷明细中修改，不能单独删除资金记录")
            }
            val repayment = loan.repayments.firstOrNull { it.entryId == id }
            requireNotNull(repayment) { "未找到关联还款，未删除账目" }
            dao.upsertLoan(loan.copy(repaidCents = loan.repaidCents - repayment.amountCents,
                repayments = loan.repayments.filterNot { it.entryId == id }).toEntity(ownerId))
        }
        dao.softDeleteEntry(id, clock.nowMillis())
    }

    suspend fun deleteAccount(id: String, ownerId: String) = db.withTransaction {
        val account = dao.accountById(id)
        require(account != null && account.ownerId == ownerId) { "账户不存在" }
        if (account.deleted) return@withTransaction
        require(dao.loansByOwner(ownerId).mapNotNull { LoanRecord.decode(it.payload) }
            .none { it.loanAccountId == id }) { "该账户还有借贷明细，请先处理明细" }
        // Loan creation/repayment and this check+delete share Room's transaction boundary.
        dao.softDeleteAccount(id, clock.nowMillis())
    }

    suspend fun deleteLoan(id: String, ownerId: String) = db.withTransaction {
        val entity = dao.loanById(id) ?: return@withTransaction
        require(entity.ownerId == ownerId) { "不能删除其他用户的借贷" }
        val linked = dao.entriesForLoan(id)
        require(linked.none { it.loanAction == LoanAction.REPAY_BORROWED.name || it.loanAction == LoanAction.COLLECT_LENT.name }) { "这笔借贷已有还款账目，请先在账目中删除对应还款" }
        linked.forEach { dao.softDeleteEntry(it.id, clock.nowMillis()) }
        dao.deleteLoan(id)
    }

    private fun cashEntry(id: String, owner: String, loan: LoanRecord, cash: String, cents: Long,
        date: String, note: String, action: LoanAction) = LedgerEntry(
        id, owner, cash, action.entryType, "${action.label}·${loan.counterpartyType.label}·${loan.counterpartyName}",
        cents, null, null, LocalDate.parse(date), note = note.trim(), loanId = loan.id, loanAction = action,
    )
}
