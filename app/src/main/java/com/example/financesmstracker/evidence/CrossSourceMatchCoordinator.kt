package com.example.financesmstracker.evidence

import android.util.Log
import com.example.financesmstracker.data.TransactionRepository

class CrossSourceMatchCoordinator(
    private val repository: TransactionRepository,
    private val onEvidenceReconciled: ((SourceEvidence) -> Unit)? = null
) {

    fun onSourceEvidenceCreated(evidenceId: Long) {
        val evidence = repository.getSourceEvidenceById(evidenceId) ?: return
        if (evidence.status == EvidenceStatus.MATCHED && evidence.transactionId != null) {
            Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: $evidenceId, transactionId: ${evidence.transactionId}, result: SKIPPED_ALREADY_MATCHED, reasons: already matched")
            return
        }

        // Reference is the first lookup. If the canonical transaction has not
        // received the reference yet, fall back to amount candidates so the
        // arriving reference can enrich that canonical row.
        val referenceCandidates = if (!evidence.reference.isNullOrBlank()) {
            repository.getTransactionsByReference(evidence.reference)
        } else {
            emptyList()
        }
        val candidateTransactions = if (referenceCandidates.isNotEmpty()) {
            referenceCandidates
        } else {
            repository.getTransactionsByAmount(evidence.amountPaise)
        }
        val result = CrossSourceMatcher.match(evidence, candidateTransactions)

        repository.applyMatchResult(evidence.id, result)
        notifyIfReconciled(evidence.id)
    }

    fun onCanonicalTransactionCreated(transactionId: Long) {
        val transaction = repository.getTransactionById(transactionId) ?: return
        val unmatchedEvidence = if (!transaction.refNumber.isNullOrBlank()) {
            repository.getUnmatchedOrAmbiguousEvidenceByReference(transaction.refNumber)
        } else {
            repository.getUnmatchedOrAmbiguousEvidenceByAmount(transaction.amountPaise)
        }

        for (evidence in unmatchedEvidence) {
            if (evidence.status == EvidenceStatus.MATCHED && evidence.transactionId != null) {
                Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: ${evidence.id}, transactionId: ${evidence.transactionId}, result: SKIPPED_ALREADY_MATCHED, reasons: already matched")
                continue
            }

            val referenceCandidates = if (!evidence.reference.isNullOrBlank()) {
                repository.getTransactionsByReference(evidence.reference)
            } else {
                emptyList()
            }
            val candidateTransactions = if (referenceCandidates.isNotEmpty()) {
                referenceCandidates
            } else {
                repository.getTransactionsByAmount(evidence.amountPaise)
            }
            val result = CrossSourceMatcher.match(evidence, candidateTransactions)

            repository.applyMatchResult(evidence.id, result)
            notifyIfReconciled(evidence.id)
        }
    }

    private fun notifyIfReconciled(evidenceId: Long) {
        val updated = repository.getSourceEvidenceById(evidenceId) ?: return
        if (updated.status == EvidenceStatus.MATCHED && updated.transactionId != null) {
            onEvidenceReconciled?.invoke(updated)
        }
    }
}
