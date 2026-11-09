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
    const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    /** An existing credit on the same card, same amount, within three days. */
    fun findExisting(
        candidates: List<Transaction>,
        amountPaise: Long,
        last4: String?,
        aroundMs: Long,
        onlyManual: Boolean = false,
        bank: String? = null
    ): Transaction? = findAll(candidates, amountPaise, last4, aroundMs, onlyManual, bank).firstOrNull()

    /** Every such credit; several equal part payments are normal, so callers may need the count. */
    fun findAll(
        candidates: List<Transaction>,
        amountPaise: Long,
        last4: String?,
        aroundMs: Long,
        onlyManual: Boolean = false,
        bank: String? = null
    ): List<Transaction> {
        val digits = last4?.trim().orEmpty()
        val issuer = bank?.trim().orEmpty()
        // A message that names no card digits can only be matched by issuer.
        if (digits.isEmpty() && issuer.isEmpty()) return emptyList()
        return candidates.filter {
            it.transactionType == TransactionType.CREDIT &&
                it.accountType == AccountType.CREDIT_CARD &&
                it.amountPaise == amountPaise &&
                (if (digits.isNotEmpty()) it.accountLastFour?.trim() == digits
                else it.bank?.trim().equals(issuer, ignoreCase = true)) &&
                abs(it.timestamp - aroundMs) <= WINDOW_MS &&
                (!onlyManual || it.smsHash.startsWith(MANUAL_PREFIX))
        }
    }
}
