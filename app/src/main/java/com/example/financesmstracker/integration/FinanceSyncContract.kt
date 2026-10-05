package com.example.financesmstracker.integration

import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

/**
 * Versioned, transport-neutral contract for sending Android evidence to the
 * central finance service.
 *
 * This intentionally contains no HTTP/client implementation. The server
 * contract must be selected before credentials, endpoints, retries, or a
 * permanent wire format are introduced.
 */
object FinanceSyncContract {
    const val VERSION = 1
}

data class SyncTransaction(
    val localTransactionId: Long,
    val amountMinor: Long,
    val currency: String,
    val transactionType: TransactionType,
    val paymentMethod: PaymentMethod,
    val accountType: AccountType,
    val bank: String?,
    val merchantName: String?,
    val payeeId: String?,
    val accountLastFour: String?,
    val reference: String?,
    val timestamp: Long,
    val category: String?,
    val parserConfidence: Float
)

/**
 * A locally detected candidate for an own-account transfer.
 *
 * This is deliberately a candidate rather than a final ledger classification.
 * Oracle remains authoritative and must confirm the pair before excluding it
 * from Splitwise/other income or expense calculations.
 */
data class SyncInternalTransferCandidate(
    val debitTransactionId: Long,
    val creditTransactionId: Long,
    val amountMinor: Long,
    val currency: String,
    val timeDifferenceMillis: Long,
    val matchType: InternalTransferMatchType
)

enum class InternalTransferMatchType {
    REFERENCE,
    AMOUNT_TIME
}

data class SyncEvidence(
    val localEvidenceId: Long,
    val sourceType: SourceType,
    val sourceKey: String,
    val receivedAt: Long,
    val localTransactionId: Long?,
    val amountMinor: Long,
    val currency: String,
    val direction: String,
    val bankProvider: String?,
    val accountLastFour: String?,
    val reference: String?,
    val contentHash: String,
    val confidence: Float,
    val status: EvidenceStatus
)


data class SyncCardBill(
    val sourceType: String,
    val sourceKey: String,
    val timestamp: Long,
    val amountMinor: Long?,
    val bank: String?,
    val accountLastFour: String?,
    val accountLastTwo: String?,
    val confidence: Float
)
