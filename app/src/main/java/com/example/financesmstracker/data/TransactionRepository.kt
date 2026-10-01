package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.financesmstracker.evidence.CrossSourceMatcher
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.MatchOutcome
import com.example.financesmstracker.evidence.MatchResult
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.integration.OracleTransaction

class TransactionRepository(private val dbHelper: FinanceDatabaseHelper) {

    fun insertTransaction(transaction: Transaction): Long {
        val db = dbHelper.writableDatabase

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountPaise)
            put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
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
            Log.d("FinanceSource", "TRANSACTION_REPOSITORY_INSERT -> RowID: $rowId, Amount: ${transaction.amountPaise}, Currency: ${transaction.currency}, Type: ${transaction.transactionType}, Bank: ${transaction.bank}, Timestamp: ${transaction.timestamp}")
        }

        return rowId
    }

    fun insertSourceEvidence(evidence: SourceEvidence): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_TYPE, evidence.sourceType.name)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_KEY, evidence.sourceKey)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT, evidence.receivedAt)
            if (evidence.transactionId != null) {
                put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, evidence.transactionId)
            }
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE, evidence.amountPaise)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CURRENCY, evidence.currency)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_DIRECTION, evidence.direction)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_BANK_PROVIDER, evidence.bankProvider)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR, evidence.accountLastFour)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_REFERENCE, evidence.reference)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONTENT_HASH, evidence.contentHash)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONFIDENCE, evidence.confidence)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, evidence.status.name)
        }

        return db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun insertUnrecognizedSms(unrecognized: UnrecognizedSms): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER, unrecognized.sender)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT, unrecognized.receivedAt)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH, unrecognized.contentHash)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON, unrecognized.reason)
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS, unrecognized.status.name)
        }

        val rowId = db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        if (rowId != -1L) {
            Log.d("FinanceSource", "UNRECOGNIZED_SMS_RECORDED -> ID: $rowId, sender: ${unrecognized.sender}, reason: ${unrecognized.reason}")
        }
        return rowId
    }

    fun getUnresolvedUnrecognizedSms(): List<UnrecognizedSms> {
        val list = mutableListOf<UnrecognizedSms>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS} = ?",
            arrayOf(ReviewStatus.REVIEW.name),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    UnrecognizedSms(
                        id = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID)),
                        sender = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER)),
                        receivedAt = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT)),
                        contentHash = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH)),
                        reason = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON)),
                        status = ReviewStatus.valueOf(it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS)))
                    )
                )
            }
        }
        return list
    }

    fun getUnresolvedUnrecognizedSmsCount(): Int {
        val db = dbHelper.readableDatabase
        return db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            arrayOf("COUNT(*)"),
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS} = ?",
            arrayOf(ReviewStatus.REVIEW.name),
            null,
            null,
            null
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun updateUnrecognizedSmsStatus(id: Long, status: ReviewStatus): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS, status.name)
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            values,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun getUnrecognizedSmsByHash(contentHash: String): UnrecognizedSms? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
            null,
            "${FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH} = ?",
            arrayOf(contentHash),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return UnrecognizedSms(
                    id = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_ID)),
                    sender = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_SENDER)),
                    receivedAt = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_RECEIVED_AT)),
                    contentHash = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_CONTENT_HASH)),
                    reason = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_REASON)),
                    status = ReviewStatus.valueOf(it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_UNRECOGNIZED_STATUS)))
                )
            }
        }
        return null
    }

    fun getSourceEvidenceById(id: Long): SourceEvidence? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToEvidence(it)
            }
        }
        return null
    }

    fun applyMatchResult(evidenceId: Long, result: MatchResult) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            when (result.outcome) {
                MatchOutcome.MATCHED -> {
                    if (result.matchedTransactionId != null) {
                        updateSourceEvidenceMatchInternal(db, evidenceId, result.matchedTransactionId, EvidenceStatus.MATCHED)
                        Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: $evidenceId, transactionId: ${result.matchedTransactionId}, result: MATCHED, reasons: ${result.reasons}")
                    }
                }
                MatchOutcome.AMBIGUOUS -> {
                    updateSourceEvidenceStatusInternal(db, evidenceId, EvidenceStatus.AMBIGUOUS)
                    Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: $evidenceId, transactionId: null, result: AMBIGUOUS, reasons: ${result.reasons}")
                }
                MatchOutcome.UNMATCHED -> {
                    updateSourceEvidenceStatusInternal(db, evidenceId, EvidenceStatus.UNMATCHED)
                    Log.d("FinanceSource", "CROSS_SOURCE_MATCH -> evidenceId: $evidenceId, transactionId: null, result: UNMATCHED, reasons: ${result.reasons}")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getUnresolvedEvidence(): List<SourceEvidence> {
        val list = mutableListOf<SourceEvidence>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS} IN (?, ?)",
            arrayOf(EvidenceStatus.UNMATCHED.name, EvidenceStatus.AMBIGUOUS.name),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToEvidence(it))
            }
        }
        return list
    }

    fun getUnresolvedEvidenceCount(): Int {
        val db = dbHelper.readableDatabase
        return db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            arrayOf("COUNT(*)"),
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS} IN (?, ?)",
            arrayOf(EvidenceStatus.UNMATCHED.name, EvidenceStatus.AMBIGUOUS.name),
            null,
            null,
            null
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getCandidateTransactionsForEvidence(evidence: SourceEvidence): List<Transaction> {
        val candidates = getTransactionsByAmount(evidence.amountPaise)
        return candidates.filter { tx ->
            tx.currency.equals(evidence.currency, ignoreCase = true) &&
                (evidence.direction.isBlank() ||
                    evidence.direction.equals("UNKNOWN", ignoreCase = true) ||
                    evidence.direction.equals(tx.transactionType.name, ignoreCase = true)) &&
                (evidence.bankProvider.isNullOrBlank() ||
                    tx.bank.isNullOrBlank() ||
                    normalizeBank(evidence.bankProvider) == normalizeBank(tx.bank))
        }
    }

    fun resolveEvidenceToTransaction(evidenceId: Long, transactionId: Long): SourceEvidence? {
        val db = dbHelper.writableDatabase
        val evidence = getSourceEvidenceById(evidenceId) ?: return null
        if (getTransactionById(transactionId) == null) return null

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, transactionId)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, EvidenceStatus.MATCHED.name)
        }
        val updated = db.update(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            values,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_ID} = ?",
            arrayOf(evidenceId.toString())
        )
        return if (updated > 0) getSourceEvidenceById(evidenceId) else evidence
    }

    private fun normalizeBank(bank: String?): String? {
        if (bank.isNullOrBlank()) return null
        val upper = bank.trim().uppercase()
        return when {
            upper.contains("AXIS") -> "AXIS"
            upper.contains("HDFC") -> "HDFC"
            upper.contains("ICICI") -> "ICICI"
            upper.contains("SBI") -> "SBI"
            upper.contains("KOTAK") -> "KOTAK"
            upper.contains("PAYTM") -> "PAYTM"
            else -> upper.replace(" BANK", "").trim()
        }
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

    fun getUnmatchedOrAmbiguousEvidenceByAmount(amountPaise: Long): List<SourceEvidence> {
        val list = mutableListOf<SourceEvidence>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE} = ? AND ${FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS} != ?",
            arrayOf(amountPaise.toString(), EvidenceStatus.MATCHED.name),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToEvidence(it))
            }
        }
        return list
    }

    private fun updateSourceEvidenceMatchInternal(db: SQLiteDatabase, id: Long, transactionId: Long, status: EvidenceStatus) {
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, transactionId)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, status.name)
        }
        db.update(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            values,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_ID} = ?",
            arrayOf(id.toString())
        )
    }

    private fun updateSourceEvidenceStatusInternal(db: SQLiteDatabase, id: Long, status: EvidenceStatus) {
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, status.name)
        }
        db.update(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            values,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun getSourceEvidenceByKey(sourceKey: String): SourceEvidence? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_KEY} = ?",
            arrayOf(sourceKey),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToEvidence(it)
            }
        }
        return null
    }

    fun updateSourceEvidenceMatchPublic(id: Long, transactionId: Long, status: EvidenceStatus): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, transactionId)
            put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, status.name)
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            values,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun logNewestEvidenceSummary(limit: Int = 5) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            null, null, null, null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT} DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                val sType = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_TYPE))
                val amt = it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE))
                val curr = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CURRENCY))
                val dir = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_DIRECTION))
                val bank = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_BANK_PROVIDER))
                val txId = if (it.isNull(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID))) null else it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID))
                val status = it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS))
                Log.d("FinanceSource", "NEWEST_EVIDENCE_SUMMARY -> sourceType: $sType, amount: $amt $curr, direction: $dir, bank: ${bank ?: "null"}, transactionId: ${txId ?: "null"}, status: $status")
            }
        }
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

    /**
     * Upserts a transaction received from the durable Oracle Gmail ledger into
     * the phone's local transaction list. The remote Gmail id is stored in the
     * existing unique sms_hash column with an explicit prefix, so this works
     * without a destructive database migration and remains idempotent.
     */
    fun upsertOracleGmailTransaction(transaction: OracleTransaction): Long {
        val db = dbHelper.writableDatabase
        val remoteMarker = "oracle:gmail:" + transaction.id

        val existingRemoteId = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            arrayOf(FinanceDatabaseHelper.COLUMN_ID),
            FinanceDatabaseHelper.COLUMN_SMS_HASH + " = ?",
            arrayOf(remoteMarker),
            null,
            null,
            null,
            "1"
        ).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

        // Prefer an existing phone transaction when Oracle's Gmail copy clearly
        // describes the same real-world transaction. This prevents a Gmail
        // mirror from appearing as a second row beside the SMS transaction.
        val matchingLocal = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            arrayOf(
                FinanceDatabaseHelper.COLUMN_ID,
                FinanceDatabaseHelper.COLUMN_MERCHANT_NAME
            ),
            FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ? AND " +
                FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE + " = ? AND " +
                "LOWER(TRIM(COALESCE(" + FinanceDatabaseHelper.COLUMN_BANK + ",''))) = LOWER(TRIM(COALESCE(?,''))) AND " +
                "TRIM(COALESCE(" + FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR + ",'')) = TRIM(COALESCE(?,'')) AND " +
                "ABS(" + FinanceDatabaseHelper.COLUMN_TIMESTAMP + " - ?) <= ?",
            arrayOf(
                "oracle:gmail:%",
                "ACTIVE",
                transaction.amountMinor.toString(),
                transaction.currency,
                transaction.transactionType,
                transaction.bank ?: "",
                transaction.accountLast4 ?: "",
                transaction.timestamp.toString(),
                (24L * 60L * 60L * 1000L).toString()
            ),
            null,
            null,
            FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
            "1"
        ).use {
            if (it.moveToFirst()) {
                Pair(
                    it.getLong(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_ID)),
                    it.getString(it.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME))?.trim()
                )
            } else {
                null
            }
        }

        if (matchingLocal != null) {
            val localId = matchingLocal.first
            val localMerchant = matchingLocal.second

            val remoteMerchant = transaction.merchantOrPayee?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { !it.equals("null", ignoreCase = true) }
                ?.takeIf { !it.equals("none", ignoreCase = true) }

            val merchantNeedsRepair =
                localMerchant.isNullOrBlank() ||
                    localMerchant.equals("null", ignoreCase = true) ||
                    localMerchant.equals("none", ignoreCase = true)

            var changed = false
            if (merchantNeedsRepair && remoteMerchant != null) {
                val merchantValues = ContentValues().apply {
                    put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, remoteMerchant)
                }
                changed = db.update(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    merchantValues,
                    FinanceDatabaseHelper.COLUMN_ID + " = ?",
                    arrayOf(localId.toString())
                ) > 0
            }

            if (existingRemoteId != null) {
                db.delete(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    FinanceDatabaseHelper.COLUMN_ID + " = ?",
                    arrayOf(existingRemoteId.toString())
                )
                changed = true
            }

            return if (changed) localId else 0L
        }

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountMinor)
            put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType)
            put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType)
            put(FinanceDatabaseHelper.COLUMN_BANK, transaction.bank)
            put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantOrPayee)
            putNull(FinanceDatabaseHelper.COLUMN_PAYEE_ID)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLast4)
            put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, transaction.reference)
            put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, transaction.timestamp)
            put(FinanceDatabaseHelper.COLUMN_SMS_HASH, remoteMarker)
            put(FinanceDatabaseHelper.COLUMN_CATEGORY, transaction.category ?: "OTHER")
            put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, transaction.confidence)
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "ACTIVE")
        }

        if (existingRemoteId != null) {
            val updated = db.update(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                values,
                FinanceDatabaseHelper.COLUMN_ID + " = ?",
                arrayOf(existingRemoteId.toString())
            )
            return if (updated > 0) existingRemoteId else 0L
        }

        return db.insertWithOnConflict(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
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

    fun voidTransaction(id: Long): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "VOIDED")
        }
        return db.update(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            values,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
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
            currency = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    FinanceDatabaseHelper.COLUMN_CURRENCY
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

    private fun cursorToEvidence(cursor: Cursor): SourceEvidence {
        return SourceEvidence(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_ID)),
            sourceType = SourceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_TYPE))),
            sourceKey = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_SOURCE_KEY)),
            receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT)),
            transactionId = if (cursor.isNull(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID))) null else cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID)),
            amountPaise = cursor.getLong(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_AMOUNT_PAISE)),
            currency = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CURRENCY)),
            direction = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_DIRECTION)),
            bankProvider = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_BANK_PROVIDER)),
            accountLastFour = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_ACCOUNT_LAST_FOUR)),
            reference = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_REFERENCE)),
            contentHash = cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONTENT_HASH)),
            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_CONFIDENCE)),
            status = EvidenceStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS)))
        )
    }
}
