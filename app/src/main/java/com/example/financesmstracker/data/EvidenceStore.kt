package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.MatchOutcome
import com.example.financesmstracker.evidence.MatchResult
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.util.TransactionReferenceNormalizer

internal class EvidenceStore(
    private val dbHelper: FinanceDatabaseHelper,
    private val queries: TransactionQueries
) {

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
                        val matchedEvidence = cursorToEvidenceForWrite(db, evidenceId)
                        if (matchedEvidence != null) {
                            enrichTransactionFromEvidenceInternal(db, result.matchedTransactionId, matchedEvidence)
                        }
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

    private fun cursorToEvidenceForWrite(db: SQLiteDatabase, id: Long): SourceEvidence? {
        db.query(
            FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
            null,
            FinanceDatabaseHelper.COLUMN_EVIDENCE_ID + " = ?",
            arrayOf(id.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursorToEvidence(cursor)
        }
        return null
    }

    private fun enrichTransactionFromEvidenceInternal(
        db: SQLiteDatabase,
        transactionId: Long,
        evidence: SourceEvidence
    ) {
        val current = db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            FinanceDatabaseHelper.COLUMN_ID + " = ?",
            arrayOf(transactionId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursorToTransaction(cursor) else null
        } ?: return

        val values = ContentValues()
        if (TransactionReferenceNormalizer.normalize(current.refNumber) == null) {
            TransactionReferenceNormalizer.normalize(evidence.reference)?.let {
                values.put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, it)
            }
        }
        if (current.bank.isNullOrBlank() && !evidence.bankProvider.isNullOrBlank()) {
            values.put(FinanceDatabaseHelper.COLUMN_BANK, evidence.bankProvider)
        }
        if (current.accountLastFour.isNullOrBlank() && !evidence.accountLastFour.isNullOrBlank()) {
            values.put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, evidence.accountLastFour)
        }
        if (evidence.confidence > current.parserConfidence) {
            values.put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, evidence.confidence)
        }
        if (values.size() > 0) {
            db.update(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                values,
                FinanceDatabaseHelper.COLUMN_ID + " = ?",
                arrayOf(transactionId.toString())
            )
            Log.d("FinanceSource", "TRANSACTION_ENRICHED_FROM_EVIDENCE -> transactionId=$transactionId, reference=${evidence.reference}")
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
        val candidates = queries.getTransactionsByAmount(evidence.amountPaise)
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
        if (queries.getTransactionById(transactionId) == null) return null

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
}
