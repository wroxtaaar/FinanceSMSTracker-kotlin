package com.example.financesmstracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FinanceDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

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

        const val TABLE_SOURCE_EVIDENCE = "source_evidence"
        const val COLUMN_EVIDENCE_ID = "id"
        const val COLUMN_EVIDENCE_SOURCE_TYPE = "source_type"
        const val COLUMN_EVIDENCE_SOURCE_KEY = "source_key"
        const val COLUMN_EVIDENCE_RECEIVED_AT = "received_at"
        const val COLUMN_EVIDENCE_TRANSACTION_ID = "transaction_id"
        const val COLUMN_EVIDENCE_AMOUNT_PAISE = "amount_paise"
        const val COLUMN_EVIDENCE_DIRECTION = "direction"
        const val COLUMN_EVIDENCE_BANK_PROVIDER = "bank_provider"
        const val COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR = "account_last_four"
        const val COLUMN_EVIDENCE_REFERENCE = "reference"
        const val COLUMN_EVIDENCE_CONTENT_HASH = "content_hash"
        const val COLUMN_EVIDENCE_CONFIDENCE = "confidence"
        const val COLUMN_EVIDENCE_STATUS = "status"
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

        val createMemoryTable = """
            CREATE TABLE $TABLE_CATEGORY_MEMORY (
                $COLUMN_MEMORY_KEY TEXT PRIMARY KEY,
                $COLUMN_MEMORY_CATEGORY TEXT NOT NULL
            )
        """.trimIndent()

        val createEvidenceTable = """
            CREATE TABLE $TABLE_SOURCE_EVIDENCE (
                $COLUMN_EVIDENCE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_EVIDENCE_SOURCE_TYPE TEXT NOT NULL,
                $COLUMN_EVIDENCE_SOURCE_KEY TEXT UNIQUE NOT NULL,
                $COLUMN_EVIDENCE_RECEIVED_AT INTEGER NOT NULL,
                $COLUMN_EVIDENCE_TRANSACTION_ID INTEGER,
                $COLUMN_EVIDENCE_AMOUNT_PAISE INTEGER NOT NULL,
                $COLUMN_EVIDENCE_DIRECTION TEXT NOT NULL,
                $COLUMN_EVIDENCE_BANK_PROVIDER TEXT,
                $COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR TEXT,
                $COLUMN_EVIDENCE_REFERENCE TEXT,
                $COLUMN_EVIDENCE_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_EVIDENCE_CONFIDENCE REAL NOT NULL,
                $COLUMN_EVIDENCE_STATUS TEXT NOT NULL,
                FOREIGN KEY($COLUMN_EVIDENCE_TRANSACTION_ID) REFERENCES $TABLE_TRANSACTIONS($COLUMN_ID) ON DELETE SET NULL
            )
        """.trimIndent()

        db.execSQL(createTransactionsTable)
        db.execSQL(createMemoryTable)
        db.execSQL(createEvidenceTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val createEvidenceTable = """
                CREATE TABLE IF NOT EXISTS $TABLE_SOURCE_EVIDENCE (
                    $COLUMN_EVIDENCE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_EVIDENCE_SOURCE_TYPE TEXT NOT NULL,
                    $COLUMN_EVIDENCE_SOURCE_KEY TEXT UNIQUE NOT NULL,
                    $COLUMN_EVIDENCE_RECEIVED_AT INTEGER NOT NULL,
                    $COLUMN_EVIDENCE_TRANSACTION_ID INTEGER,
                    $COLUMN_EVIDENCE_AMOUNT_PAISE INTEGER NOT NULL,
                    $COLUMN_EVIDENCE_DIRECTION TEXT NOT NULL,
                    $COLUMN_EVIDENCE_BANK_PROVIDER TEXT,
                    $COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR TEXT,
                    $COLUMN_EVIDENCE_REFERENCE TEXT,
                    $COLUMN_EVIDENCE_CONTENT_HASH TEXT NOT NULL,
                    $COLUMN_EVIDENCE_CONFIDENCE REAL NOT NULL,
                    $COLUMN_EVIDENCE_STATUS TEXT NOT NULL,
                    FOREIGN KEY($COLUMN_EVIDENCE_TRANSACTION_ID) REFERENCES $TABLE_TRANSACTIONS($COLUMN_ID) ON DELETE SET NULL
                )
            """.trimIndent()
            db.execSQL(createEvidenceTable)
        }
    }
}
