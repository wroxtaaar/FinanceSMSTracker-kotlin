package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.TransactionReferenceNormalizer
import kotlin.math.abs

/**
 * Finds conservative candidates for internal bank-account transfers.
 *
 * Reference/UTR matches are the strongest signal because the same bank-transfer
 * reference identifies both sides of the same transfer. Amount/time matching
 * remains the fallback when a reference is unavailable on one or both sides.
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
                    val sameTransferCore =
                        credit.id != debit.id &&
                            credit.currency.equals(debit.currency, ignoreCase = true) &&
                            credit.amountPaise == debit.amountPaise &&
                            !sameAccount(debit, credit)

                    if (!sameTransferCore) {
                        false
                    } else {
                        val referenceMatch = isReferenceMatch(debit, credit)
                        val timeMatch = abs(credit.timestamp - debit.timestamp) <= windowMillis
                        referenceMatch || timeMatch
                    }
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

    private fun isReferenceMatch(a: Transaction, b: Transaction): Boolean {
        val referenceA = TransactionReferenceNormalizer.normalize(a.refNumber)
        val referenceB = TransactionReferenceNormalizer.normalize(b.refNumber)
        return !referenceA.isNullOrBlank() &&
            !referenceB.isNullOrBlank() &&
            referenceA.equals(referenceB, ignoreCase = true)
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
