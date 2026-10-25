package com.example.financesmstracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.MatchResult
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.integration.OracleTransaction
import com.example.financesmstracker.util.TransactionReferenceNormalizer

class TransactionRepository(private val dbHelper: FinanceDatabaseHelper, private val context: Context) {

    private val queries = TransactionQueries(dbHelper)
    private val evidenceStore = EvidenceStore(dbHelper, queries)
    private val unrecognizedStore = UnrecognizedSmsStore(dbHelper)
    private val categoryStore = CategoryStore(dbHelper)
    private val duplicates = DuplicateResolver(dbHelper, queries)
    private val oracleMirror = OracleMirrorStore(dbHelper, context, queries)

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
                    FinanceDatabaseHelper.COLUMN_SMS_HASH,
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE,
                    FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD,
                    FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE
                ),
                "(" +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " LIKE ? OR " +
                    FinanceDatabaseHelper.COLUMN_SMS_HASH + " LIKE ?" +
                    ") AND " +
                    FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE + " = ? AND " +
                    FinanceDatabaseHelper.COLUMN_CURRENCY + " = ? AND " +
                    "ABS(" + FinanceDatabaseHelper.COLUMN_TIMESTAMP + " - ?) <= ?",
                arrayOf(
                    "notification:%",
                    "oracle:gmail:%",
                    "ACTIVE",
                    transaction.amountPaise.toString(),
                    transaction.currency,
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
                    val notificationType = it.getString(5)
                    val notificationPaymentMethod = it.getString(6)
                    val notificationAccountType = it.getString(7)

                    val bankCompatible =
                        notificationBank.isNullOrBlank() ||
                            transaction.bank.isNullOrBlank() ||
                            normalizedBank(notificationBank) == normalizedBank(transaction.bank)

                    val last4Compatible =
                        notificationLast4.isNullOrBlank() ||
                            transaction.accountLastFour.isNullOrBlank() ||
                            notificationLast4 == transaction.accountLastFour

                    val referenceCompatible =
                        notificationReference.isNullOrBlank() ||
                            transaction.refNumber.isNullOrBlank() ||
                            notificationReference.equals(transaction.refNumber, ignoreCase = true)

                    val directionCompatible =
                        notificationType.equals(transaction.transactionType.name, ignoreCase = true) ||
                            (
                                notificationBank.isNullOrBlank() &&
                                    notificationReference.isNullOrBlank() &&
                                    notificationPaymentMethod.equals(PaymentMethod.UNKNOWN.name, ignoreCase = true) &&
                                    notificationAccountType.equals(transaction.accountType.name, ignoreCase = true)
                            )

                    if (bankCompatible && last4Compatible && referenceCompatible && directionCompatible) {
                        val notificationId = it.getLong(0)

                        // SMS is authoritative over a provisional Gmail/Oracle row.
                        // If Gmail sync ran before the SMS arrived, reuse that canonical
                        // row instead of creating a second SMS transaction.
                        val values = ContentValues().apply {
                            // New evidence enriches the canonical row; it must never
                            // erase a reference/details captured by an earlier source.
                            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountPaise)
                            put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
                            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType.name)
                            if (transaction.paymentMethod != PaymentMethod.UNKNOWN) {
                                put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod.name)
                            }
                            if (transaction.accountType != AccountType.UNKNOWN) {
                                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType.name)
                            }
                            if (!transaction.bank.isNullOrBlank()) {
                                put(FinanceDatabaseHelper.COLUMN_BANK, transaction.bank)
                            }
                            if (!transaction.merchantName.isNullOrBlank() && transaction.merchantName != "-") {
                                put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantName)
                            }
                            if (!transaction.payeeId.isNullOrBlank()) {
                                put(FinanceDatabaseHelper.COLUMN_PAYEE_ID, transaction.payeeId)
                            }
                            if (!transaction.accountLastFour.isNullOrBlank()) {
                                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLastFour)
                            }
                            TransactionReferenceNormalizer.normalize(transaction.refNumber)?.let {
                                put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, it)
                            }
                            put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, transaction.timestamp)
                            put(FinanceDatabaseHelper.COLUMN_SMS_HASH, transaction.smsHash)
                            if (!transaction.category.isNullOrBlank()) {
                                put(FinanceDatabaseHelper.COLUMN_CATEGORY, transaction.category)
                            }
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

        // Some bank SMS variants expose the same real transaction with a
        // different body/hash. Prefer the existing canonical row when strong
        // same-side identity proves that this is the same event.
        if (!transaction.smsHash.startsWith("notification:")) {
            val semanticDuplicate = duplicates.findCanonicalDuplicate(db, transaction)
            if (semanticDuplicate != null) {
                val values = ContentValues().apply {
                    if (TransactionDuplicateRules.isGenericMerchant(semanticDuplicate.merchantName, semanticDuplicate.bank) && !transaction.merchantName.isNullOrBlank()) {
                        put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, transaction.merchantName)
                    }
                    if (semanticDuplicate.payeeId.isNullOrBlank() && !transaction.payeeId.isNullOrBlank()) {
                        put(FinanceDatabaseHelper.COLUMN_PAYEE_ID, transaction.payeeId)
                    }
                    if (semanticDuplicate.bank.isNullOrBlank() && !transaction.bank.isNullOrBlank()) {
                        put(FinanceDatabaseHelper.COLUMN_BANK, transaction.bank)
                    }
                    if (semanticDuplicate.accountLastFour.isNullOrBlank() && !transaction.accountLastFour.isNullOrBlank()) {
                        put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, transaction.accountLastFour)
                    }
                    if (TransactionReferenceNormalizer.normalize(semanticDuplicate.refNumber) == null &&
                        !transaction.refNumber.isNullOrBlank()) {
                        put(
                            FinanceDatabaseHelper.COLUMN_REF_NUMBER,
                            TransactionReferenceNormalizer.normalize(transaction.refNumber)
                        )
                    }
                    if (transaction.parserConfidence > semanticDuplicate.parserConfidence) {
                        put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, transaction.parserConfidence)
                    }
                }

                if (values.size() > 0) {
                    db.update(
                        FinanceDatabaseHelper.TABLE_TRANSACTIONS,
                        values,
                        FinanceDatabaseHelper.COLUMN_ID + " = ?",
                        arrayOf(semanticDuplicate.id.toString())
                    )
                }
                return semanticDuplicate.id
            }
        }

        val values = ContentValues().apply {
            put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, transaction.amountPaise)
            put(FinanceDatabaseHelper.COLUMN_CURRENCY, transaction.currency)
            put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, transaction.transactionType.name)
            put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, transaction.paymentMethod.name)
            put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, transaction.accountType.name)
            put(FinanceDatabaseHelper.COLUMN_BANK, notificationBank)
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

    fun deleteTransaction(id: Long): Int {
        val db = dbHelper.writableDatabase

        return db.delete(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            "${FinanceDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    // --- Delegates: the public API is unchanged; the work lives in the stores. ---

    fun repairCanonicalDuplicates(): List<Long> =
        duplicates.repairCanonicalDuplicates()

    fun insertSourceEvidence(evidence: SourceEvidence): Long =
        evidenceStore.insertSourceEvidence(evidence)

    fun insertUnrecognizedSms(unrecognized: UnrecognizedSms): Long =
        unrecognizedStore.insertUnrecognizedSms(unrecognized)

    fun getUnresolvedUnrecognizedSms(): List<UnrecognizedSms> =
        unrecognizedStore.getUnresolvedUnrecognizedSms()

    fun getUnresolvedUnrecognizedSmsCount(): Int =
        unrecognizedStore.getUnresolvedUnrecognizedSmsCount()

    fun updateUnrecognizedSmsStatus(id: Long, status: ReviewStatus): Int =
        unrecognizedStore.updateUnrecognizedSmsStatus(id, status)

    fun getUnrecognizedSmsByHash(contentHash: String): UnrecognizedSms? =
        unrecognizedStore.getUnrecognizedSmsByHash(contentHash)

    fun getSourceEvidenceById(id: Long): SourceEvidence? =
        evidenceStore.getSourceEvidenceById(id)

    fun applyMatchResult(evidenceId: Long, result: MatchResult) =
        evidenceStore.applyMatchResult(evidenceId, result)

    fun getUnresolvedEvidence(): List<SourceEvidence> =
        evidenceStore.getUnresolvedEvidence()

    fun getUnresolvedEvidenceCount(): Int =
        evidenceStore.getUnresolvedEvidenceCount()

    fun getCandidateTransactionsForEvidence(evidence: SourceEvidence): List<Transaction> =
        evidenceStore.getCandidateTransactionsForEvidence(evidence)

    fun resolveEvidenceToTransaction(evidenceId: Long, transactionId: Long): SourceEvidence? =
        evidenceStore.resolveEvidenceToTransaction(evidenceId, transactionId)

    fun getTransactionsByReference(reference: String): List<Transaction> =
        queries.getTransactionsByReference(reference)

    fun getUnmatchedOrAmbiguousEvidenceByReference(reference: String): List<SourceEvidence> =
        evidenceStore.getUnmatchedOrAmbiguousEvidenceByReference(reference)

    fun getTransactionsByAmount(amountPaise: Long): List<Transaction> =
        queries.getTransactionsByAmount(amountPaise)

    fun getUnmatchedOrAmbiguousEvidenceByAmount(amountPaise: Long): List<SourceEvidence> =
        evidenceStore.getUnmatchedOrAmbiguousEvidenceByAmount(amountPaise)

    fun getSourceEvidenceByKey(sourceKey: String): SourceEvidence? =
        evidenceStore.getSourceEvidenceByKey(sourceKey)

    fun updateSourceEvidenceMatchPublic(id: Long, transactionId: Long, status: EvidenceStatus): Int =
        evidenceStore.updateSourceEvidenceMatchPublic(id, transactionId, status)

    fun logNewestEvidenceSummary(limit: Int = 5) =
        evidenceStore.logNewestEvidenceSummary(limit)

    fun getTransactionById(id: Long): Transaction? =
        queries.getTransactionById(id)

    fun restoreMissingFromOracle(
        remote: List<OracleTransaction>,
        scheme: com.example.financesmstracker.integration.OracleIds.Scheme,
        isCleared: (OracleTransaction) -> Boolean
    ): Int =
        oracleMirror.restoreMissingFromOracle(remote, scheme, isCleared)

    fun upsertOracleGmailTransaction(transaction: OracleTransaction): Long =
        oracleMirror.upsertOracleGmailTransaction(transaction)

    fun getPotentialTransactionConflicts(): List<TransactionConflict> =
        duplicates.getPotentialTransactionConflicts()

    fun getAllTransactions(): List<Transaction> =
        queries.getAllTransactions()

    fun getSmsHashById(id: Long): String? =
        queries.getSmsHashById(id)

    fun updateTransactionCategory(id: Long, category: String): Int =
        categoryStore.updateTransactionCategory(id, category)

    fun updateCategoriesForMemoryKey(memoryKey: String, category: String): Int =
        categoryStore.updateCategoriesForMemoryKey(memoryKey, category)

    fun getTransactionsByMemoryKey(memoryKey: String): List<Transaction> =
        queries.getTransactionsByMemoryKey(memoryKey)

    fun getTransactionsByPayee(payeeId: String): List<Transaction> =
        queries.getTransactionsByPayee(payeeId)

    fun getTransactionsByDateRange(startTime: Long, endTime: Long): List<Transaction> =
        queries.getTransactionsByDateRange(startTime, endTime)

    fun saveCategoryMemory(memoryKey: String, category: String) =
        categoryStore.saveCategoryMemory(memoryKey, category)

    fun getCategoryForMemoryKey(memoryKey: String): String? =
        categoryStore.getCategoryForMemoryKey(memoryKey)
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
