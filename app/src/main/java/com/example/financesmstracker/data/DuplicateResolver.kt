package com.example.financesmstracker.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.TransactionReferenceNormalizer

internal class DuplicateResolver(
    private val dbHelper: FinanceDatabaseHelper,
    private val queries: TransactionQueries
) {

    private fun normalizedMerchant(value: String?): String? =
        value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) && it != "-" }
            ?.uppercase()

    private fun merchantsCompatible(first: String?, second: String?): Boolean {
        val a = normalizedMerchant(first)
        val b = normalizedMerchant(second)
        if (a == null || b == null) return true
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun transactionsAreSafeDuplicates(first: Transaction, second: Transaction): Boolean =
        TransactionDuplicateRules.safeDuplicates(first, second)

    fun findCanonicalDuplicate(
        db: SQLiteDatabase,
        transaction: Transaction
    ): Transaction? {
        if (transaction.smsHash.startsWith("notification:") ||
            transaction.smsHash.startsWith("oracle:gmail:")
        ) return null

        val candidates = mutableListOf<Transaction>()
        db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ? AND " +
                FinanceDatabaseHelper.COLUMN_SMS_HASH + " NOT LIKE ?",
            arrayOf(
                "ACTIVE",
                transaction.amountPaise.toString(),
                transaction.currency,
                transaction.transactionType.name,
                transaction.accountType.name,
                "notification:%",
                "oracle:gmail:%"
            ),
            null,
            null,
            FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
            "100"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = cursorToTransaction(cursor)
                if (candidate.id == 0L) continue
                if (transactionsAreSafeDuplicates(candidate, transaction)) {
                    candidates += candidate
                }
            }
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { candidate ->
            val hasReference = if (TransactionReferenceNormalizer.normalize(candidate.refNumber) != null) 1000 else 0
            val hasIdentity =
                if (!candidate.bank.isNullOrBlank() && !candidate.accountLastFour.isNullOrBlank()) 100 else 0
            val merchant = if (!candidate.merchantName.isNullOrBlank() && candidate.merchantName != "-") 10 else 0
            val confidence = (candidate.parserConfidence * 100).toInt()
            hasReference + hasIdentity + merchant + confidence
        } ?: return null

        return if (candidates.count { transactionsAreSafeDuplicates(it, transaction) && (
            (TransactionReferenceNormalizer.normalize(it.refNumber) != null) ==
                (TransactionReferenceNormalizer.normalize(best.refNumber) != null)
        ) } == 1 || candidates.size == 1) {
            best
        } else {
            // Multiple equally plausible same-side events are left alone rather
            // than risking the collapse of two legitimate payments.
            null
        }
    }

    /**
     * Repairs already-stored active local duplicates. A transaction with an
     * exact RRN is stronger than an otherwise identical row without one.
     * Opposite-side transfers remain separate because direction is mandatory.
     */
    fun repairCanonicalDuplicates(): List<Long> {
        val db = dbHelper.writableDatabase
        val rows = mutableListOf<Transaction>()

        db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            null,
            FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ?",
            arrayOf("ACTIVE"),
            null,
            null,
            FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToTransaction(cursor)
            }
        }

        val duplicateToCanonical = LinkedHashMap<Long, Long>()

        fun canonicalScore(transaction: Transaction): Long {
            val reference = if (TransactionReferenceNormalizer.normalize(transaction.refNumber) != null) 1_000_000L else 0L
            val source = when {
                transaction.smsHash.startsWith("oracle:gmail:") -> 0L
                transaction.smsHash.startsWith("notification:") -> 50_000L
                else -> 100_000L
            }
            val identity = if (!transaction.bank.isNullOrBlank() && !transaction.accountLastFour.isNullOrBlank()) 100_000L else 0L
            val merchant = if (!transaction.merchantName.isNullOrBlank() && transaction.merchantName != "-") 10_000L else 0L
            val confidence = (transaction.parserConfidence * 1000).toLong()
            return reference + source + identity + merchant + confidence - transaction.id
        }

        for (i in rows.indices) {
            val first = rows[i]
            if (duplicateToCanonical.containsKey(first.id)) continue

            for (j in i + 1 until rows.size) {
                val second = rows[j]
                if (duplicateToCanonical.containsKey(second.id)) continue
                if (!transactionsAreSafeDuplicates(first, second)) continue

                val canonical: Transaction
                val duplicate: Transaction
                if (canonicalScore(first) >= canonicalScore(second)) {
                    canonical = first
                    duplicate = second
                } else {
                    canonical = second
                    duplicate = first
                }

                duplicateToCanonical[duplicate.id] = canonical.id
            }
        }

        // Second pass: an instant notification and the SMS / e-mail for the
        // same event can arrive minutes apart without a shared reference. Only
        // unambiguous one-to-one pairs are merged.
        val handledIds = duplicateToCanonical.keys + duplicateToCanonical.values
        for ((first, second) in TransactionDuplicateRules.selectLoosePairs(rows, handledIds)) {
            if (canonicalScore(first) >= canonicalScore(second)) {
                duplicateToCanonical[second.id] = first.id
            } else {
                duplicateToCanonical[first.id] = second.id
            }
        }

        if (duplicateToCanonical.isEmpty()) return emptyList()

        val repaired = mutableListOf<Long>()
        db.beginTransaction()
        try {
            duplicateToCanonical.forEach { (duplicateId, canonicalId) ->
                val status = ContentValues().apply {
                    put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "VOIDED")
                }
                val updated = db.update(
                    FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                    status,
                    FinanceDatabaseHelper.COLUMN_ID + " = ? AND " +
                        FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ?",
                    arrayOf(duplicateId.toString(), "ACTIVE")
                )

                if (updated > 0) {
                    // The voided row may hold details the canonical row lacks
                    // (typically the merchant from the SMS while the canonical
                    // notification only knows the bank). Carry them over.
                    val canonicalRow = rows.firstOrNull { it.id == canonicalId }
                    val duplicateRow = rows.firstOrNull { it.id == duplicateId }
                    if (canonicalRow != null && duplicateRow != null) {
                        val enrichment = TransactionDuplicateRules.enrichmentFrom(canonicalRow, duplicateRow)
                        if (enrichment.isNotEmpty()) {
                            val enrichValues = ContentValues()
                            enrichment.merchantName?.let {
                                enrichValues.put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, it)
                            }
                            enrichment.payeeId?.let {
                                enrichValues.put(FinanceDatabaseHelper.COLUMN_PAYEE_ID, it)
                            }
                            enrichment.bank?.let {
                                enrichValues.put(FinanceDatabaseHelper.COLUMN_BANK, it)
                            }
                            enrichment.accountLastFour?.let {
                                enrichValues.put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, it)
                            }
                            enrichment.refNumber?.let {
                                enrichValues.put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, it)
                            }
                            db.update(
                                FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                                enrichValues,
                                FinanceDatabaseHelper.COLUMN_ID + " = ?",
                                arrayOf(canonicalId.toString())
                            )
                        }
                    }
                    val evidenceValues = ContentValues().apply {
                        put(FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID, canonicalId)
                        put(FinanceDatabaseHelper.COLUMN_EVIDENCE_STATUS, EvidenceStatus.MATCHED.name)
                    }
                    db.update(
                        FinanceDatabaseHelper.TABLE_SOURCE_EVIDENCE,
                        evidenceValues,
                        FinanceDatabaseHelper.COLUMN_EVIDENCE_TRANSACTION_ID + " = ?",
                        arrayOf(duplicateId.toString())
                    )
                    repaired += duplicateId
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return repaired
    }

    /** Finds likely notification/SMS duplicates that deserve human review. */
    fun getPotentialTransactionConflicts(): List<TransactionConflict> {
        val transactions = queries.getAllTransactions()
        if (transactions.size < 2) return emptyList()

        data class ConflictKey(
            val amountPaise: Long,
            val currency: String,
            val transactionType: TransactionType
        )

        val grouped = transactions.groupBy {
            ConflictKey(it.amountPaise, it.currency.uppercase(), it.transactionType)
        }

        val conflicts = mutableListOf<TransactionConflict>()

        for (group in grouped.values) {
            val notifications = group
                .filter { it.smsHash.startsWith("notification:") }
                .sortedBy { it.timestamp }

            val canonical = group
                .filterNot { it.smsHash.startsWith("notification:") }
                .sortedBy { it.timestamp }

            var canonicalStart = 0
            for (notification in notifications) {
                while (
                    canonicalStart < canonical.size &&
                    canonical[canonicalStart].timestamp < notification.timestamp - 2L * 60L * 1000L
                ) {
                    canonicalStart++
                }

                var index = canonicalStart
                while (
                    index < canonical.size &&
                    canonical[index].timestamp <= notification.timestamp + 2L * 60L * 1000L
                ) {
                    val other = canonical[index]

                    val bankConflict =
                        !notification.bank.isNullOrBlank() &&
                            !other.bank.isNullOrBlank() &&
                            !notification.bank.equals(other.bank, ignoreCase = true)

                    val accountConflict =
                        !notification.accountLastFour.isNullOrBlank() &&
                            !other.accountLastFour.isNullOrBlank() &&
                            notification.accountLastFour != other.accountLastFour

                    val referenceConflict =
                        !notification.refNumber.isNullOrBlank() &&
                            !other.refNumber.isNullOrBlank() &&
                            !notification.refNumber.equals(other.refNumber, ignoreCase = true)

                    if (bankConflict || accountConflict || referenceConflict) {
                        val reasons = buildList {
                            if (bankConflict) {
                                add("different banks: ${notification.bank} vs ${other.bank}")
                            }
                            if (accountConflict) {
                                add("different accounts: ${notification.accountLastFour} vs ${other.accountLastFour}")
                            }
                            if (referenceConflict) add("different references")
                        }
                        conflicts += TransactionConflict(notification, other, reasons.joinToString(", "))
                    }

                    index++
                }
            }
        }

        return conflicts
    }
}
