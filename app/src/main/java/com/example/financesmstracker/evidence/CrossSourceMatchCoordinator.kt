package com.example.financesmstracker.evidence

import android.util.Log
import com.example.financesmstracker.data.TransactionRepository

class CrossSourceMatchCoordinator(private val repository: TransactionRepository) {

    fun onSourceEvidenceCreated(evidenceId: Long) {
        val evidence = repository.getSourceEvidenceById(evidenceId) ?: return
        if (evidence.status == EvidenceStatus.MATCHED && evidence.transactionId != null) {
            Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: $evidenceId, transactionId: ${evidence.transactionId}, result: SKIPPED_ALREADY_MATCHED, reasons: already matched")
            return
        }

        val candidateTransactions = repository.getTransactionsByAmount(evidence.amountPaise)
        val result = CrossSourceMatcher.match(evidence, candidateTransactions)

        repository.applyMatchResult(evidence.id, result)
    }

    fun onCanonicalTransactionCreated(transactionId: Long) {
        val transaction = repository.getTransactionById(transactionId) ?: return
        val unmatchedEvidence = repository.getUnmatchedOrAmbiguousEvidenceByAmount(transaction.amountPaise)

        for (evidence in unmatchedEvidence) {
            if (evidence.status == EvidenceStatus.MATCHED && evidence.transactionId != null) {
                Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: ${evidence.id}, transactionId: ${evidence.transactionId}, result: SKIPPED_ALREADY_MATCHED, reasons: already matched")
                continue
            }

            val candidateTransactions = repository.getTransactionsByAmount(evidence.amountPaise)
            val result = CrossSourceMatcher.match(evidence, candidateTransactions)

            repository.applyMatchResult(evidence.id, result)
        }
    }
}
