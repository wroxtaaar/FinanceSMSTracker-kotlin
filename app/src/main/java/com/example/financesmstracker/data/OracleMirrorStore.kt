package com.example.financesmstracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.integration.OracleTransaction
import com.example.financesmstracker.util.TransactionReferenceNormalizer

internal class OracleMirrorStore(
    private val dbHelper: FinanceDatabaseHelper,
    private val context: Context,
    private val queries: TransactionQueries
) {

    /**
     * Upserts a transaction received from the durable Oracle Gmail ledger into
     * the phone's local transaction list. The remote Gmail id is stored in the
     * existing unique sms_hash column with an explicit prefix, so this works
     * without a destructive database migration and remains idempotent.
     */
    /**
     * Bring back server history that the phone no longer has (for example
     * after a reinstall). Rows already on the phone are never touched:
     * a server row is skipped when a local row already maps to its id, when
     * it is a Gmail row (the normal pull handles those), or when an equal
     * amount/direction/bank event within two minutes is already here.
     * Restored rows carry the "oracle:gmail:<id>" marker so deleting or
     * correcting them reaches the right server row. Nothing is sent back.
     */
    fun restoreMissingFromOracle(
        remote: List<OracleTransaction>,
        scheme: com.example.financesmstracker.integration.OracleIds.Scheme,
        isCleared: (OracleTransaction) -> Boolean
    ): Int {
        val db = dbHelper.writableDatabase
        val local = queries.getAllTransactions()
        val represented = local
            .map { com.example.financesmstracker.integration.FinanceSyncBridge.oracleVoidId(it.id, it.smsHash, scheme) }
            .toHashSet()
        var restored = 0

        remote.forEach { r ->
            if (r.id.startsWith("gmail:") || r.id in represented || isCleared(r)) return@forEach

            val sameEventPresent = local.any { l ->
                l.amountPaise == r.amountMinor &&
                    l.transactionType.name.equals(r.transactionType, ignoreCase = true) &&
                    kotlin.math.abs(l.timestamp - r.timestamp) <= 120_000L &&
                    (normalizedBank(l.bank) == null || normalizedBank(r.bank) == null ||
                        normalizedBank(l.bank) == normalizedBank(r.bank)) &&
                    (l.accountLastFour.isNullOrBlank() || r.accountLast4.isNullOrBlank() ||
                        l.accountLastFour == r.accountLast4)
            }
            if (sameEventPresent) return@forEach

            val values = ContentValues().apply {
                put(FinanceDatabaseHelper.COLUMN_AMOUNT_PAISE, r.amountMinor)
                put(FinanceDatabaseHelper.COLUMN_CURRENCY, r.currency)
                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_TYPE, r.transactionType)
                put(FinanceDatabaseHelper.COLUMN_PAYMENT_METHOD, r.paymentMethod)
                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_TYPE, r.accountType)
                put(FinanceDatabaseHelper.COLUMN_BANK, r.bank)
                put(FinanceDatabaseHelper.COLUMN_MERCHANT_NAME, r.merchantOrPayee)
                put(FinanceDatabaseHelper.COLUMN_ACCOUNT_LAST_FOUR, r.accountLast4)
                put(FinanceDatabaseHelper.COLUMN_REF_NUMBER, r.reference)
                put(FinanceDatabaseHelper.COLUMN_TIMESTAMP, r.timestamp)
                put(FinanceDatabaseHelper.COLUMN_SMS_HASH, "oracle:gmail:" + r.id)
                put(FinanceDatabaseHelper.COLUMN_CATEGORY, r.category)
                put(FinanceDatabaseHelper.COLUMN_PARSER_CONFIDENCE, r.confidence)
            }
            val rowId = db.insertWithOnConflict(
                FinanceDatabaseHelper.TABLE_TRANSACTIONS, null, values, SQLiteDatabase.CONFLICT_IGNORE
            )
            if (rowId != -1L) restored++
        }
        return restored
    }

    fun upsertOracleGmailTransaction(transaction: OracleTransaction): Long =
        GmailRowUpsert(dbHelper.writableDatabase, context, transaction).run()
}

