package com.example.financesmstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.financesmstracker.categorizer.CategoryMemoryKey
import com.example.financesmstracker.categorizer.TransactionCategorizer
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.evidence.CrossSourceMatchCoordinator
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.SmsParserManager
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
        const val ACTION_TRANSACTION_DATA_CHANGED = "com.example.financesmstracker.ACTION_TRANSACTION_DATA_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (!messages.isNullOrEmpty()) {
                    val sender = messages[0].originatingAddress ?: "UNKNOWN"
                    val timestamp = messages[0].timestampMillis
                    val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }

                    val parserManager = SmsParserManager()
                    val parserResult = parserManager.parse(sender, fullBody)
                    if (parserResult.isTransaction) {
                        val dbHelper = FinanceDatabaseHelper(context)
                        val repository = TransactionRepository(dbHelper)

                        val smsHash = HashUtil.sha256(fullBody)
                        val memoryKey = CategoryMemoryKey.from(parserResult)
                        val rememberedCategory = memoryKey?.let { repository.getCategoryForMemoryKey(it) }
                        val category = rememberedCategory ?: TransactionCategorizer.categorize(parserResult, fullBody)

                        val transaction = Transaction(
                            amountPaise = parserResult.amountPaise,
                            transactionType = parserResult.transactionType,
                            paymentMethod = parserResult.paymentMethod,
                            accountType = parserResult.accountType,
                            bank = parserResult.bank,
                            merchantName = parserResult.merchantName,
                            payeeId = parserResult.payeeId,
                            accountLastFour = parserResult.accountLastFour,
                            refNumber = parserResult.refNumber,
                            timestamp = timestamp,
                            smsHash = smsHash,
                            category = category,
                            parserConfidence = parserResult.confidence
                        )

                        val rowId = repository.insertTransaction(transaction)
                        if (rowId != -1L) {
                            val evidence = SourceEvidence(
                                sourceType = SourceType.SMS,
                                sourceKey = smsHash,
                                receivedAt = timestamp,
                                transactionId = rowId,
                                amountPaise = parserResult.amountPaise,
                                direction = if (parserResult.transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT",
                                bankProvider = parserResult.bank,
                                accountLastFour = parserResult.accountLastFour,
                                reference = parserResult.refNumber,
                                contentHash = smsHash,
                                confidence = parserResult.confidence,
                                status = EvidenceStatus.MATCHED
                            )
                            val evidenceId = repository.insertSourceEvidence(evidence)
                            if (evidenceId != -1L) {
                                Log.d("FinanceSource", "SMS_EVIDENCE_CREATED -> evidenceId: $evidenceId, transactionId: $rowId, amount: ${parserResult.amountPaise}, direction: ${if (parserResult.transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT"}, bank: ${parserResult.bank}, timestamp: $timestamp, status: ${EvidenceStatus.MATCHED}")
                            }

                            // Event-driven matching: evaluate any unmatched/ambiguous Truecaller evidence against this new canonical transaction
                            val coordinator = CrossSourceMatchCoordinator(repository)
                            coordinator.onCanonicalTransactionCreated(rowId)

                            repository.logNewestEvidenceSummary()

                            val rupees = parserResult.amountPaise / 100.0
                            val amountFormatted = String.format(Locale.US, "₹%.2f (%d paise)", rupees, parserResult.amountPaise)
                            Log.d(TAG, "Successfully persisted multipart transaction ID: $rowId, Amount: $amountFormatted, Category: $category")
                            Log.d("FinanceSource", "SMS_TRANSACTION_SAVED -> ID: $rowId, Amount: ${parserResult.amountPaise}, Type: ${parserResult.transactionType}, Bank: ${parserResult.bank}, Timestamp: $timestamp")

                            // Notify UI that new transaction data was saved
                            val updateIntent = Intent(ACTION_TRANSACTION_DATA_CHANGED).apply {
                                setPackage(context.packageName)
                            }
                            context.sendBroadcast(updateIntent)
                        } else {
                            Log.d(TAG, "Duplicate SMS skipped (hash already exists): $smsHash")
                        }
                        dbHelper.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received SMS pipeline", e)
            }
        }
    }
}
