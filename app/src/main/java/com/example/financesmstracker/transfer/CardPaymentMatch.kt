package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.TransactionType
import kotlin.math.abs

/**
 * A card bill payment can be recorded twice: by the user marking a bank debit
 * as a card payment, and by the card's own "payment received" message. Both
 * would lower the card's Bill, so whichever arrives second must find the first.
 */
object CardPaymentMatch {
    const val MANUAL_PREFIX = "manual-card-payment:"
    private const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    /** An existing credit on the same card, same amount, within three days. */
    fun findExisting(
        candidates: List<Transaction>,
        amountPaise: Long,
        last4: String?,
        aroundMs: Long,
        onlyManual: Boolean = false
    ): Transaction? {
        val digits = last4?.trim().orEmpty()
        if (digits.isEmpty()) return null
        return candidates.firstOrNull {
            it.transactionType == TransactionType.CREDIT &&
                it.accountType == AccountType.CREDIT_CARD &&
                it.amountPaise == amountPaise &&
                it.accountLastFour?.trim() == digits &&
                abs(it.timestamp - aroundMs) <= WINDOW_MS &&
                (!onlyManual || it.smsHash.startsWith(MANUAL_PREFIX))
        }
    }
}
