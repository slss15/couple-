package com.qimingnan.couple.data.ledger

import java.time.LocalDate

/** 向导只组织输入，最终验证和余额/统计仍复用 LedgerLogic。 */
data class LedgerEntryDraft(
    val amountCents: Long,
    val type: LedgerEntryType,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val category: String? = null,
    val detail: String? = null,
    val occurredOn: LocalDate = LocalDate.now(),
    val note: String = "",
) {
    val steps: List<String> get() = if (type == LedgerEntryType.TRANSFER)
        listOf("金额", "收支类型", "转出账户", "转入账户", "确认转账")
    else listOf("金额", "收支类型", "选择账户", "一级分类", "细分子类", "确认记账")

    fun toEntry(id: String, ownerId: String = ""): LedgerEntry = LedgerEntry(
        id = id, ownerId = ownerId, type = type, amountCents = amountCents,
        accountId = if (type == LedgerEntryType.TRANSFER) null else accountId,
        fromAccountId = if (type == LedgerEntryType.TRANSFER) accountId else null,
        toAccountId = if (type == LedgerEntryType.TRANSFER) toAccountId else null,
        categoryId = if (type == LedgerEntryType.TRANSFER || category == null) null
            else if (detail.isNullOrBlank()) category else "$category·$detail",
        occurredOn = occurredOn, note = note.trim(),
    )
}
