package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

class ManualTransactionRepository(private val dbHelper: FinanceDatabaseHelper) {
    data class Result(val transactionId: Long, val evidenceId: Long)

    fun createFromUnrecognized(
        unrecognized: UnrecognizedSms, amountPaise: Long, transactionType: TransactionType,
        paymentMethod: PaymentMethod, accountType: AccountType, bank: String,
        merchantName: String?, accountLastFour: String?, reference: String?, category: String?
    ): Result? {
        if (amountPaise <= 0L || bank.isBlank() || transactionType == TransactionType.UNKNOWN || accountType == AccountType.UNKNOWN) return null
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val tx = ContentValues().apply {
                put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, amountPaise)
                put(FinanceDatabaseHelper.COLUMN_CURRENCY, "INR")
                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transactionType.name)
                put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, paymentMethod.name)
                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, accountType.name)
                put(FinanceDatabaseHelper.COLUMN_BANK, bank.trim())
                put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, merchantName?.trim()?.takeIf { it.isNotEmpty() })
                put(FinanceDatabaseHelper.COLUMN_PAYEE_ID, null as String?)
                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, accountLastFour?.trim()?.takeIf { it.isNotEmpty() })
                put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, reference?.trim()?.takeIf { it.isNotEmpty() })
                put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, unrecognized.receivedAt)
                put(FinanceDatabaseHelper.COLUMN_SMS_HASH, unrecognized.contentHash)
                put(FinanceDatabaseHelper.COLUMN_CATEGORY, category?.trim()?.takeIf { it.isNotEmpty() } ?: "OTHER")
                put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, 1.0f)
            }
            val transactionId = db.insertWithOnConflict(FinanceDatabaseHelper.TABLE_TRANSACTIONS, null, tx, SQLiteDatabase.CONFLICT_IGNORE)
            if (transactionId == -1L) return null

            val evidence = ContentValues().apply {
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_TYPE, SourceType.SMS.name)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_KEY, unrecognized.contentHash)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT, unrecognized.receivedAt)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, transactionId)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE, amountPaise)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CURRENCY, "INR")
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_DIRECTION, if (transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT")
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_BANK_PROVIDER, bank.trim())
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR, accountLastFour?.trim()?.takeIf { it.isNotEmpty() })
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_REFERENCE, reference?.trim()?.takeIf { it.isNotEmpty() })
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONTENT_HASH, unrecognized.contentHash)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONFIDENCE, 1.0f)
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, EvidenceStatus.MATCHED.name)
            }
            val evidenceId = db.insertWithOnConflict(FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE, null, evidence, SQLiteDatabase.CONFLICT_IGNORE)
            if (evidenceId == -1L) return null

            val status = ContentValues().apply { put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS, ReviewStatus.RESOLVED.name) }
            val updated = db.update(FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS, status,
                FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID + " = ? AND " + FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS + " = ?",
                arrayOf(unrecognized.id.toString(), ReviewStatus.REVIEW.name))
            if (updated != 1) return null
            db.setTransactionSuccessful()
            return Result(transactionId, evidenceId)
        } finally { db.endTransaction() }
    }
}