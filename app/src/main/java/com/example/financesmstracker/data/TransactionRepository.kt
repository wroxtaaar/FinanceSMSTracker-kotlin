package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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

        val rowId = db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        if (rowId != -1L) {
            Log.d("FinanceSource", "TRANSACTION_REPOSITORY_INSERT -> RowID: $rowId, Amount: ${transaction.amountPaise}, Type: ${transaction.transactionType}, Bank: ${transaction.bank}, Timestamp: ${transaction.timestamp}")
        }

        return rowId
    }

    fun getTransactionById(id: Long): Transaction? {
        val db = dbHelper.readableDatabase

        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
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
            null,
            null,
            null,
            null,
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

    fun updateCategoriesForMemoryKey(memoryKey: String, category: String): Int {
        if (memoryKey.isBlank()) return 0

        val normalizedKey = memoryKey.trim().lowercase()
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_CATEGORY, category)
        }

        return when {
            normalizedKey.startsWith("VPA|") -> {
                val payeeId = normalizedKey.removePrefix("vpa|")

                db.update(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    values,
                    "LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_PAYEE_ID})) = ?",
                    arrayOf(payeeId)
                )
            }

            normalizedKey.startsWith("BANK|") -> {
                val parts = normalizedKey.split("|")

                if (parts.size != 4) {
                    0
                } else {
                    val bank = parts[1]
                    val accountType = parts[2].uppercase()
                    val lastFour = parts[3]

                    db.update(
                        FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                        values,
                        """
                        LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_BANK})) = ?
                        AND ${FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE} = ?
                        AND TRIM(${FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR}) = ?
                        """.trimIndent(),
                        arrayOf(bank, accountType, lastFour)
                    )
                }
            }

            else -> 0
        }
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
            "LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_PAYEE_ID})) = ?",
            arrayOf(normalizedPayee),
            null,
            null,
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
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
        }

        return list
    }

    fun saveCategoryMemory(memoryKey: String, category: String) {
        if (memoryKey.isBlank()) return

        val normalizedKey = memoryKey.trim().lowercase()
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_MEMORY_KEY, normalizedKey)
            put(FinanceDatabaseHelper.COLUMN_MEMORY_CATEGORY, category)
        }

        db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_CATEGORY_MEMORY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getCategoryForMemoryKey(memoryKey: String): String? {
        if (memoryKey.isBlank()) return null

        val normalizedKey = memoryKey.trim().lowercase()
        val db = dbHelper.readableDatabase

        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_CATEGORY_MEMORY,
            arrayOf(FinanceDatabaseHelper.COLUMN_MEMORY_CATEGORY),
            "${FinanceDatabaseHelper.COLUMN_MEMORY_KEY} = ?",
            arrayOf(normalizedKey),
            null,
            null,
            null
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
            id = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_ID
                )
            ),
            amountPaise = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE
                )
            ),
            transactionType = TransactionType.valueOf(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE
                    )
                )
            ),
            paymentMethod = PaymentMethod.valueOf(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD
                    )
                )
            ),
            accountType = AccountType.valueOf(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE
                    )
                )
            ),
            bank = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_BANK
                )
            ),
            merchantName = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_MERCHANT_NAME
                )
            ),
            payeeId = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_PAYEE_ID
                )
            ),
            accountLastFour = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR
                )
            ),
            refNumber = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_REF_NUMBER
                )
            ),
            timestamp = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_TIMESTAMP
                )
            ),
            smsHash = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_SMS_HASH
                )
            ),
            category = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_CATEGORY
                )
            ),
            parserConfidence = cursor.getFloat(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE
                )
            )
        )
    }
}
