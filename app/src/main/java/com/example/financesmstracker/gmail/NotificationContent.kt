package com.example.financesmstracker.gmail

import android.app.Notification
import android.os.Bundle

/** The text fields of a posted notification, read once and joined the same way everywhere. */
class NotificationContent(
    val title: String?,
    titleBig: String?,
    val text: String?,
    val bigText: String?,
    subText: String?,
    summary: String?,
    val textLines: String?
) {
    /** Every non-empty field, in a fixed order, one per line. */
    val payload: String = joinPayload(title, titleBig, text, bigText, subText, summary, textLines)

    /** The short body shown in diagnostics: the first of text, big text, text lines. */
    val preview: String? get() = text ?: bigText ?: textLines

    companion object {
        fun from(extras: Bundle): NotificationContent = NotificationContent(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }
        )

        fun joinPayload(vararg parts: String?): String = parts.filterNotNull().joinToString("\n")
    }
}
