package com.example.financesmstracker.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GmailNotificationIdentityTest {

    @Test
    fun sameNotificationInstanceHasStableIdentity() {
        val first = GmailNotificationListenerService.notificationIdentity(
            "com.google.android.gm",
            "0|com.google.android.gm|axis-alert|123",
            1_790_000_000_000L
        )
        val second = GmailNotificationListenerService.notificationIdentity(
            "com.google.android.gm",
            "0|com.google.android.gm|axis-alert|123",
            1_790_000_000_000L
        )

        assertEquals(first, second)
    }

    @Test
    fun differentPostTimeCreatesDifferentNotificationInstance() {
        val first = GmailNotificationListenerService.notificationIdentity(
            "com.google.android.gm",
            "0|com.google.android.gm|axis-alert|123",
            1_790_000_000_000L
        )
        val second = GmailNotificationListenerService.notificationIdentity(
            "com.google.android.gm",
            "0|com.google.android.gm|axis-alert|123",
            1_790_000_001_000L
        )

        assertNotEquals(first, second)
    }
}
