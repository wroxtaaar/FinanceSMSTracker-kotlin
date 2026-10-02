package com.example.financesmstracker.gmail

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

object NotificationAccessHelper {

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