/** A local transaction row considered as the counterpart of a Gmail ledger row. */
private data class LocalCandidate(
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

private val CANDIDATE_COLUMNS = arrayOf(
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
)

private fun readCandidate(it: android.database.Cursor) = LocalCandidate(
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

/**
 * One upsert of a Gmail row from Oracle into the phone's list: find the local
 * SMS/notification row for the same payment (and merge into it), otherwise
 * insert or refresh the "oracle:gmail:<id>" row.
 */
private class GmailRowUpsert(
    private val db: SQLiteDatabase,
    private val context: Context,
    private val transaction: OracleTransaction
) {
    private val remoteMarker = "oracle:gmail:" + transaction.id

    fun run(): Long {
        val existingRemoteId = findExistingRemoteId()

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
            val merged = mergeIntoLocal(resolvedLocal, matchingLocal, notificationLocal, existingRemoteId)
            if (merged != null) return merged
        }
        return insertOrRefreshRemoteRow(existingRemoteId)
    }

    private fun findExistingRemoteId(): Long? = db.query(
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

    private fun normalized(value: String?): String? =
        value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
            ?.lowercase()

    private fun normalizedReference(value: String?): String? =
        com.example.financesmstracker.util.TransactionReferenceNormalizer.normalize(value)?.lowercase()

    private fun candidateScore(candidate: LocalCandidate, maxAgeMs: Long): Int? {
        val remoteBank = normalizedBank(transaction.bank)?.lowercase()
        val remoteLast4 = normalized(transaction.accountLast4)
        val remoteReference = normalizedReference(transaction.reference)
        val localBank = normalizedBank(candidate.bank)?.lowercase()
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
                localReference == null &&
                candidate.accountType.equals(transaction.accountType, ignoreCase = true) &&
                candidate.paymentMethod.equals(PaymentMethod.UNKNOWN.name, ignoreCase = true) &&
                (
                    (remoteLast4 != null && localLast4 == remoteLast4) ||
                        (remoteBank != null && localBank == null)
                )

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

    private fun findBestCandidate(isNotification: Boolean, maxAgeMs: Long): LocalCandidate? {
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
            CANDIDATE_COLUMNS,
            selection,
            args,
            null,
            null,
            FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
            if (!isNotification && remoteReference != null) null else "50"
        ).use {
            while (it.moveToNext()) {
                val candidate = readCandidate(it)
                val score = candidateScore(candidate, maxAgeMs) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
        }

        return best
    }

    /** Returns the local row id on success, or null when nothing was updated. */
    private fun mergeIntoLocal(
        resolvedLocal: LocalCandidate,
        matchingLocal: LocalCandidate?,
        notificationLocal: LocalCandidate?,
        existingRemoteId: Long?
    ): Long? {
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
            // The same Gmail row may already exist locally from an earlier
            // sync. We are about to move the provisional notification row
            // onto that Gmail marker. Remove the older Gmail copy first so
            // the unique sms_hash constraint cannot crash the app.
            if (existingRemoteId != null && existingRemoteId != localId) {
                deleteRow(existingRemoteId)
            }
            values.put(FinanceDatabaseHelper.COLUMN_SMS_HASH, remoteMarker)
        }

        val updated = db.update(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            values,
            FinanceDatabaseHelper.COLUMN_ID + " = ?",
            arrayOf(localId.toString())
        )
        if (updated <= 0) return null

        voidConflictingReferenceRows(localId)
        voidSupersededNotification(localId, matchingLocal, notificationLocal)

        if (existingRemoteId != null && existingRemoteId != localId) {
            deleteRow(existingRemoteId)
        }
        return localId
    }

    private fun deleteRow(id: Long) {
        db.delete(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            FinanceDatabaseHelper.COLUMN_ID + " = ?",
            arrayOf(id.toString())
        )
    }

    private fun voidRow(id: Long) {
        val voided = db.update(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            ContentValues().apply {
                put(FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS, "VOIDED")
            },
            FinanceDatabaseHelper.COLUMN_ID + " = ?",
            arrayOf(id.toString())
        )
        if (voided > 0) {
            com.example.financesmstracker.integration.FinanceSyncBridge
                .enqueueVoidedTransaction(context, id)
        }
    }

    /**
     * A reference can arrive after a bad provisional row was created. Void any
     * active same-bank/same-account row carrying the same reference but the
     * wrong amount or ledger direction.
     */
    private fun voidConflictingReferenceRows(localId: Long) {
        val remoteReference = normalizedReference(transaction.reference) ?: return
        val conflicts = mutableListOf<LocalCandidate>()
        db.query(
            FinanceDatabaseHelper.TABLE_TRANSACTIONS,
            CANDIDATE_COLUMNS,
            FinanceDatabaseHelper.COLUMN_TRANSACTION_STATUS + " = ? AND " +
                FinanceDatabaseHelper.COLUMN_REF_NUMBER + " IS NOT NULL",
            arrayOf("ACTIVE"),
            null,
            null,
            FinanceDatabaseHelper.COLUMN_TIMESTAMP + " DESC",
            null
        ).use {
            while (it.moveToNext()) {
                val candidate = readCandidate(it)
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
        for (conflict in conflicts) voidRow(conflict.id)
    }

    /** Once a real SMS row is matched, the provisional notification copy is void. */
    private fun voidSupersededNotification(
        localId: Long,
        matchingLocal: LocalCandidate?,
        notificationLocal: LocalCandidate?
    ) {
        if (matchingLocal == null || notificationLocal == null || notificationLocal.id == localId) return
        val notificationAge = kotlin.math.abs(notificationLocal.timestamp - matchingLocal.timestamp)
        if (
            notificationAge <= 2L * 60L * 60L * 1000L ||
            !transaction.reference.isNullOrBlank()
        ) {
            voidRow(notificationLocal.id)
        }
    }

    private fun insertOrRefreshRemoteRow(existingRemoteId: Long?): Long {
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
}
