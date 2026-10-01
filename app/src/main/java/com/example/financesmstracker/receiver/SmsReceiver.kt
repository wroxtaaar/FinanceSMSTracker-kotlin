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
import com.example.financesmstracker.data.UnrecognizedSms
import com.example.financesmstracker.evidence.CrossSourceMatchCoordinator
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.parser.SenderTrustManager
import com.example.financesmstracker.parser.SenderTrustStatus
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
        Log.d(TAG, "SMS_RECEIVER_ENTERED -> action=${intent.action}")
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                Log.d(TAG, "SMS_RECEIVER_PARSED_INTENT -> messageParts=${messages?.size ?: 0}")
                if (!messages.isNullOrEmpty()) {
                    val sender = messages[0].originatingAddress ?: "UNKNOWN"
                    val timestamp = messages[0].timestampMillis
                    val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }

                    val trustStatus = SenderTrustManager.classifySender(sender)
                    val parserManager = SmsParserManager()
                    val parserResult = parserManager.parse(sender, fullBody)

                    val dbHelper = FinanceDatabaseHelper(context)
                    val repository = TransactionRepository(dbHelper)
                    val smsHash = HashUtil.sha256(fullBody)

                    val nonTransactionalMessage =
                        SenderTrustManager.isNonTransactionalFinancialMessage(fullBody)

                    val shouldCreateCanonicalTransaction =
                        !nonTransactionalMessage &&
                        parserResult.isTransaction &&
                        trustStatus == SenderTrustStatus.TRUSTED &&
                        parserResult.confidence >= 0.90f &&
                        parserResult.amountPaise > 0L &&
                        parserResult.transactionType != TransactionType.UNKNOWN

                    if (shouldCreateCanonicalTransaction) {
                        val memoryKey = CategoryMemoryKey.from(parserResult)
                        val rememberedCategory = memoryKey?.let { repository.getCategoryForMemoryKey(it) }
                        val category = rememberedCategory ?: TransactionCategorizer.categorize(parserResult, fullBody)

                        val transaction = Transaction(
                            amountPaise = parserResult.amountPaise,
                            currency = parserResult.currency,
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
                                currency = parserResult.currency,
                                direction = if (parserResult.transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT",
                                bankProvider = parserResult.bank,
                                accountLastFour = parserResult.accountLastFour,
                                reference = parserResult.refNumber,
                                contentHash = smsHash,
                                confidence = parserResult.confidence,
                                status = EvidenceStatus.MATCHED
                            )
                            val evidenceId = repository.insertSourceEvidence(evidence)
                            val persistedEvidence = if (evidenceId != -1L) {
                                repository.getSourceEvidenceById(evidenceId)
                            } else {
                                null
                            }
                            if (evidenceId != -1L) {
                                Log.d("FinanceSource", "SMS_EVIDENCE_CREATED -> evidenceId: $evidenceId, transactionId: $rowId, amount: ${parserResult.amountPaise}, currency: ${parserResult.currency}, direction: ${if (parserResult.transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT"}, bank: ${parserResult.bank}, timestamp: $timestamp, status: ${EvidenceStatus.MATCHED}")
                            }

                            val canonicalTransaction = repository.getTransactionById(rowId)
                            if (canonicalTransaction != null) {
                                FinanceSyncBridge.enqueueCanonical(
                                    context = context,
                                    transaction = canonicalTransaction,
                                    evidence = persistedEvidence
                                )
                            }

                            val coordinator = CrossSourceMatchCoordinator(
                                repository = repository,
                                onEvidenceReconciled = { reconciledEvidence ->
                                    FinanceSyncBridge.enqueueEvidence(context, reconciledEvidence)
                                }
                            )
                            coordinator.onCanonicalTransactionCreated(rowId)

                            repository.logNewestEvidenceSummary()

                            val rupees = parserResult.amountPaise / 100.0
                            val amountFormatted = String.format(Locale.US, "₹%.2f (%d paise)", rupees, parserResult.amountPaise)
                            Log.d(TAG, "Successfully persisted multipart transaction ID: $rowId, Amount: $amountFormatted, Category: $category")
                            Log.d("FinanceSource", "SMS_TRANSACTION_SAVED -> ID: $rowId, Amount: ${parserResult.amountPaise}, Currency: ${parserResult.currency}, Type: ${parserResult.transactionType}, Bank: ${parserResult.bank}, Timestamp: $timestamp")

                            val updateIntent = Intent(ACTION_TRANSACTION_DATA_CHANGED).apply {
                                setPackage(context.packageName)
                            }
                            context.sendBroadcast(updateIntent)
                        } else {
                            Log.d(TAG, "Duplicate SMS skipped (hash already exists): $smsHash")
                        }
                    } else {
                        // Never create a canonical transaction from a low-confidence or untrusted parse.
                        // Financial-looking messages are sent to Review & Reconcile instead.
                        if (!nonTransactionalMessage && SenderTrustManager.isFinancialLooking(fullBody)) {
                            val reason = when {
                                !parserResult.isTransaction ->
                                    "UNRECOGNIZED_FINANCIAL_SMS"
                                trustStatus != SenderTrustStatus.TRUSTED ->
                                    "UNTRUSTED_FINANCIAL_SMS"
                                parserResult.confidence < 0.90f ->
                                    "LOW_CONFIDENCE_FINANCIAL_SMS"
                                else ->
                                    "INCOMPLETE_FINANCIAL_SMS"
                            }

                            val unrecognized = UnrecognizedSms(
                                sender = sender,
                                receivedAt = timestamp,
                                contentHash = smsHash,
                                reason = reason
                            )
                            repository.insertUnrecognizedSms(unrecognized)
                        }
                    }
                    dbHelper.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received SMS pipeline", e)
            }
        }
    }
}
