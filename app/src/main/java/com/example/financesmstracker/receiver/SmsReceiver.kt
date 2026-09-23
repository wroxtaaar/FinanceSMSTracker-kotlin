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
import com.example.financesmstracker.parser.SmsParserManager
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        const val ACTION_TRANSACTION_DATA_CHANGED =
            "com.example.financesmstracker.TRANSACTION_DATA_CHANGED"

        private val CREDIT_CATEGORIES = setOf(
            "SALARY",
            "TRANSFER",
            "REFUND"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isNullOrEmpty()) {
                return
            }

            val sender = messages[0].originatingAddress ?: "UNKNOWN"
            val timestamp = messages[0].timestampMillis

            val fullBody = messages.joinToString(separator = "") {
                it.messageBody ?: ""
            }

            val parserManager = SmsParserManager()
            val parserResult = parserManager.parse(sender, fullBody)

            if (!parserResult.isTransaction) {
                return
            }

            val dbHelper = FinanceDatabaseHelper(context)
            val repository = TransactionRepository(dbHelper)

            try {
                val smsHash = HashUtil.sha256(fullBody)

                val memoryKey = CategoryMemoryKey.from(parserResult)

                val deterministicCategory =
                    TransactionCategorizer.categorize(
                        parserResult,
                        fullBody
                    )

                val rememberedCategory =
                    memoryKey?.let {
                        repository.getCategoryForMemoryKey(it)
                    }

                val validRememberedCategory =
                    when {
                        rememberedCategory.isNullOrBlank() -> null

                        parserResult.transactionType == TransactionType.CREDIT &&
                            rememberedCategory !in CREDIT_CATEGORIES -> null

                        else -> rememberedCategory
                    }

                val category =
                    validRememberedCategory ?: deterministicCategory

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
                    Log.d(
                        TAG,
                        "Transaction inserted: id=$rowId, memoryKey=$memoryKey, category=$category"
                    )

                    val refreshIntent =
                        Intent(ACTION_TRANSACTION_DATA_CHANGED).apply {
                            `package` = context.packageName
                        }

                    context.sendBroadcast(refreshIntent)
                } else {
                    Log.d(
                        TAG,
                        "Transaction ignored, probably duplicate SMS: memoryKey=$memoryKey"
                    )
                }
            } finally {
                dbHelper.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }
}
