package com.example.financesmstracker.truecaller

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.evidence.CrossSourceMatchCoordinator
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.util.HashUtil
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TruecallerNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "TruecallerListener"
        private const val TRUECALLER_PACKAGE_CANDIDATE = "truecaller"
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val GMAIL_NOTIFICATION_DEBOUNCE_MS = 15_000L

        private val gmailSyncExecutor = Executors.newSingleThreadExecutor()
        private val gmailSyncInFlight = AtomicBoolean(false)
        private val lastGmailSyncTriggerAt = AtomicLong(0L)
        
        private val dedupManager = TruecallerDedupManager()

        // In-memory debug observation list (minimum info retained, zero raw content storage beyond hash)
        private val observedNotifications = Collections.synchronizedList(LinkedList<ParsedNotification>())

        fun getObservedNotifications(): List<ParsedNotification> {
            synchronized(observedNotifications) {
                return ArrayList(observedNotifications)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (handleGmailNotification(sbn)) {
            return
        }

        try {
            val pkg = sbn.packageName ?: return
            if (!pkg.contains(TRUECALLER_PACKAGE_CANDIDATE, ignoreCase = true)) {
                return
            }

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val postTime = sbn.postTime
            val key = sbn.key
            val id = sbn.id
            val tag = sbn.tag

            val rawContent = "${title.orEmpty()} ${text.orEmpty()}"
            val contentHash = HashUtil.sha256(rawContent)

            val parsed = TruecallerNotificationParser.parse(title, text, postTime, contentHash)
            if (parsed.isNotificationTransaction) {
                val evaluation = dedupManager.evaluate(
                    key = key,
                    packageName = pkg,
                    id = id,
                    tag = tag,
                    postTime = postTime,
                    amountPaise = parsed.amountPaise,
                    direction = parsed.direction,
                    bank = parsed.bankProvider,
                    contentHash = contentHash
                )

                Log.d(
                    "FinanceSource",
                    "DEDUP_RESULT: ${evaluation.result} | keyFP: ${evaluation.keyFingerprint}, id: $id, tag: ${tag ?: "null"}, postTime: $postTime, hashFP: ${evaluation.contentHashPrefix}, amount: ${parsed.amountPaise}, currency: ${parsed.currency}, direction: ${parsed.direction}, bank: ${parsed.bankProvider ?: "null"}"
                )

                if (evaluation.result == DedupResult.NEW_NOTIFICATION || evaluation.result == DedupResult.KEPT_SEPARATE) {
                    Log.d(TAG, "Observed Unique Truecaller Transaction -> Amount: ${parsed.amountPaise} ${parsed.currency}, Direction: ${parsed.direction}, Bank: ${parsed.bankProvider}")
                    Log.d("FinanceSource", "TRUECALLER_NOTIFICATION_PARSED -> Amount: ${parsed.amountPaise}, Currency: ${parsed.currency}, Direction: ${parsed.direction}, Bank: ${parsed.bankProvider}, Timestamp: $postTime")
                    
                    // Persist SourceEvidence as UNMATCHED initially
                    val dbHelper = FinanceDatabaseHelper(applicationContext)
                    val repository = TransactionRepository(dbHelper)
                    val sourceKey = sbn.key ?: "tc_${sbn.packageName}_$postTime"
                    val evidence = SourceEvidence(
                        sourceType = SourceType.TRUECALLER,
                        sourceKey = sourceKey,
                        receivedAt = postTime,
                        transactionId = null,
                        amountPaise = parsed.amountPaise,
                        currency = parsed.currency,
                        direction = parsed.direction.name,
                        bankProvider = parsed.bankProvider,
                        accountLastFour = null,
                        reference = null,
                        contentHash = contentHash,
                        confidence = 0.90f,
                        status = EvidenceStatus.UNMATCHED
                    )
                    val evidenceId = repository.insertSourceEvidence(evidence)
                    if (evidenceId != -1L) {
                        Log.d("FinanceSource", "TRUECALLER_EVIDENCE_CREATED -> evidenceId: $evidenceId, transactionId: null, amount: ${parsed.amountPaise}, currency: ${parsed.currency}, direction: ${parsed.direction}, bank: ${parsed.bankProvider}, timestamp: $postTime, status: ${EvidenceStatus.UNMATCHED}")
                        
                        // Event-driven matching: evaluate this new evidence immediately against existing canonical transactions
                        val coordinator = CrossSourceMatchCoordinator(
                            repository = repository,
                            onEvidenceReconciled = { reconciledEvidence ->
                                // Truecaller is evidence only. It enters the Oracle
                                // ledger after it has been matched to a canonical
                                // Android transaction.
                                FinanceSyncBridge.enqueueEvidence(applicationContext, reconciledEvidence)
                            }
                        )
                        coordinator.onSourceEvidenceCreated(evidenceId)
                    }
                    repository.logNewestEvidenceSummary()
                    dbHelper.close()

                    observedNotifications.add(parsed)
                    if (observedNotifications.size > 50) {
                        observedNotifications.removeAt(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    /**
     * Gmail notifications are only an event trigger. The actual email is still
     * fetched and parsed by Oracle, so notification text is never stored as
     * financial evidence and cannot create a transaction by itself.
     */
    private fun handleGmailNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != GMAIL_PACKAGE) {
            return false
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!GmailNotificationClassifier.isLikelyBankNotification(title, text, bigText, subText)) {
            return true
        }

        val now = SystemClock.elapsedRealtime()
        val lastTriggered = lastGmailSyncTriggerAt.get()
        if (now - lastTriggered < GMAIL_NOTIFICATION_DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate Gmail bank notification trigger within debounce window")
            return true
        }

        if (!gmailSyncInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Gmail notification sync already in progress")
            return true
        }

        lastGmailSyncTriggerAt.set(now)
        Log.d(TAG, "Gmail bank notification detected -> triggering Gmail sync")

        gmailSyncExecutor.execute {
            try {
                val result = FinanceSyncClient(applicationContext).triggerGmailSync()
                result.onSuccess { sync ->
                    Log.d(
                        "FinanceSource",
                        "GMAIL_NOTIFICATION_SYNC -> scanned=${sync.messagesScanned}, parsed=${sync.parsedTransactions}, duplicates=${sync.duplicateTransactions}, reviews=${sync.reviewCount}"
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Gmail notification sync failed: ${error.message}", error)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Gmail notification sync crashed", error)
            } finally {
                gmailSyncInFlight.set(false)
            }
        }

        return true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op
    }
}
