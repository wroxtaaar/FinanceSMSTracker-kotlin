package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.TransactionType
import kotlin.math.abs

/**
 * Finds conservative candidates for internal bank-account transfers.
 *
 * This does not classify a transfer as fact. The central ledger must confirm
 * the pair using its wider account/evidence context.
 */
object InternalTransferCandidateDetector {
    const val DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L

    fun findCandidates(
        transactions: List<Transaction>,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS
    ): List<InternalTransferCandidate> {
        require(windowMillis >= 0) { "windowMillis must be non-negative" }

        val debits = transactions.filter {
            it.accountType == AccountType.BANK_ACCOUNT &&
                it.transactionType == TransactionType.DEBIT
        }

        val credits = transactions.filter {
            it.accountType == AccountType.BANK_ACCOUNT &&
                it.transactionType == TransactionType.CREDIT
        }

        return debits.flatMap { debit ->
            credits
                .asSequence()
                .filter { credit ->
                    credit.id != debit.id &&
                        credit.currency.equals(debit.currency, ignoreCase = true) &&
                        credit.amountPaise == debit.amountPaise &&
                        abs(credit.timestamp - debit.timestamp) <= windowMillis &&
                        !sameAccount(debit, credit)
                }
                .map { credit ->
                    InternalTransferCandidate(
                        debit = debit,
                        credit = credit,
                        timeDifferenceMillis = abs(credit.timestamp - debit.timestamp)
                    )
                }
                .toList()
        }
        .sortedWith(
            compareBy<InternalTransferCandidate> { it.timeDifferenceMillis }
                .thenBy { it.debit.timestamp }
        )
    }

    private fun sameAccount(a: Transaction, b: Transaction): Boolean {
        val sameBank = !a.bank.isNullOrBlank() &&
            !b.bank.isNullOrBlank() &&
            a.bank.equals(b.bank, ignoreCase = true)

        val sameLastFour = !a.accountLastFour.isNullOrBlank() &&
            !b.accountLastFour.isNullOrBlank() &&
            a.accountLastFour == b.accountLastFour

        return sameBank && sameLastFour
    }
}
