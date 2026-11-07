package com.qimingnan.couple.data.anniversary

import com.qimingnan.couple.data.ledger.LedgerAccount
import com.qimingnan.couple.data.ledger.LedgerEntry
import com.qimingnan.couple.data.ledger.LedgerLogic

/** 展示旅行储蓄，不另造共同余额、不会冻结或扣减关联账户资金。 */
data class TravelSavings(val balanceCents: Long, val targetCents: Long) {
    val savedCents: Long get() = balanceCents.coerceAtLeast(0)
    val remainingCents: Long get() = (targetCents - savedCents).coerceAtLeast(0)
    val progress: Float get() = if (targetCents <= 0) 0f else (savedCents.toDouble() / targetCents).toFloat().coerceIn(0f, 1f)

    companion object {
        fun from(account: LedgerAccount?, entries: List<LedgerEntry>, targetCents: Long): TravelSavings? =
            account?.let { TravelSavings(LedgerLogic.balance(it, entries), targetCents) }
    }
}
