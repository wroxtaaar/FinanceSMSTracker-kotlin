package com.example.financesmstracker.evidence

import com.example.financesmstracker.data.Transaction
import kotlin.math.abs

enum class MatchOutcome {
    MATCHED,
    AMBIGUOUS,
    UNMATCHED
}

data class MatchResult(
    val outcome: MatchOutcome,
    val matchedTransactionId: Long? = null,
    val reasons: List<String> = emptyList(),
    val confidence: Float = 0.0f
)

object CrossSourceMatcher {
    // 120 seconds window to account for Truecaller notification delay relative to SMS
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

    private fun isDirectionCompatible(evidenceDir: String, txDir: String): Boolean {
        val normEv = evidenceDir.trim().uppercase()
        val normTx = txDir.trim().uppercase()
        if (normEv == "UNKNOWN" || normEv.isBlank()) return true
        return normEv == normTx
    }

    private fun isBankCompatible(evidenceBank: String?, txBank: String?): Boolean {
        val normEv = normalizeBank(evidenceBank) ?: return true // null means unknown (neutral)
        val normTx = normalizeBank(txBank) ?: return true // null means unknown (neutral)
        return normEv == normTx
    }

    fun match(evidence: SourceEvidence, transactions: List<Transaction>): MatchResult {
        if (evidence.amountPaise <= 0) {
            return MatchResult(MatchOutcome.UNMATCHED, reasons = listOf("Invalid or zero amount"))
        }

        val matchingCandidates = mutableListOf<Pair<Transaction, List<String>>>()

        for (tx in transactions) {
            val reasons = mutableListOf<String>()

            // 1. Amount check (must match exactly)
            if (tx.amountPaise != evidence.amountPaise) {
                continue
            }
            reasons.add("amount equal")

            // 2. Direction compatibility check (UNKNOWN is compatible with both)
            val txDirection = if (tx.transactionType.name == "CREDIT") "CREDIT" else "DEBIT"
            if (!isDirectionCompatible(evidence.direction, txDirection)) {
                continue
            }
            if (evidence.direction.trim().uppercase() != "UNKNOWN" && evidence.direction.isNotBlank()) {
                reasons.add("direction equal")
            } else {
                reasons.add("direction unknown (neutral)")
            }

            // 3. Reference check (Strongest identifier if available)
            if (!evidence.reference.isNullOrBlank() && !tx.refNumber.isNullOrBlank()) {
                if (evidence.reference.equals(tx.refNumber, ignoreCase = true)) {
                    reasons.add("same reference")
                }
            }

            // 4. Bank compatibility check (null bank is neutral)
            if (!isBankCompatible(evidence.bankProvider, tx.bank)) {
                continue
            }
            if (!evidence.bankProvider.isNullOrBlank()) {
                reasons.add("same bank")
            } else {
                reasons.add("bank unknown (neutral)")
            }

            // 5. Account last four check
            if (!evidence.accountLastFour.isNullOrBlank() && !tx.accountLastFour.isNullOrBlank()) {
                if (evidence.accountLastFour == tx.accountLastFour) {
                    reasons.add("same account last four")
                }
            }

            // 6. Time proximity check
            val timeDiff = abs(tx.timestamp - evidence.receivedAt)
            if (timeDiff <= MAX_TIME_DIFF_MILLIS) {
                reasons.add("event time difference ${timeDiff / 1000.0} seconds")
            } else {
                if (!reasons.contains("same reference")) {
                    continue
                }
            }

            matchingCandidates.add(Pair(tx, reasons))
        }

        return when {
            matchingCandidates.isEmpty() -> {
                MatchResult(MatchOutcome.UNMATCHED, reasons = listOf("no candidate found"))
            }
            matchingCandidates.size == 1 -> {
                val (tx, reasons) = matchingCandidates[0]
                MatchResult(MatchOutcome.MATCHED, matchedTransactionId = tx.id, reasons = reasons, confidence = 0.95f)
            }
            else -> {
                val refMatches = matchingCandidates.filter { it.second.contains("same reference") }
                if (refMatches.size == 1) {
                    MatchResult(MatchOutcome.MATCHED, matchedTransactionId = refMatches[0].first.id, reasons = refMatches[0].second, confidence = 0.98f)
                } else {
                    MatchResult(MatchOutcome.AMBIGUOUS, reasons = listOf("multiple plausible candidates found (${matchingCandidates.size})"))
                }
            }
        }
    }
}
