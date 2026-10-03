package com.example.financesmstracker.data

import android.content.ContentValues
import android.content.Context
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
import com.example.financesmstracker.util.TransactionReferenceNormalizer

class TransactionRepository(private val dbHelper: FinanceDatabaseHelper, private val context: Context) {

    private val localHistoryPrefs = context.getSharedPreferences("local_history_state", Context.MODE_PRIVATE)

    private fun wasClearedBefore(transaction: Transaction): Boolean {
        val clearedAt = localHistoryPrefs.getLong("cleared_at", 0L)
        if (clearedAt > 0L && transaction.timestamp <= clearedAt) return true

        val clearedHashes =
            localHistoryPrefs.getStringSet("cleared_sms_hashes", emptySet()) ?: emptySet()
        return transaction.smsHash.isNotBlank() && transaction.smsHash in clearedHashes
    }


    fun insertTransaction(transaction: Transaction): Long {
        if (wasClearedBefore(transaction)) return 0L

        val db = dbHelper.writableDatabase

        // Some Gmail notifications expose only the account last-four and omit
        // the issuer name. If that last-four uniquely belongs to one active
        // bank account in the local ledger, enrich the provisional notification
        // before it is synced to Oracle so the bank balance can be applied.
        val notificationBank = if (
            transaction.smsHash.startsWith("notification:") &&
            transaction.accountType == com.example.financesmstracker.parser.AccountType.BANK_ACCOUNT &&
            transaction.bank.isNullOrBlank() &&
            !transaction.accountLastFour.isNullOrBlank()
        ) {
            db.query(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                arrayOf("DISTINCT " + FinanceDatabaseHelper.COLUMN_BANK),
                FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_BANK + " IS NOT NULL AND TRIM(" +
                    FinanceDatabaseHelper.COLUMN_BANK + ") <> ''",
                arrayOf(
                    "ACTIVE",
                    com.example.financesmstracker.parser.AccountType.BANK_ACCOUNT.name,
                    transaction.accountLastFour
                ),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val firstBank = cursor.getString(0)
                    if (cursor.moveToNext()) null else firstBank
                } else {
                    null
                }
            }
        } else {
            transaction.bank
        }

        // A Gmail notification can arrive before the bank SMS. When the SMS
        // arrives later, reuse the notification transaction instead of creating
        // a second canonical row.
        if (!transaction.smsHash.startsWith("notification:")) {
            val notificationCandidates = db.query(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                arrayOf(
                    FinanceDatabaseHelper.COLUMN_ID,
                    FinanceDatabaseHelper.COLUMN_BANK,
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR,
                    FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                    FinanceDatabaseHelper.COLUMN_SMS_HASH
                ),
                "(" +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " LIKE ? OR " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " LIKE ?" +
                    ") AND " +
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE + " = ? AND " +
                    "ABS(" + FinanceDatabaseHelper.COLUMN_TIMESTAMP + " - ?) <= ?",
                arrayOf(
                    "notification:%",
                    "oracle:gmail:%",
                    "ACTIVE",
                    transaction.amountPaise.toString(),
                    transaction.currency,
                    transaction.transactionType.name,
                    transaction.timestamp.toString(),
                    (2L * 60L * 60L * 1000L).toString()
                ),
                null,
                null,
                FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
                "20"
            )

            notificationCandidates.use {
                while (it.moveToNext()) {
                    val notificationBank = it.getString(1)
                    val notificationLast4 = it.getString(2)
                    val notificationReference = it.getString(3)

                    val bankCompatible =
                        notificationBank.isNullOrBlank() ||
                            transaction.bank.isNullOrBlank() ||
                            notificationBank.equals(transaction.bank, ignoreCase = true)

                    val last4Compatible =
                        notificationLast4.isNullOrBlank() ||
                            transaction.accountLastFour.isNullOrBlank() ||
                            notificationLast4 == transaction.accountLastFour

                    val referenceCompatible =
                        notificationReference.isNullOrBlank() ||
                            transaction.refNumber.isNullOrBlank() ||
                            notificationReference.equals(transaction.refNumber, ignoreCase = true)

                    if (bankCompatible && last4Compatible && referenceCompatible) {
                        val notificationId = it.getLong(0)

                        // SMS is authoritative over a provisional Gmail notification.
                        // Update the existing row in place instead of returning its id
                        // unchanged; otherwise the SMS details never reach Oracle and
                        // the notification can remain unresolved/provisional forever.
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
                        db.update(
                            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                            values,
                            FinanceDatabaseHelper.COLUMN_ID + " = ?",
                            arrayOf(notificationId.toString())
                        )
                        return notificationId
                    }
                }
            }
        }

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
            if (!tx.currency.equals(evidence.currency, ignoreCase = true)) return@filter false

