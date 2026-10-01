package com.example.financesmstracker.gmail

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object NotificationAccessHelper {

    fun isNotificationAccessGranted(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex())
            for (name in names) {
                val componentName = ComponentName.unflattenFromString(name)
                if (componentName != null && componentName.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }
}
