package com.example.financesmstracker.gmail

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.financesmstracker.integration.FinanceSyncClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Watches Gmail notifications only to trigger a server-side Gmail sync.
 *
 * No notification text is stored or parsed into transactions on the phone.
 */
class GmailNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "GmailNotificationListener"
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val GMAIL_NOTIFICATION_DEBOUNCE_MS = 15_000L

        private val gmailSyncExecutor = Executors.newSingleThreadExecutor()
        private val gmailSyncInFlight = AtomicBoolean(false)
        private val lastGmailSyncTriggerAt = AtomicLong(0L)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GMAIL_PACKAGE) {
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!GmailNotificationClassifier.isLikelyBankNotification(title, text, bigText, subText)) {
            return
        }

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
        Log.d(TAG, "Gmail bank notification detected -> triggering Oracle Gmail sync")

        gmailSyncExecutor.execute {
            try {
                val result = FinanceSyncClient(applicationContext).triggerGmailSync()
                result.onSuccess { sync ->
                    Log.d(
                        TAG,
                        "Gmail notification sync -> scanned=${sync.messagesScanned}, parsed=${sync.parsedTransactions}, duplicates=${sync.duplicateTransactions}, reviews=${sync.reviewCount}"
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
    }
}
