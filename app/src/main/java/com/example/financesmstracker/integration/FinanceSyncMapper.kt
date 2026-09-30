package com.example.financesmstracker.integration

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.evidence.SourceEvidence

object FinanceSyncMapper {
    fun toSyncTransaction(transaction: Transaction): SyncTransaction =
        SyncTransaction(
            localTransactionId = transaction.id,
            amountMinor = transaction.amountPaise,
            currency = transaction.currency,
            transactionType = transaction.transactionType,
            paymentMethod = transaction.paymentMethod,
            accountType = transaction.accountType,
            bank = transaction.bank,
            merchantName = transaction.merchantName,
            payeeId = transaction.payeeId,
            accountLastFour = transaction.accountLastFour,
            reference = transaction.refNumber,
            timestamp = transaction.timestamp,
            category = transaction.category,
            parserConfidence = transaction.parserConfidence
        )

    fun toSyncEvidence(evidence: SourceEvidence): SyncEvidence =
        SyncEvidence(
            localEvidenceId = evidence.id,
            sourceType = evidence.sourceType,
            sourceKey = evidence.sourceKey,
            receivedAt = evidence.receivedAt,
            localTransactionId = evidence.transactionId,
            amountMinor = evidence.amountPaise,
            currency = evidence.currency,
            direction = evidence.direction,
            bankProvider = evidence.bankProvider,
            accountLastFour = evidence.accountLastFour,
            reference = evidence.reference,
            contentHash = evidence.contentHash,
            confidence = evidence.confidence,
            status = evidence.status
        )
}
