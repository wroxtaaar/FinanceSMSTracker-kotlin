package com.example.financesmstracker.truecaller

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.financesmstracker.util.HashUtil
import java.util.Collections
import java.util.LinkedList

class TruecallerNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "TruecallerListener"
        private const val TRUECALLER_PACKAGE_CANDIDATE = "truecaller"
        
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
                    "DEDUP_RESULT: ${evaluation.result} | keyFP: ${evaluation.keyFingerprint}, id: $id, tag: ${tag ?: "null"}, postTime: $postTime, hashFP: ${evaluation.contentHashPrefix}, amount: ${parsed.amountPaise}, direction: ${parsed.direction}, bank: ${parsed.bankProvider ?: "null"}"
                )

                if (evaluation.result == DedupResult.NEW_NOTIFICATION || evaluation.result == DedupResult.KEPT_SEPARATE) {
                    Log.d(TAG, "Observed Unique Truecaller Transaction -> Amount: ${parsed.amountPaise} paise, Direction: ${parsed.direction}, Bank: ${parsed.bankProvider}")
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op
    }
}
