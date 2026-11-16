package com.example.financesmstracker.integration

import android.content.Context

/**
 * Coordinates durable enqueue + delivery. Call this after the Android-side
 * reconciliation has produced a transaction/evidence batch.
 */
class FinanceSyncService(context: Context) {
    private val queue = FinanceSyncQueue(context)
    private val client = FinanceSyncClient(context)

    fun enqueue(
        transactions: List<SyncTransaction>,
        evidence: List<SyncEvidence>,
        internalTransferCandidates: List<SyncInternalTransferCandidate> = emptyList(),
        voidedTransactionIds: List<String> = emptyList(),
        cardBills: List<SyncCardBill> = emptyList()
    ) {
        queue.enqueue(
            FinanceSyncPayload.build(
                transactions = transactions,
                evidence = evidence,
                internalTransferCandidates = internalTransferCandidates,
                voidedTransactionIds = voidedTransactionIds,
                cardBills = cardBills
            )
        )
    }

    fun flush(): Result<Int> {
        var sent = 0
        var rejected: Throwable? = null
        while (true) {
            val payload = queue.peek() ?: break
            val result = client.send(payload)
            if (result.isFailure) {
                val error = result.exceptionOrNull()!!
                // A payload the server refuses outright can never succeed. Left at
                // the head of the queue it would hold back every later change, so
                // it is dropped and reported instead.
                if (!isPermanentRejection(error)) return Result.failure(error)
                rejected = rejected ?: error
            }
            queue.removeHead()
            if (result.isSuccess) sent++
        }
        return if (rejected != null) Result.failure(rejected) else Result.success(sent)
    }

    companion object {
        /** True for "the server read it and said no" (not a network, sign-in, rate-limit or server fault). */
        fun isPermanentRejection(error: Throwable): Boolean =
            Regex("""HTTP (400|404|409|410|413|422)\b""").containsMatchIn(error.message.orEmpty())
    }

    fun pendingCount(): Int = queue.size()
}
