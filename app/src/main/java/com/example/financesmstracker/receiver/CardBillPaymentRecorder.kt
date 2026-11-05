package com.example.financesmstracker.receiver

import android.content.Context
import android.util.Log
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.parser.TransactionType

/**
 * Records the card's own "payment received" confirmation as a credit on the
 * card. Shared by every route that can carry that message (SMS and the
 * Messages app's notifications), so the rules live in one place.
 */
object CardBillPaymentRecorder {
    private const val TAG = "CardBillPaymentRecorder"

    fun record(
        context: Context,
        timestamp: Long,
        smsHash: String,
        parsed: com.example.financesmstracker.parser.CardBillPaymentSms,
        onlyMatchMarked: Boolean
    ): String {
        val dbHelper = FinanceDatabaseHelper(context)
        val repository = TransactionRepository(dbHelper, context)
        try {
            // The payment may already be recorded: the user marked the paying bank
            // debit, or the same confirmation arrived by another route (SMS and a
            // Messages notification). Recording it again would lower the Bill twice.
            com.example.financesmstracker.transfer.CardPaymentMatch.findExisting(
                candidates = repository.getTransactionsByAmount(parsed.amountPaise),
                amountPaise = parsed.amountPaise,
                last4 = parsed.cardLastFour,
                bank = parsed.cardBank,
                aroundMs = timestamp,
                onlyManual = onlyMatchMarked
            )?.let { return "Matched a card payment already recorded (row ${it.id}); no new row" }

            val evidence = SourceEvidence(
                sourceType = SourceType.SMS,
                sourceKey = "sms-card-bill-payment:" + smsHash,
                receivedAt = timestamp,
                amountPaise = parsed.amountPaise,
                currency = "INR",
                direction = "CREDIT",
                bankProvider = parsed.cardBank,
                accountLastFour = parsed.cardLastFour,
                reference = parsed.reference,
                contentHash = smsHash,
                confidence = parsed.confidence,
                status = EvidenceStatus.MATCHED
            )

            val evidenceId = repository.insertSourceEvidence(evidence)
            if (evidenceId == -1L) {
                Log.d(TAG, "Duplicate card bill payment SMS skipped: " + smsHash)
                return "Skipped: card payment SMS already recorded"
            }

            val transaction = Transaction(
                amountPaise = parsed.amountPaise,
                currency = "INR",
                transactionType = TransactionType.CREDIT,
                paymentMethod = parsed.paymentMethod,
                accountType = com.example.financesmstracker.parser.AccountType.CREDIT_CARD,
                bank = parsed.cardBank,
                merchantName = "Credit Card Bill Payment",
                payeeId = null,
                accountLastFour = parsed.cardLastFour,
                refNumber = parsed.reference,
                timestamp = timestamp,
                smsHash = "card-bill-payment:" + smsHash,
                category = "TRANSFER",
                parserConfidence = parsed.confidence
            )

            val rowId = repository.insertTransaction(transaction)
            if (rowId == -1L) {
                Log.d(TAG, "Card bill payment transaction already exists")
                return "Skipped: card payment row already exists"
            }
            if (rowId == 0L) {
                return "NOT SAVED: older than your cleared local history"
            }

            repository.resolveEvidenceToTransaction(evidenceId, rowId)?.let {
                FinanceSyncBridge.enqueueEvidence(context, it)
            }

            repository.getTransactionById(rowId)?.let { canonical ->
                FinanceSyncBridge.enqueueCanonical(
                    context = context,
                    transaction = canonical,
                    evidence = repository.getSourceEvidenceById(evidenceId)
                )
            }

            Log.i(
                TAG,
                "CARD_BILL_PAYMENT_SMS_SAVED -> card=" + parsed.cardBank + "-" + parsed.cardLastFour +
                    ", amount=" + parsed.amountPaise +
                    ", method=" + parsed.paymentMethod +
                    ", ref=" + parsed.reference
            )
            val saved = repository.getTransactionById(rowId)
            return "Card payment saved: CREDIT ${parsed.cardBank} card ${parsed.cardLastFour} " +
                "(row $rowId, now ${saved?.accountType ?: "?"}/${saved?.transactionType ?: "?"} ${saved?.merchantName ?: "-"})"
        } finally {
            dbHelper.close()
        }
    }
}
