package com.example.financesmstracker.data

import com.example.financesmstracker.util.TransactionReferenceNormalizer

internal class TransactionQueries(
    private val dbHelper: FinanceDatabaseHelper
) {

    /**
     * Returns active transaction history candidates for a normalized reference.
     * Reference-based reconciliation intentionally does not impose an amount or
     * time window; the caller still applies direction/bank/account compatibility.
     */
    fun getTransactionsByReference(reference: String): List<Transaction> {
        val normalizedReference = TransactionReferenceNormalizer.normalize(reference) ?: return emptyList()
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ? AND " +
                "${FinanceDatabaseHelper.COLUMN_REF_NUMBER} IS NOT NULL AND " +
                "${FinanceDatabaseHelper.COLUMN_REF_NUMBER} != ?",
            arrayOf("ACTIVE", ""),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val transaction = cursorToTransaction(it)
                if (TransactionReferenceNormalizer.normalize(transaction.refNumber) == normalizedReference) {
                    list.add(transaction)
                }
            }
        }
        return list
    }

    fun getTransactionsByAmount(amountPaise: Long): List<Transaction> {
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE} = ?",
            arrayOf(amountPaise.toString()),
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

    fun getTransactionById(id: Long): Transaction? {
        val db = dbHelper.readableDatabase

        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "${FinanceDatabaseHelper.COLUMN_ID} = ? AND " +
                "${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ?",
            arrayOf(id.toString(), "ACTIVE"),
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
            "${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ?",
            arrayOf("ACTIVE"),
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

    /** The stored source hash of a row in any status (voided rows included). */
    fun getSmsHashById(id: Long): String? {
        val db = dbHelper.readableDatabase
        db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            arrayOf(FinanceDatabaseHelper.COLUMN_SMS_HASH),
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun getTransactionsByMemoryKey(memoryKey: String): List<Transaction> {
        if (memoryKey.isBlank()) return emptyList()

        val normalizedKey = memoryKey.trim().lowercase()
        val db = dbHelper.readableDatabase

        val cursor = when {
            normalizedKey.startsWith("vpa|") -> {
                val payeeId = normalizedKey.removePrefix("vpa|")
                db.query(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    null,
                    "LOWER(TRIM(" + FinanceDatabaseHelper.COLUMN_PAYEE_ID + ")) = ? AND " +
                        FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ?",
                    arrayOf(payeeId, "ACTIVE"),
                    null,
                    null,
                    FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC"
                )
            }

            normalizedKey.startsWith("bank|") -> {
                val parts = normalizedKey.split("|")
                if (parts.size != 4) return emptyList()

                val bank = parts[1]
                val accountType = parts[2].uppercase()
                val lastFour = parts[3]

                db.query(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    null,
                    """
                    LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_BANK})) = ?
                    AND ${FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE} = ?
                    AND TRIM(${FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR}) = ?
                    AND ${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ?
                    """.trimIndent(),
                    arrayOf(bank, accountType, lastFour, "ACTIVE"),
                    null,
                    null,
                    FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC"
                )
            }

            else -> return emptyList()
        }

        cursor.use {
            val list = mutableListOf<Transaction>()
            while (it.moveToNext()) {
                list.add(cursorToTransaction(it))
            }
            return list
        }
    }

    fun getTransactionsByPayee(payeeId: String): List<Transaction> {
        val normalizedPayee = payeeId.trim().lowercase()
        val list = mutableListOf<Transaction>()
        val db = dbHelper.readableDatabase

        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            "LOWER(TRIM(${FinanceDatabaseHelper.COLUMN_PAYEE_ID})) = ? AND " +
                "${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ?",
            arrayOf(normalizedPayee, "ACTIVE"),
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
            "${FinanceDatabaseHelper.COLUMN_TIMESTAMP} BETWEEN ? AND ? AND " +
                "${FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS} = ?",
            arrayOf(startTime.toString(), endTime.toString(), "ACTIVE"),
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
}
