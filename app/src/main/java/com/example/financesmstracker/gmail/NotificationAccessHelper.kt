package com.example.financesmstracker.gmail

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

object NotificationAccessHelper {
    private const val PREFS = "gmail_notification_listener"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_CONNECTED_AT = "connected_at"
    private const val KEY_LAST_GMAIL_EVENT_AT = "last_gmail_event_at"
    private const val KEY_LAST_GMAIL_TITLE = "last_gmail_title"
    private const val KEY_LAST_GMAIL_TEXT = "last_gmail_text"
    private const val KEY_LAST_GMAIL_PARSED_AT = "last_gmail_parsed_at"
    private const val KEY_LAST_GMAIL_PARSE_ERROR = "last_gmail_parse_error"

    fun setListenerConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putLong(KEY_CONNECTED_AT, if (connected) System.currentTimeMillis() else 0L)
            .apply()
    }

    fun recordGmailNotification(context: Context, title: String?, text: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_GMAIL_EVENT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_GMAIL_TITLE, title.orEmpty())
            .putString(KEY_LAST_GMAIL_TEXT, text.orEmpty())
            .apply()
    }

    fun recordGmailNotificationParsed(context: Context, parsed: Boolean, error: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_GMAIL_PARSED_AT, if (parsed) System.currentTimeMillis() else 0L)
            .putString(KEY_LAST_GMAIL_PARSE_ERROR, error.orEmpty())
            .apply()
    }

    fun lastGmailNotificationAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_GMAIL_EVENT_AT, 0L)

    fun lastGmailNotificationTitle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GMAIL_TITLE, "")
            .orEmpty()

    fun lastGmailNotificationText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GMAIL_TEXT, "")
            .orEmpty()

    fun lastGmailNotificationParsedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_GMAIL_PARSED_AT, 0L)

    fun lastGmailNotificationParseError(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GMAIL_PARSE_ERROR, "")
            .orEmpty()

    fun requestRebind(context: Context) {
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(context, GmailNotificationListenerService::class.java)
            )
        }
    }

    fun isListenerConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONNECTED, false)

    fun isNotificationAccessGranted(context: Context): Boolean {
        val component = ComponentName(
            context,
            GmailNotificationListenerService::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) == true
        }

        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )

        if (TextUtils.isEmpty(flat)) return false

        return flat.split(":").any { name ->
            ComponentName.unflattenFromString(name)?.let {
                it.packageName == context.packageName &&
                    it.className == component.className
            } == true
        }
    }
}
