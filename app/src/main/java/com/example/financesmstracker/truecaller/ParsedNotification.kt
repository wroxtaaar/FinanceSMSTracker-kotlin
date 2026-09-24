package com.example.financesmstracker.truecaller

enum class NotificationDirection {
    DEBIT,
    CREDIT,
    UNKNOWN
}

data class ParsedNotification(
    val isNotificationTransaction: Boolean,
    val amountPaise: Long = 0L,
    val currency: String = "INR",
    val direction: NotificationDirection = NotificationDirection.UNKNOWN,
    val bankProvider: String? = null,
    val merchantName: String? = null,
    val timestamp: Long = 0L,
    val contentHash: String = ""
)
