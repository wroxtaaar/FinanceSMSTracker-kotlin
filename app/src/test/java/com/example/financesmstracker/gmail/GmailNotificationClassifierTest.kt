package com.example.financesmstracker.gmail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailNotificationClassifierTest {
    @Test
    fun detectsAxisBankAlert() {
        assertTrue(
            GmailNotificationClassifier.isLikelyBankNotification(
                title = "Axis Bank Alerts",
                text = "INR 1.00 was credited to your A/c.",
                bigText = null,
                subText = null
            )
        )
    }

    @Test
    fun detectsIciciCreditCardAlert() {
        assertTrue(
            GmailNotificationClassifier.isLikelyBankNotification(
                title = "ICICI Bank",
                text = "Transaction alert for your ICICI Bank Credit Card",
                bigText = null,
                subText = null
            )
        )
    }

    @Test
    fun rejectsNonBankGmailNotification() {
        assertFalse(
            GmailNotificationClassifier.isLikelyBankNotification(
                title = "Gmail",
                text = "Your package was delivered today",
                bigText = null,
                subText = null
            )
        )
    }
}
