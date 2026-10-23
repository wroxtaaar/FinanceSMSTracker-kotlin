package com.example.financesmstracker.integration

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.evidence.SourceEvidence

object FinanceSyncMapper {
    fun toSyncTransaction(
        transaction: Transaction,
        scheme: OracleIds.Scheme = OracleIds.BARE
    ): SyncTransaction =
        SyncTransaction(
            localTransactionId = transaction.id,
            // A row mirrored from the server's Gmail ledger is known to the server by
            // its own id. Sending the phone's row number instead made the server
            // create a second row, or overwrite an unrelated one, on any re-sync
            // such as a category edit.
            serverId = FinanceSyncBridge.oracleVoidId(transaction.id, transaction.smsHash, scheme),
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

    fun toSyncEvidence(
        evidence: SourceEvidence,
        scheme: OracleIds.Scheme = OracleIds.BARE
    ): SyncEvidence =
        SyncEvidence(
            localEvidenceId = evidence.id,
            serverEvidenceId = scheme.evidenceId(evidence.id),
            serverTransactionId = evidence.transactionId?.let { scheme.transactionId(it) },
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
