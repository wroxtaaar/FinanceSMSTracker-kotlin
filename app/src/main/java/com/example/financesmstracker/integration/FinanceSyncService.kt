package com.example.financesmstracker.integration

import android.content.Context

/**
 * Coordinates durable enqueue + delivery. Call this after the Android-side
 * reconciliation has produced a transaction/evidence batch.
 */
class FinanceSyncService(context: Context) {
    private val appContext: Context = context.applicationContext
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
        var skippedInARow = 0
        while (true) {
            val payload = queue.peek() ?: break
            val result = client.send(payload)
            if (result.isSuccess) {
                queue.removeHead()
                sent++
                skippedInARow = 0
                continue
            }
            val error = result.exceptionOrNull()!!
            // A payload the server refuses outright can never succeed. Left at
            // the head of the queue it would hold back every later change, so
            // it is dropped and reported instead.
            if (isPermanentRejection(error)) {
                queue.removeHead()
                rejected = rejected ?: error
                continue
            }
            recordError(error)
            // The server failed on this one payload (HTTP 5xx). Put it at the back
            // and carry on with the others; stop once every item has failed in a
            // row. Network or sign-in failures stop at once, since nothing can go
            // through.
            if (!isServerFault(error)) return Result.failure(error)
            skippedInARow++
            if (skippedInARow >= queue.size()) return Result.failure(error)
            queue.rotate()
        }
        if (rejected != null) recordError(rejected)
        return if (rejected != null) Result.failure(rejected) else Result.success(sent)
    }

    private fun recordError(error: Throwable) {
        runCatching {
            appContext.getSharedPreferences("finance_sync_status", Context.MODE_PRIVATE).edit()
                .putString("last_flush_error", "${System.currentTimeMillis()}: ${error.message.orEmpty().take(300)}")
                .apply()
        }
    }

    companion object {
        /** True for "the server read it and said no" (not a network, sign-in, rate-limit or server fault). */
        fun isServerFault(error: Throwable): Boolean =
            Regex("""HTTP 5\d\d\b""").containsMatchIn(error.message.orEmpty())

        fun isPermanentRejection(error: Throwable): Boolean =
            Regex("""HTTP (400|404|409|410|413|422)\b""").containsMatchIn(error.message.orEmpty())
    }

    fun pendingCount(): Int = queue.size()
}
