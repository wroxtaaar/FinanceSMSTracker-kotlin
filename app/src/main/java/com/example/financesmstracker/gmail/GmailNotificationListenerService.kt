package com.example.financesmstracker.gmail

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.financesmstracker.categorizer.CategoryMemoryKey
import com.example.financesmstracker.categorizer.TransactionCategorizer
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.evidence.CrossSourceMatchCoordinator
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Gmail bank notifications are the fast first-pass transaction source.
 *
 * Flow:
 * Gmail notification -> parse immediately -> match SMS evidence
 * -> create local transaction if needed -> sync to Oracle
 * -> trigger Gmail/IMAP sync in background for final clarification.
 *
 * Android notification listeners receive notification posted events and the
 * notification payload can be read from the notification extras.
 */
class GmailNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "GmailNotificationListener"
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val GMAIL_NOTIFICATION_DEBOUNCE_MS = 15_000L

        private val gmailSyncExecutor = Executors.newSingleThreadExecutor()
        private val notificationExecutor = Executors.newSingleThreadExecutor()
        private val gmailSyncInFlight = AtomicBoolean(false)
        private val lastGmailSyncTriggerAt = AtomicLong(0L)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationAccessHelper.setListenerConnected(applicationContext, true)
        Log.i(TAG, "Notification listener connected")

        // If the service was disconnected/killed while Gmail posted the
        // notification, onNotificationPosted may already have been missed.
        // Process currently visible Gmail notifications once after binding.
        runCatching {
            getActiveNotifications()
                .filter { it.packageName == GMAIL_PACKAGE }
                .forEach { sbn ->
                    processNotificationSafely(sbn)
                }
            triggerBackgroundGmailSync()
        }.onFailure { error ->
            Log.e(TAG, "Could not inspect active Gmail notifications", error)
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected; requesting rebind")
        NotificationAccessHelper.setListenerConnected(applicationContext, false)
        super.onListenerDisconnected()
        runCatching {
            requestRebind(android.content.ComponentName(this, GmailNotificationListenerService::class.java))
        }.onFailure { error ->
            Log.w(TAG, "Notification listener rebind request failed", error)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GMAIL_PACKAGE) return

        Log.d(
            TAG,
            "Gmail notification posted: title=" +
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) +
                " text=" +
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        )

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!GmailNotificationClassifier.isLikelyBankNotification(title, text, bigText, subText)) {
            return
        }

        notificationExecutor.execute {
            try {
                processNotification(sbn, title, text, bigText, subText)
            } catch (error: Exception) {
                Log.e(TAG, "Immediate Gmail notification processing failed", error)
            } finally {
                // Only after the fast notification path has run do we start the
                // slower Gmail/IMAP clarification pass.
                triggerBackgroundGmailSync()
            }
        }
    }

    private fun processNotificationSafely(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!GmailNotificationClassifier.isLikelyBankNotification(title, text, bigText, subText)) {
            return
        }

        notificationExecutor.execute {
            try {
                processNotification(sbn, title, text, bigText, subText)
            } catch (error: Exception) {
                Log.e(TAG, "Active Gmail notification processing failed", error)
            }
        }
    }

    private fun processNotification(
        sbn: StatusBarNotification,
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?
    ) {
        val parsed = GmailNotificationParser.parse(title, text, bigText, subText)
        if (parsed == null) {
            Log.d(TAG, "Bank Gmail notification detected but details were not parseable; IMAP sync will clarify it")
            return
        }

        val content = listOfNotNull(title, text, bigText, subText).joinToString("\n")
        val contentHash = HashUtil.sha256(content)
        val sourceKey = "gmail-notification:" + contentHash

        val dbHelper = FinanceDatabaseHelper(applicationContext)
        val repository = TransactionRepository(dbHelper)

        try {
            val parserResult = ParserResult(
                isTransaction = true,
                amountPaise = parsed.amountPaise,
                transactionType = parsed.transactionType,
                paymentMethod = parsed.paymentMethod,
                accountType = parsed.accountType,
                bank = parsed.bank,
                merchantName = parsed.merchantName,
                payeeId = parsed.payeeId,
                accountLastFour = parsed.accountLastFour,
                refNumber = parsed.refNumber,
                confidence = parsed.confidence
            )

            val evidence = SourceEvidence(
                sourceType = SourceType.GMAIL_NOTIFICATION,
                sourceKey = sourceKey,
                receivedAt = sbn.postTime,
                amountPaise = parsed.amountPaise,
                currency = parsed.currency,
                direction = if (parsed.transactionType == TransactionType.CREDIT) "CREDIT" else "DEBIT",
                bankProvider = parsed.bank,
                accountLastFour = parsed.accountLastFour,
                reference = parsed.refNumber,
                contentHash = contentHash,
                confidence = parsed.confidence,
                status = EvidenceStatus.UNMATCHED
            )

            val evidenceId = repository.insertSourceEvidence(evidence)
            if (evidenceId == -1L) {
                Log.d(TAG, "Duplicate Gmail notification evidence skipped")
                return
            }

            val coordinator = CrossSourceMatchCoordinator(
                repository = repository,
                onEvidenceReconciled = { reconciledEvidence ->
                    // Notification evidence is local-first. Oracle currently
                    // receives the canonical transaction; Gmail/SMS remain the
                    // durable cross-source evidence paths.
                    if (reconciledEvidence.sourceType != SourceType.GMAIL_NOTIFICATION) {
                        FinanceSyncBridge.enqueueEvidence(applicationContext, reconciledEvidence)
                    }
                }
            )

            // First preference: match the notification to an SMS transaction
            // that arrived at roughly the same time.
            coordinator.onSourceEvidenceCreated(evidenceId)

            var persistedEvidence = repository.getSourceEvidenceById(evidenceId)
            if (persistedEvidence?.status == EvidenceStatus.MATCHED) {
                Log.d(TAG, "Gmail notification matched existing transaction " + persistedEvidence.transactionId)
                return
            }

            // If SMS has not arrived yet, create the transaction immediately.
            // Later SMS/Gmail evidence can reconcile against this row.
            val memoryKey = CategoryMemoryKey.from(parserResult)
            val rememberedCategory = memoryKey?.let { repository.getCategoryForMemoryKey(it) }
            val category = rememberedCategory ?: TransactionCategorizer.categorize(parserResult, content)

            val transaction = Transaction(
                amountPaise = parsed.amountPaise,
                currency = parsed.currency,
                transactionType = parsed.transactionType,
                paymentMethod = parsed.paymentMethod,
                accountType = parsed.accountType,
                bank = parsed.bank,
                merchantName = parsed.merchantName,
                payeeId = parsed.payeeId,
                accountLastFour = parsed.accountLastFour,
                refNumber = parsed.refNumber,
                timestamp = sbn.postTime,
                smsHash = "notification:" + contentHash,
                category = category,
                parserConfidence = parsed.confidence
            )

            val rowId = repository.insertTransaction(transaction)
            if (rowId == -1L) {
                Log.d(TAG, "Notification transaction already exists for content hash")
                return
            }

            // Re-run matching now that the immediate notification transaction exists.
            coordinator.onCanonicalTransactionCreated(rowId)
            persistedEvidence = repository.getSourceEvidenceById(evidenceId)

            val canonical = repository.getTransactionById(rowId)
            if (canonical != null) {
                FinanceSyncBridge.enqueueCanonical(
                    applicationContext,
                    canonical,
                    null
                )
            }

            Log.d(
                TAG,
                "Immediate Gmail notification transaction saved: id=" + rowId +
                    " amount=" + parsed.amountPaise +
                    " type=" + parsed.transactionType +
                    " bank=" + parsed.bank +
                    " ref=" + parsed.refNumber
            )
        } finally {
            dbHelper.close()
        }
    }

    private fun triggerBackgroundGmailSync() {
        val now = SystemClock.elapsedRealtime()
        val lastTriggered = lastGmailSyncTriggerAt.get()
        if (now - lastTriggered < GMAIL_NOTIFICATION_DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate Gmail bank notification trigger within debounce window")
            return
        }

        if (!gmailSyncInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Gmail notification sync already in progress")
            return
        }

        lastGmailSyncTriggerAt.set(now)
        Log.d(TAG, "Gmail bank notification detected -> triggering background Oracle Gmail sync")

        gmailSyncExecutor.execute {
            try {
                val result = FinanceSyncClient(applicationContext).triggerGmailSync()
                result.onSuccess { sync ->
                    Log.d(
                        TAG,
                        "Background Gmail clarification -> scanned=" + sync.messagesScanned +
                            ", parsed=" + sync.parsedTransactions +
                            ", duplicates=" + sync.duplicateTransactions +
                            ", reviews=" + sync.reviewCount
                    )

                    // Pull the clarified Gmail rows immediately so the local
                    // notification transaction is enriched even when the app
                    // UI is not currently open.
                    FinanceSyncClient(applicationContext).fetchGmailTransactions()
                        .onSuccess { transactions ->
                            val dbHelper = FinanceDatabaseHelper(applicationContext)
                            val repository = TransactionRepository(dbHelper)
                            try {
                                transactions.forEach { remote ->
                                    val rowId = repository.upsertOracleGmailTransaction(remote)
                                    if (rowId != 0L) {
                                        repository.getTransactionById(rowId)?.let { local ->
                                            FinanceSyncBridge.enqueueCanonical(
                                                applicationContext,
                                                local
                                            )
                                        }
                                    }
                                }
                            } finally {
                                dbHelper.close()
                            }
                        }
                        .onFailure { error ->
                            Log.w(TAG, "Could not pull Gmail clarification rows: " + error.message, error)
                        }
                }.onFailure { error ->
                    Log.w(TAG, "Background Gmail clarification failed: " + error.message, error)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Background Gmail clarification crashed", error)
            } finally {
                gmailSyncInFlight.set(false)
            }
        }
    }
}
