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
        voidedTransactionIds: List<Long> = emptyList()
    ) {
        queue.enqueue(FinanceSyncPayload.build(transactions, evidence, voidedTransactionIds))
    }

    fun flush(): Result<Int> {
        var sent = 0
        while (true) {
            val payload = queue.peek() ?: return Result.success(sent)
            val result = client.send(payload)
            if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)
            queue.removeHead()
            sent++
        }
    }

    fun pendingCount(): Int = queue.size()
}
