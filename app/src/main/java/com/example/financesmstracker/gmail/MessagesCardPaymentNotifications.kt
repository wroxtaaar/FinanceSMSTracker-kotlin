package com.example.financesmstracker.gmail

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import com.example.financesmstracker.parser.CardBillPaymentSmsParser
import com.example.financesmstracker.receiver.CardBillPaymentRecorder
import com.example.financesmstracker.receiver.SmsHandlingLog
import com.example.financesmstracker.receiver.SmsReceiver
import com.example.financesmstracker.util.HashUtil

/**
 * Card issuers also send "payment received" confirmations as RCS Business Chat
 * messages. Those never arrive as SMS, so the SMS receiver cannot see them; the
 * Messages app only shows them as notifications.
 *
 * Only a message that reads as a card-payment confirmation is used, and every
 * other notification (personal chats included) is dropped without being logged.
 */
object MessagesCardPaymentNotifications {
    const val PACKAGE = "com.google.android.apps.messaging"

    fun handle(context: Context, sbn: StatusBarNotification) {
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val content = NotificationContent.from(sbn.notification.extras)
        val title = content.title.orEmpty()
        if (looksLikePhoneNumber(title)) return
        val body = (content.text ?: content.bigText).orEmpty().trim()
        if (body.isEmpty()) return

        val parsed = CardBillPaymentSmsParser.parse(title, body) ?: return

        // Hash only the message text, like the SMS path, so the same confirmation
        // delivered both ways is one record.
        val outcome = CardBillPaymentRecorder.record(
            context = context,
            timestamp = sbn.postTime,
            smsHash = HashUtil.sha256(body),
            parsed = parsed,
            onlyMatchMarked = false
        )
        SmsHandlingLog.record(context, "Messages notification: $title", body, outcome)
        context.sendBroadcast(
            Intent(SmsReceiver.ACTION_TRANSACTION_DATA_CHANGED).setPackage(context.packageName)
        )
    }

    /** A personal chat is titled with a number or a contact, never an issuer id. */
    internal fun looksLikePhoneNumber(title: String): Boolean =
        title.isNotBlank() && title.count { it.isDigit() } >= 7
}
