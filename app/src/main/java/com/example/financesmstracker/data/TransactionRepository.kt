package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

class TransactionRepository(private val dbHelper: FinanceDatabaseHelper) {

    fun insertTransaction(transaction: Transaction): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountPaise)
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType.name)
            put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod.name)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType.name)
            put(FinanceDatabaseHelper.COLUMN_BANK, transaction.bank)
            put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantName)
            put(FinanceDatabaseHelper.COLUMN_PAYEE_ID, transaction.payeeId)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLastFour)
            put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, transaction.refNumber)
            put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, transaction.timestamp)
            put(FinanceDatabaseHelper.COLUMN_SMS_HASH, transaction.smsHash)
            put(FinanceDatabaseHelper.COLUMN_CATEGORY, transaction.category)
            put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, transaction.parserConfidence)
        }
        return db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun getTransactionById(id: Long): Transaction? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString()),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToTransaction(it)
            }
        }
        return null
    }

    fun getAllTransactions(): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            null, null, null, null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }
        return list
    }

    fun updateTransactionCategory(id: Long, category: String): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_CATEGORY, category)
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            values,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun updateCategoriesForPayee(payeeId: String, category: String): Int {
        if (payeeId.isBlank()) return 0
        val normalizedPayee = payeeId.trim().lowercase()
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_CATEGORY, category)
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            values,
            "${FinanceDatabaseHelper.COLUMN_PAYEE_ID} = ?",
            arrayOf(normalizedPayee)
        )
    }

    fun deleteTransaction(id: Long): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun getTransactionsByPayee(payeeId: String): List<Transaction> {
        val normalizedPayee = payeeId.trim().lowercase()
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_PAYEE_ID} = ?",
            arrayOf(normalizedPayee),
            null, null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }
        return list
    }

    fun getTransactionsByDateRange(startTime: Long, endTime: Long): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} BETWEEN ? AND ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }
        return list
    }

    fun savePayeeCategoryMapping(payeeId: String, category: String) {
        if (payeeId.isBlank()) return
        val normalizedPayee = payeeId.trim().lowercase()
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_MAPPING_PAYEE_ID, normalizedPayee)
            put(FinanceDatabaseHelper.COLUMN_MAPPING_CATEGORY, category)
        }
        db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_PAYEE_MAPPINGS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getCategoryForPayee(payeeId: String): String? {
        if (payeeId.isBlank()) return null
        val normalizedPayee = payeeId.trim().lowercase()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_PAYEE_MAPPINGS,
            arrayOf(FinanceDatabaseHelper.COLUMN_MAPPING_CATEGORY),
            "${FinanceDatabaseHelper.COLUMN_MAPPING_PAYEE_ID} = ?",
            arrayOf(normalizedPayee),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }

    private fun cursorToTransaction(cursor: Cursor): Transaction {
        return Transaction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_ID)),
            amountPaise = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE)),
            transactionType = TransactionType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE))),
            paymentMethod = PaymentMethod.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD))),
            accountType = AccountType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE))),
            bank = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_BANK)),
            merchantName = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME)),
            payeeId = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_PAYEE_ID)),
            accountLastFour = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR)),
            refNumber = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_REF_NUMBER)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_TIMESTAMP)),
            smsHash = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_SMS_HASH)),
            category = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_CATEGORY)),
            parserConfidence = cursor.getFloat(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE))
        )
    }
}