            val directionKnown = !evidence.direction.isBlank() &&
                !evidence.direction.equals("UNKNOWN", ignoreCase = true)
            if (directionKnown && !evidence.direction.equals(tx.transactionType.name, ignoreCase = true)) {
                return@filter false
            }

            if (!evidence.bankProvider.isNullOrBlank() &&
                !tx.bank.isNullOrBlank() &&
                normalizeBank(evidence.bankProvider) != normalizeBank(tx.bank)
            ) {
                return@filter false
            }

            if (!evidence.accountLastFour.isNullOrBlank() &&
                !tx.accountLastFour.isNullOrBlank() &&
                evidence.accountLastFour != tx.accountLastFour
            ) {
                return@filter false
            }

            val timeDiff = kotlin.math.abs(tx.timestamp - evidence.receivedAt)
            if (timeDiff <= 120_000L) {
                true
            } else {
                !evidence.reference.isNullOrBlank() &&
                    !tx.refNumber.isNullOrBlank() &&
                    evidence.reference.equals(tx.refNumber, ignoreCase = true)
            }
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

    /**
     * Returns unresolved evidence whose normalized reference matches, regardless
     * of amount or event age. This lets a later Gmail reference resolve older
     * evidence instead of being trapped in a short time window.
     */
    fun getUnmatchedOrAmbiguousEvidenceByReference(reference: String): List<SourceEvidence> {
        val normalizedReference = TransactionReferenceNormalizer.normalize(reference) ?: return emptyList()
        val list = mutableListOf<SourceEvidence>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS} != ? AND " +
                "${FinanceDatabaseHelper.COLUMN_EVIDENCE_REFERENCE} IS NOT NULL",
            arrayOf(EvidenceStatus.MATCHED.name),
            null,
            null,
            "${FinanceDatabaseHelper.COLUMN_EVIDENCE_RECEIVED_AT} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val evidence = cursorToEvidence(it)
                if (TransactionReferenceNormalizer.normalize(evidence.reference) == normalizedReference) {
                    list.add(evidence)
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

        data class LocalCandidate(
            val id: Long,
            val amountPaise: Long,
            val transactionType: String,
            val merchant: String?,
            val bank: String?,
            val last4: String?,
            val reference: String?,
            val payeeId: String?,
            val paymentMethod: String?,
            val accountType: String?,
            val confidence: Float,
            val timestamp: Long,
            val smsHash: String?,
            val isNotification: Boolean
        )

        fun normalized(value: String?): String? =
            value?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
                ?.lowercase()

        fun normalizedReference(value: String?): String? =
            com.example.financesmstracker.util.TransactionReferenceNormalizer.normalize(value)?.lowercase()

        fun candidateScore(candidate: LocalCandidate, maxAgeMs: Long): Int? {
            val remoteBank = normalized(transaction.bank)
            val remoteLast4 = normalized(transaction.accountLast4)
            val remoteReference = normalizedReference(transaction.reference)
            val localBank = normalized(candidate.bank)
            val localLast4 = normalized(candidate.last4)
            val localReference = normalizedReference(candidate.reference)

            if (candidate.amountPaise != transaction.amountMinor) return null

            // A Gmail notification is only a provisional hint. Some bank
            // notifications expose the account last-4 but omit the bank and
            // can even be directionally ambiguous. Allow the authoritative
            // Gmail/IMAP row to replace the direction when it matches that
            // provisional notification by amount + account, rather than
            // leaving a bogus debit/credit stranded forever.
            val provisionalNotification =
                candidate.isNotification &&
                    localBank == null &&
                    localReference == null &&
                    remoteLast4 != null &&
                    localLast4 == remoteLast4 &&
                    candidate.accountType.equals(transaction.accountType, ignoreCase = true) &&
                    candidate.paymentMethod.equals(PaymentMethod.UNKNOWN.name, ignoreCase = true)

            if (
                !candidate.transactionType.equals(transaction.transactionType, ignoreCase = true) &&
                !provisionalNotification
            ) return null

            val sameReference = remoteReference != null &&
                localReference != null &&
                remoteReference == localReference

            // An exact reference is allowed to resolve a transaction regardless
            // of age. Without a reference, retain the bounded fallback window.
            val age = kotlin.math.abs(candidate.timestamp - transaction.timestamp)
            if (!sameReference && age > maxAgeMs) return null

            if (remoteReference != null && localReference != null && !sameReference) return null

            // The same UPI RRN can legitimately appear on both ledger sides of
            // an internal transfer. Reference identity therefore never overrides
            // a contradictory bank/account identity.
            if (remoteBank != null && localBank != null && remoteBank != localBank) return null
            if (remoteLast4 != null && localLast4 != null && remoteLast4 != localLast4) return null

            var score = 0
            if (sameReference) score += 1000
            if (remoteBank != null && localBank != null && remoteBank == localBank) score += 25
            if (remoteLast4 != null && localLast4 != null && remoteLast4 == localLast4) score += 30

            val remoteMerchant = normalized(transaction.merchantOrPayee)
            val localMerchant = normalized(candidate.merchant)
            if (remoteMerchant != null && localMerchant != null &&
                (remoteMerchant == localMerchant ||
                    remoteMerchant.contains(localMerchant) ||
                    localMerchant.contains(remoteMerchant))
            ) {
                score += 25
            }

            if (transaction.accountType.equals(candidate.accountType, ignoreCase = true)) score += 8
            if (transaction.paymentMethod.equals(candidate.paymentMethod, ignoreCase = true)) score += 5
            score += (20 - (age / (15L * 60L * 1000L)).toInt()).coerceAtLeast(0)

            val bankCompatible = remoteBank == null || localBank == null || remoteBank == localBank
            val last4Compatible = remoteLast4 == null || localLast4 == null || remoteLast4 == localLast4
            val hasBankIdentity = remoteBank != null && localBank != null
            val hasAccountIdentity = remoteLast4 != null && localLast4 != null

            val compatibleIdentity =
                sameReference ||
                    (bankCompatible && last4Compatible && (hasBankIdentity || hasAccountIdentity))

            if (!compatibleIdentity) return null
            return score
        }

        fun findBestCandidate(isNotification: Boolean, maxAgeMs: Long): LocalCandidate? {
            val remoteReference = normalizedReference(transaction.reference)

            val selection: String
            val args: Array<String>

            if (!isNotification && remoteReference != null) {
                // Reference-first lookup: deliberately no amount/type/time
                // restriction in SQL. candidateScore performs the side-aware
                // validation after normalization.
                // A Gmail RRN is authoritative, but the earlier SMS/notification
                // transaction often has no RRN yet. Do not require a local reference
                // here; candidateScore will prefer an exact RRN and otherwise match
                // the same bank/account/amount/direction within the fallback window.
                selection =
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ? AND " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ?"
                args = arrayOf("ACTIVE", "oracle:gmail:%", "notification:%")
            } else if (isNotification) {
                // Notifications are provisional and may have the wrong direction
                // before Gmail/IMAP supplies the authoritative bank email. Do not
                // filter by transaction type here; candidateScore handles the normal
                // same-direction case and the explicit provisional-notification
                // exception handles a bankless/wrong-direction notification.
                selection =
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " LIKE ?"
                args = arrayOf(
                    "ACTIVE",
                    transaction.amountMinor.toString(),
                    transaction.currency,
                    "notification:%"
                )
            } else {
                selection =
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ? AND " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ?"
                args = arrayOf(
                    "ACTIVE",
                    transaction.amountMinor.toString(),
                    transaction.currency,
                    transaction.transactionType,
                    "oracle:gmail:%",
                    "notification:%"
                )
            }

            var best: LocalCandidate? = null
            var bestScore = Int.MIN_VALUE

            db.query(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                arrayOf(
                    FinanceDatabaseHelper.COLUMN_ID,
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE,
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE,
                    FinanceDatabaseHelper.COLUMN_MERCHANT_NAME,
                    FinanceDatabaseHelper.COLUMN_BANK,
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR,
                    FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                    FinanceDatabaseHelper.COLUMN_PAYEE_ID,
                    FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD,
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE,
                    FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE,
                    FinanceDatabaseHelper.COLUMN_TIMESTAMP,
                    FinanceDatabaseHelper.COLUMN_SMS_HASH
                ),
                selection,
                args,
                null,
                null,
                FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
                if (!isNotification && remoteReference != null) null else "50"
            ).use {
                while (it.moveToNext()) {
                    val candidate = LocalCandidate(
                        id = it.getLong(0),
                        amountPaise = it.getLong(1),
                        transactionType = it.getString(2),
                        merchant = it.getString(3),
                        bank = it.getString(4),
                        last4 = it.getString(5),
                        reference = it.getString(6),
                        payeeId = it.getString(7),
                        paymentMethod = it.getString(8),
                        accountType = it.getString(9),
                        confidence = it.getFloat(10),
                        timestamp = it.getLong(11),
                        smsHash = it.getString(12),
                        isNotification = it.getString(12)?.startsWith("notification:") == true
                    )

                    val score = candidateScore(candidate, maxAgeMs) ?: continue
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate
                    }
                }
            }

            return best
        }

