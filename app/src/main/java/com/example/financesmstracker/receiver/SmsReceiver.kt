package com.example.financesmstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.financesmstracker.categorizer.TransactionCategorizer
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.parser.SmsParserManager
import com.example.financesmstracker.util.HashUtil
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
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
                        val normalizedPayee = parserResult.payeeId?.trim()?.lowercase()
                        val deterministicCategory = TransactionCategorizer.categorize(parserResult, fullBody)
                        val rememberedCategory = normalizedPayee?.let { repository.getCategoryForPayee(it) }
                        val category = rememberedCategory ?: deterministicCategory

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
                            val rupees = parserResult.amountPaise / 100.0
                            val amountFormatted = String.format(Locale.US, "₹%.2f (%d paise)", rupees, parserResult.amountPaise)
                            Log.d(TAG, "Successfully persisted multipart transaction ID: $rowId, Amount: $amountFormatted, Category: $category")
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
