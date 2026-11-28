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
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.CashbackSms
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

/** Records a card cashback as a CREDIT on the card in the CASHBACK category (SMS and Messages notification routes). */
object CashbackRecorder {
    private const val TAG = "CashbackRecorder"

    fun record(context: Context, timestamp: Long, smsHash: String, parsed: CashbackSms): String {
        val dbHelper = FinanceDatabaseHelper(context)
        val repository = TransactionRepository(dbHelper, context)
        try {
            val evidence = SourceEvidence(
                sourceType = SourceType.SMS,
                sourceKey = "sms-cashback:" + smsHash,
                receivedAt = timestamp,
                amountPaise = parsed.amountPaise,
                currency = "INR",
                direction = "CREDIT",
                bankProvider = parsed.cardBank,
                accountLastFour = parsed.cardLastFour,
                reference = null,
                contentHash = smsHash,
                confidence = parsed.confidence,
                status = EvidenceStatus.MATCHED
            )
            val evidenceId = repository.insertSourceEvidence(evidence)
            if (evidenceId == -1L) return "Skipped: cashback SMS already recorded"

            val transaction = Transaction(
                amountPaise = parsed.amountPaise,
                currency = "INR",
                transactionType = TransactionType.CREDIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.CREDIT_CARD,
                bank = parsed.cardBank,
                merchantName = "Cashback",
                payeeId = null,
                accountLastFour = parsed.cardLastFour,
                refNumber = null,
                timestamp = timestamp,
                smsHash = "cashback:" + smsHash,
                category = "CASHBACK",
                parserConfidence = parsed.confidence
            )
            val rowId = repository.insertTransaction(transaction)
            if (rowId == -1L) return "Skipped: cashback row already exists"
            if (rowId == 0L) return "NOT SAVED: older than your cleared local history"

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
            Log.i(TAG, "CASHBACK_SMS_SAVED -> card=${parsed.cardBank}-${parsed.cardLastFour}, amount=${parsed.amountPaise}")
            return "Cashback saved: CREDIT ${parsed.cardBank} card ${parsed.cardLastFour} (row $rowId, category CASHBACK)"
        } finally {
            dbHelper.close()
        }
    }
}