        val matchingLocal = findBestCandidate(
            isNotification = false,
            maxAgeMs = 24L * 60L * 60L * 1000L
        )
        val notificationLocal = findBestCandidate(
            isNotification = true,
            maxAgeMs = 6L * 60L * 60L * 1000L
        )

        val resolvedLocal = matchingLocal ?: notificationLocal

        if (resolvedLocal != null) {
            val localId = resolvedLocal.id
            val localMerchant = resolvedLocal.merchant

            val values = ContentValues().apply {
                put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountMinor)
                put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType)
                put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod)
                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType)
                put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, transaction.timestamp)
                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "ACTIVE")

                if (!transaction.bank.isNullOrBlank()) {
                    put(FinanceDatabaseHelper.COLUMN_BANK, transaction.bank)
                }
                if (!transaction.accountLast4.isNullOrBlank()) {
                    put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLast4)
                }
                if (!transaction.reference.isNullOrBlank()) {
                    put(
                        FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                        com.example.financesmstracker.util.TransactionReferenceNormalizer.normalize(transaction.reference)
                    )
                }
                if (!transaction.merchantOrPayee.isNullOrBlank() && localMerchant.isNullOrBlank()) {
                    put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantOrPayee)
                }
                if (transaction.confidence >= resolvedLocal.confidence) {
                    put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, transaction.confidence)
                }
            }

            if (resolvedLocal.isNotification) {
                values.put(FinanceDatabaseHelper.COLUMN_SMS_HASH, remoteMarker)
            }

            val updated = db.update(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                values,
                FinanceDatabaseHelper.COLUMN_ID + " = ?",
                arrayOf(localId.toString())
            )

            if (updated > 0) {
                // A reference can arrive after a bad provisional row was
                // created. Void any active same-bank/same-account row carrying
                // the same reference but the wrong amount or ledger direction.
                val remoteReference = normalizedReference(transaction.reference)
                if (remoteReference != null) {
                    val conflicts = mutableListOf<LocalCandidate>()
                    db.query(
                        FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                        arrayOf(
                            FinanceDatabaseHelper.COLUMN_ID,
                            FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE,
                            FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE,
                            FinanceDatabaseHelper.COLUMN_MERCHANT_NAME,
                            FinanceDatabaseHelper.COLUMN_BANK,
                            FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR,
                            FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                            FinanceDatabaseHelper.COLUMN_PAYEE_ID,
                            FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD,
                            FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE,
                            FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE,
                            FinanceDatabaseHelper.COLUMN_TIMESTAMP,
                            FinanceDatabaseHelper.COLUMN_SMS_HASH
                        ),
                        FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                            FinanceDatabaseHelper.COLUMN_REF_NUMBER + " IS NOT NULL",
                        arrayOf("ACTIVE"),
                        null,
                        null,
                        FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
                        null
                    ).use {
                        while (it.moveToNext()) {
                            val candidate = LocalCandidate(
                                id = it.getLong(0),
                                amountPaise = it.getLong(1),
                                transactionType = it.getString(2),
                                merchant = it.getString(3),
                                bank = it.getString(4),
                                last4 = it.getString(5),
                                reference = it.getString(6),
                                payeeId = it.getString(7),
                                paymentMethod = it.getString(8),
                                accountType = it.getString(9),
                                confidence = it.getFloat(10),
                                timestamp = it.getLong(11),
                                smsHash = it.getString(12),
                                isNotification = it.getString(12)?.startsWith("notification:") == true
                            )
                            val sameReference = normalizedReference(candidate.reference) == remoteReference
                            val sameBank = !transaction.bank.isNullOrBlank() &&
                                !candidate.bank.isNullOrBlank() &&
                                normalized(transaction.bank) == normalized(candidate.bank)
                            val sameAccount = transaction.accountLast4.isNullOrBlank() ||
                                candidate.last4.isNullOrBlank() ||
                                normalized(transaction.accountLast4) == normalized(candidate.last4)
                            val wrongSide = !candidate.transactionType.equals(transaction.transactionType, ignoreCase = true)
                            val wrongAmount = candidate.amountPaise != transaction.amountMinor

                            if (
                                candidate.id != localId &&
                                sameReference &&
                                sameBank &&
                                sameAccount &&
                                (wrongSide || wrongAmount)
                            ) {
                                conflicts += candidate
                            }
                        }
                    }

                    for (conflict in conflicts) {
                        val voided = db.update(
                            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                            ContentValues().apply {
                                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "VOIDED")
                            },
                            FinanceDatabaseHelper.COLUMN_ID + " = ?",
                            arrayOf(conflict.id.toString())
                        )
                        if (voided > 0) {
                            com.example.financesmstracker.integration.FinanceSyncBridge
                                .enqueueVoidedTransaction(context, conflict.id)
                        }
                    }
                }

                if (matchingLocal != null && notificationLocal != null && notificationLocal.id != localId) {
                    val notificationAge = kotlin.math.abs(
                        notificationLocal.timestamp - matchingLocal.timestamp
                    )
                    if (
                        notificationAge <= 2L * 60L * 60L * 1000L ||
                        !transaction.reference.isNullOrBlank()
                    ) {
                        val voided = db.update(
                            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                            ContentValues().apply {
                                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "VOIDED")
                            },
                            FinanceDatabaseHelper.COLUMN_ID + " = ?",
                            arrayOf(notificationLocal.id.toString())
                        )
                        if (voided > 0) {
                            com.example.financesmstracker.integration.FinanceSyncBridge
                                .enqueueVoidedTransaction(context, notificationLocal.id)
                        }
                    }
                }

                if (existingRemoteId != null && existingRemoteId != localId) {
                    db.delete(
                        FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                        FinanceDatabaseHelper.COLUMN_ID + " = ?",
                        arrayOf(existingRemoteId.toString())
                    )
                }
                return localId
            }
        }

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountMinor)
            put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType)
            put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType)
            put(FinanceDatabaseHelper.COLUMN_BANK, notificationBank)
            put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantOrPayee)
            putNull(FinanceDatabaseHelper.COLUMN_PAYEE_ID)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLast4)
            put(
                FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                com.example.financesmstracker.util.TransactionReferenceNormalizer.normalize(transaction.reference)
            )
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

    /**
     * Clears transaction history kept only on the Android device.
     * Category memory is intentionally preserved.
     */
    fun clearLocalHistory(): LocalHistoryClearResult {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val evidenceDeleted = db.delete(
                FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
                null,
                null
            )
            val transactionsDeleted = db.delete(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                null,
                null
            )
            val unrecognizedSmsDeleted = db.delete(
                FinanceDatabaseHelper.TABLE_UNRECOGNIZED_SMS,
                null,
                null
            )

            db.setTransactionSuccessful()
            return LocalHistoryClearResult(
                transactions = transactionsDeleted,
                evidence = evidenceDeleted,
                unrecognizedSms = unrecognizedSmsDeleted
            )
        } finally {
            db.endTransaction()
        }
    }

    /** Finds likely notification/SMS duplicates that deserve human review. */
    fun getPotentialTransactionConflicts(): List<TransactionConflict> {
        val transactions = getAllTransactions()
        val conflicts = mutableListOf<TransactionConflict>()
        for (i in transactions.indices) {
            val first = transactions[i]
            for (j in i + 1 until transactions.size) {
                val second = transactions[j]
                val firstNotification = first.smsHash.startsWith("notification:")
                val secondNotification = second.smsHash.startsWith("notification:")
                if (firstNotification == secondNotification) continue
                if (first.amountPaise != second.amountPaise) continue
                if (!first.currency.equals(second.currency, ignoreCase = true)) continue
                if (first.transactionType != second.transactionType) continue
                if (kotlin.math.abs(first.timestamp - second.timestamp) > 2L * 60L * 1000L) continue

                val bankConflict = !first.bank.isNullOrBlank() && !second.bank.isNullOrBlank() &&
                    !first.bank.equals(second.bank, ignoreCase = true)
                val accountConflict = !first.accountLastFour.isNullOrBlank() &&
                    !second.accountLastFour.isNullOrBlank() && first.accountLastFour != second.accountLastFour
                val referenceConflict = !first.refNumber.isNullOrBlank() &&
                    !second.refNumber.isNullOrBlank() && !first.refNumber.equals(second.refNumber, ignoreCase = true)

                if (bankConflict || accountConflict || referenceConflict) {
                    val reasons = buildList {
                        if (bankConflict) add("different banks: " + first.bank + " vs " + second.bank)
                        if (accountConflict) add("different accounts: " + first.accountLastFour + " vs " + second.accountLastFour)
                        if (referenceConflict) add("different references")
                    }
                    conflicts += TransactionConflict(first, second, reasons.joinToString(", "))
                }
            }
        }
        return conflicts
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

data class LocalHistoryClearResult(
    val transactions: Int,
    val evidence: Int,
    val unrecognizedSms: Int
)


data class TransactionConflict(
    val first: Transaction,
    val second: Transaction,
    val reason: String
)
