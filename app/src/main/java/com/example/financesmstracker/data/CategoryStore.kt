package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

internal class CategoryStore(
    private val dbHelper: FinanceDatabaseHelper
) {

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

        // Only debits take a remembered category (credits have their own,
        // smaller list), and the key's lowercase form is what is compared.
        val notCredit = "${FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE} = 'DEBIT'"
        return when {
            normalizedKey.startsWith("vpa|") -> db.update(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                values,
                "LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_PAYEE_ID})) = ? AND $notCredit",
                arrayOf(normalizedKey.removePrefix("vpa|"))
            )

            normalizedKey.startsWith("payee|") -> db.update(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                values,
                "LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_MERCHANT_NAME})) = ? AND $notCredit",
                arrayOf(normalizedKey.removePrefix("payee|"))
            )

            else -> 0
        }
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
}
