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
        // Oracle Gmail transactions are local mirrors of rows already owned by
        // the Oracle ledger. They must never be sent back as new canonical
        // transactions, otherwise clearing local history and rehydrating the
        // mirror can apply the same bank/card balance more than once.
        if (transaction.smsHash.startsWith("oracle:gmail:")) return

        enqueue(context, listOf(transaction), listOfNotNull(evidence))
    }

    fun enqueueEvidence(
        context: Context,
        evidence: SourceEvidence
    ) {
        enqueue(context, emptyList(), listOf(evidence))
    }

    /**
     * Sends an existing transaction back to Oracle when a user edits metadata
     * such as its category. Unlike canonical ingestion, this intentionally
     * allows the Oracle-Gmail mirror prefix because the server treats an
     * existing ID as an update rather than a new balance event.
     */
    fun enqueueCategoryUpdate(
        context: Context,
        transaction: Transaction
    ) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val service = FinanceSyncService(appContext)
                service.enqueue(
                    transactions = listOf(FinanceSyncMapper.toSyncTransaction(transaction)),
                    evidence = emptyList()
                )
                service.flush()
            }
        }
    }

    fun enqueueCardBill(
        context: Context,
        bill: SyncCardBill
    ) {
        enqueueCardBills(context, listOf(bill))
    }

    fun enqueueVoidedTransaction(
        context: Context,
        transactionId: Long
    ) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val service = FinanceSyncService(appContext)
                service.enqueue(
                    transactions = emptyList(),
                    evidence = emptyList(),
                    voidedTransactionIds = listOf(transactionId)
                )
                service.flush()
            }
        }
    }

    private fun enqueueCardBills(
        context: Context,
        cardBills: List<SyncCardBill>
    ) {
        if (cardBills.isEmpty()) return
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val service = FinanceSyncService(appContext)
                service.enqueue(
                    transactions = emptyList(),
                    evidence = emptyList(),
                    cardBills = cardBills
                )
                service.flush()
            }
        }
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
