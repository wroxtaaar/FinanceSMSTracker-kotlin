package com.example.financesmstracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FinanceDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "finance_tracker.db"
        private const val DATABASE_VERSION = 1

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

        const val TABLE_PAYEE_MAPPINGS = "payee_category_mappings"
        const val COLUMN_MAPPING_PAYEE_ID = "payee_id"
        const val COLUMN_MAPPING_CATEGORY = "category"
    }

    override fun onCreate(db: SQLiteDatabase) {
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

        val createMappingsTable = """
            CREATE TABLE $TABLE_PAYEE_MAPPINGS (
                $COLUMN_MAPPING_PAYEE_ID TEXT PRIMARY KEY,
                $COLUMN_MAPPING_CATEGORY TEXT NOT NULL
            )
        """.trimIndent()

        db.execSQL(createTransactionsTable)
        db.execSQL(createMappingsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRANSACTIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PAYEE_MAPPINGS")
        onCreate(db)
    }
}
