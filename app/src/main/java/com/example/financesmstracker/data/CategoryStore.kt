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
