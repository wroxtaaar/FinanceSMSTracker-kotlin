package com.example.financesmstracker.integration

import android.content.Context
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.evidence.SourceEvidence
import java.util.concurrent.Executors

/**
 * Connects the local reconciliation pipeline to the durable Oracle sync queue.
 *
 * Network delivery happens off the receiver/service thread. If delivery fails,
 * FinanceSyncService keeps the payload in its durable FIFO queue for a later flush.
 * Only canonical transactions and reconciled evidence should enter here.
 */
object FinanceSyncBridge {
    private val executor = Executors.newSingleThreadExecutor()

    fun enqueueCanonical(
        context: Context,
        transaction: Transaction,
        evidence: SourceEvidence? = null
    ) {
        enqueue(context, listOf(transaction), listOfNotNull(evidence))
    }

    fun enqueueEvidence(
        context: Context,
        evidence: SourceEvidence
    ) {
        enqueue(context, emptyList(), listOf(evidence))
    }

    private fun enqueue(
        context: Context,
        transactions: List<Transaction>,
        evidence: List<SourceEvidence>
    ) {
        if (transactions.isEmpty() && evidence.isEmpty()) return

        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val service = FinanceSyncService(appContext)
                service.enqueue(
                    transactions = transactions.map(FinanceSyncMapper::toSyncTransaction),
                    evidence = evidence.map(FinanceSyncMapper::toSyncEvidence)
                )
                service.flush()
            }
        }
    }
}
