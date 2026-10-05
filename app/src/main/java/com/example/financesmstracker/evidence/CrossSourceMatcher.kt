package com.example.financesmstracker.evidence

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.util.TransactionReferenceNormalizer
import kotlin.math.abs

enum class MatchOutcome { MATCHED, AMBIGUOUS, UNMATCHED }

data class MatchResult(
    val outcome: MatchOutcome,
    val matchedTransactionId: Long? = null,
    val reasons: List<String> = emptyList(),
    val confidence: Float = 0.0f
)

object CrossSourceMatcher {
    private const val MAX_TIME_DIFF_MILLIS = 120_000L

    private fun normalizeBank(bank: String?): String? {
        if (bank.isNullOrBlank()) return null
        val upper = bank.trim().uppercase()
        return when {
            upper.contains("AXIS") -> "AXIS"
            upper.contains("HDFC") -> "HDFC"
            upper.contains("ICICI") -> "ICICI"
            upper.contains("SBI") -> "SBI"
            upper.contains("KOTAK") -> "KOTAK"
            upper.contains("PAYTM") -> "PAYTM"
            else -> upper.replace(" BANK", "").trim()
        }
    }

    private fun directionCompatible(evidence: SourceEvidence, tx: Transaction): Boolean {
        val direction = evidence.direction.trim().uppercase()
        return direction.isBlank() || direction == "UNKNOWN" || direction == tx.transactionType.name
    }

    private fun bankCompatible(evidence: SourceEvidence, tx: Transaction): Boolean {
        val evidenceBank = normalizeBank(evidence.bankProvider)
        val txBank = normalizeBank(tx.bank)
        return evidenceBank == null || txBank == null || evidenceBank == txBank
    }

    private fun accountCompatible(evidence: SourceEvidence, tx: Transaction): Boolean {
        val evidenceLast4 = evidence.accountLastFour?.trim()
        val txLast4 = tx.accountLastFour?.trim()
        return evidenceLast4.isNullOrBlank() || txLast4.isNullOrBlank() || evidenceLast4 == txLast4
    }

    fun match(evidence: SourceEvidence, transactions: List<Transaction>): MatchResult {
        if (evidence.amountPaise <= 0) {
            return MatchResult(MatchOutcome.UNMATCHED, reasons = listOf("Invalid or zero amount"))
        }

        val evidenceReference = TransactionReferenceNormalizer.normalize(evidence.reference)

        // PRIMARY IDENTITY: exact normalized reference/RRN/UTR.
        if (!evidenceReference.isNullOrBlank()) {
            val referenceCandidates = transactions.filter { tx ->
                TransactionReferenceNormalizer.normalize(tx.refNumber) == evidenceReference &&
                    tx.currency.equals(evidence.currency, ignoreCase = true) &&
                    directionCompatible(evidence, tx) &&
                    bankCompatible(evidence, tx) &&
                    accountCompatible(evidence, tx)
            }

            if (referenceCandidates.size == 1) {
                val tx = referenceCandidates.single()
                val reasons = buildList {
                    add("same reference")
                    if (evidence.amountPaise == tx.amountPaise) add("amount equal")
                    else add("amount differs; reference identity wins")
                    if (!evidence.bankProvider.isNullOrBlank() && !tx.bank.isNullOrBlank()) add("same bank")
                    if (!evidence.accountLastFour.isNullOrBlank() && !tx.accountLastFour.isNullOrBlank()) add("same account last four")
                }
                return MatchResult(MatchOutcome.MATCHED, tx.id, reasons, 0.99f)
            }

            if (referenceCandidates.size > 1) {
                fun score(tx: Transaction): Int = listOf(
                    evidence.amountPaise == tx.amountPaise,
                    !evidence.accountLastFour.isNullOrBlank() && evidence.accountLastFour == tx.accountLastFour,
                    normalizeBank(evidence.bankProvider) != null &&
                        normalizeBank(evidence.bankProvider) == normalizeBank(tx.bank)
                ).count { it }

                val ranked = referenceCandidates.sortedByDescending(::score)
                val top = ranked.first()
                if (score(top) > (ranked.getOrNull(1)?.let(::score) ?: -1)) {
                    return MatchResult(
                        MatchOutcome.MATCHED,
                        top.id,
                        listOf("same reference", "reference candidate disambiguated by account/bank/amount"),
                        0.99f
                    )
                }
                return MatchResult(
                    MatchOutcome.AMBIGUOUS,
                    reasons = listOf("multiple transactions share the same reference")
                )
            }
        }

        // FALLBACK IDENTITY: only when no reference exists.
        val matchingCandidates = transactions.filter { tx ->
            tx.currency.equals(evidence.currency, ignoreCase = true) &&
                tx.amountPaise == evidence.amountPaise &&
                directionCompatible(evidence, tx) &&
                bankCompatible(evidence, tx) &&
                accountCompatible(evidence, tx) &&
                abs(tx.timestamp - evidence.receivedAt) <= MAX_TIME_DIFF_MILLIS
        }

        return when {
            matchingCandidates.isEmpty() ->
                MatchResult(MatchOutcome.UNMATCHED, reasons = listOf("no reference and no amount/time candidate"))
            matchingCandidates.size == 1 -> {
                val tx = matchingCandidates.single()
                MatchResult(
                    MatchOutcome.MATCHED,
                    tx.id,
                    listOf("amount equal", "direction equal or unknown", "within 120 seconds"),
                    0.95f
                )
            }
            else ->
                MatchResult(
                    MatchOutcome.AMBIGUOUS,
                    reasons = listOf("multiple amount/time candidates found")
                )
        }
    }
}
