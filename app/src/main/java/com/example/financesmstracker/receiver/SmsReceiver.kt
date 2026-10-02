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
import com.example.financesmstracker.integration.SyncCardBill
import com.example.financesmstracker.parser.CardBillPaymentSmsParser
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
                    val smsHash = HashUtil.sha256(fullBody)

                    // A card-payment confirmation is a payment signal, not a
                    // generic debit/credit. Only accept it when the message
                    // explicitly says the payment was applied to a credit
                    // card. A normal "Sent/Debited from bank account" SMS
                    // must remain a bank transaction.
                    val cardBillPayment = CardBillPaymentSmsParser.parse(sender, fullBody)
                    if (cardBillPayment != null && trustStatus == SenderTrustStatus.TRUSTED) {
                        processCardBillPayment(
                            context = context,
                            timestamp = timestamp,
                            smsHash = smsHash,
                            parsed = cardBillPayment
                        )
                        val updateIntent = Intent(ACTION_TRANSACTION_DATA_CHANGED).apply {
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(updateIntent)
                        return
                    }

                    // A credit-card statement SMS is a bill signal, not a card
                    // purchase. Handle it before the normal transaction parser
                    // so phrases such as "Total amt ... Dr." can never become
                    // a fake DEBIT transaction.
                    val cardBill = CardBillStatementParser.parse(sender, fullBody)
                    if (cardBill != null && trustStatus == SenderTrustStatus.TRUSTED) {
                        val billKey = "sms-card-bill:" + smsHash
                        FinanceSyncBridge.enqueueCardBill(
                            context,
                            SyncCardBill(
                                sourceType = "SMS",
                                sourceKey = billKey,
                                timestamp = timestamp,
                                amountMinor = cardBill.amountPaise,
                                bank = cardBill.bank,
                                accountLastFour = cardBill.accountLastFour,
                                accountLastTwo = cardBill.accountLastTwo,
                                confidence = cardBill.confidence
                            )
                        )
                        Log.d(
                            TAG,
                            "CARD_BILL_SMS_DETECTED -> amount=" + cardBill.amountPaise +
                                ", bank=" + cardBill.bank +
                                ", last4=" + cardBill.accountLastFour +
                                ", last2=" + cardBill.accountLastTwo
                        )

                        val updateIntent = Intent(ACTION_TRANSACTION_DATA_CHANGED).apply {
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(updateIntent)
                        return
                    }

                    val parserManager = SmsParserManager()
                    val parserResult = parserManager.parse(sender, fullBody)

                    val dbHelper = FinanceDatabaseHelper(context)
                    val repository = TransactionRepository(dbHelper)

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
    private fun processCardBillPayment(
        context: Context,
        timestamp: Long,
        smsHash: String,
        parsed: com.example.financesmstracker.parser.CardBillPaymentSms
    ) {
        val dbHelper = FinanceDatabaseHelper(context)
        val repository = TransactionRepository(dbHelper)
        try {
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
                return
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
                return
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
        } finally {
            dbHelper.close()
        }
    }

    }
}
