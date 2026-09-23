package com.example.financesmstracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FinanceDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "finance_tracker.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_TRANSACTIONS = "transactions"
        const val COLUMN_ID = "id"
        const val COLUMN_AMOUNT_PAISE = "amount_paise"
        const val COLUMN_TRANSACTION_TYPE = "transaction_type"
        const val COLUMN_PAYMENT_METHOD = "payment_method"
        const val COLUMN_ACCOUNT_TYPE = "account_type"
        const val COLUMN_BANK = "bank"
        const val COLUMN_MERCHANT_NAME = "merchant_name"
        const val COLUMN_PAYEE_ID = "payee_id"
        const val COLUMN_ACCOUNT_LAST_FOUR = "account_last_four"
        const val COLUMN_REF_NUMBER = "ref_number"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_SMS_HASH = "sms_hash"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_PARSER_CONFIDENCE = "parser_confidence"

        const val TABLE_CATEGORY_MEMORY = "category_memory"
        const val COLUMN_MEMORY_KEY = "memory_key"
        const val COLUMN_MEMORY_CATEGORY = "category"

        // Kept only for migration compatibility.
        private const val OLD_TABLE_PAYEE_MAPPINGS = "payee_category_mappings"
        private const val OLD_COLUMN_PAYEE_ID = "payee_id"
        private const val OLD_COLUMN_CATEGORY = "category"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTransactionsTable(db)
        createCategoryMemoryTable(db)
    }

    private fun createTransactionsTable(db: SQLiteDatabase) {
        val createTransactionsTable = """
            CREATE TABLE $TABLE_TRANSACTIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_AMOUNT_PAISE INTEGER NOT NULL,
                $COLUMN_TRANSACTION_TYPE TEXT NOT NULL,
                $COLUMN_PAYMENT_METHOD TEXT NOT NULL,
                $COLUMN_ACCOUNT_TYPE TEXT NOT NULL,
                $COLUMN_BANK TEXT,
                $COLUMN_MERCHANT_NAME TEXT,
                $COLUMN_PAYEE_ID TEXT,
                $COLUMN_ACCOUNT_LAST_FOUR TEXT,
                $COLUMN_REF_NUMBER TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_SMS_HASH TEXT UNIQUE NOT NULL,
                $COLUMN_CATEGORY TEXT,
                $COLUMN_PARSER_CONFIDENCE REAL NOT NULL
            )
        """.trimIndent()

        db.execSQL(createTransactionsTable)
    }

    private fun createCategoryMemoryTable(db: SQLiteDatabase) {
        val createCategoryMemoryTable = """
            CREATE TABLE $TABLE_CATEGORY_MEMORY (
                $COLUMN_MEMORY_KEY TEXT PRIMARY KEY,
                $COLUMN_MEMORY_CATEGORY TEXT NOT NULL
            )
        """.trimIndent()

        db.execSQL(createCategoryMemoryTable)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            migratePayeeMappingsToCategoryMemory(db)
        }
    }

    private fun migratePayeeMappingsToCategoryMemory(db: SQLiteDatabase) {
        createCategoryMemoryTable(db)

        // Preserve all existing VPA/payee mappings.
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_CATEGORY_MEMORY
                ($COLUMN_MEMORY_KEY, $COLUMN_MEMORY_CATEGORY)
            SELECT
                'VPA|' || lower(trim($OLD_COLUMN_PAYEE_ID)),
                $OLD_COLUMN_CATEGORY
            FROM $OLD_TABLE_PAYEE_MAPPINGS
            WHERE $OLD_COLUMN_PAYEE_ID IS NOT NULL
              AND trim($OLD_COLUMN_PAYEE_ID) != ''
            """.trimIndent()
        )

        db.execSQL(
            "DROP TABLE IF EXISTS $OLD_TABLE_PAYEE_MAPPINGS"
        )
    }
}
