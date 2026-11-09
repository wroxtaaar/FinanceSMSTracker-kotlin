package com.example.financesmstracker.data

/** Where a history row came from, read from the marker stored with it. */
object TransactionSource {
    fun label(smsHash: String): String = when {
        smsHash.startsWith("manual-card-payment:") -> "Marked by you"
        smsHash.startsWith("oracle:gmail:") -> "E-mail"
        smsHash.startsWith("notification:") -> "Notification"
        // The card's own "payment received" message (SMS or a Messages notification).
        smsHash.startsWith("card-bill-payment:") -> "Card confirmation"
        else -> "SMS"
    }
}
