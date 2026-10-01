package com.example.financesmstracker.truecaller

/**
 * Identifies Gmail notifications that are likely to be bank emails.
 *
 * The notification is only a trigger for an Oracle Gmail fetch; it is not
 * parsed into a transaction on the phone.
 */
object GmailNotificationClassifier {
    private val bankHints = listOf(
        "axis bank",
        "alerts@axis.bank.in",
        "alerts@axisbank.com",
        "icici bank",
        "credit_cards@icici.bank.in",
        "credit_cards@icicibank.com",
        "customernotification@icici.bank.in",
        "customercare@icicibank.com",
        "hdfc bank",
        "hdfcbank.net",
        "hdfcbank.bank.in",
        "sbi card",
        "onlinesbicard@sbicard.com",
        "paynet@billdesk.in",
        "hsbc",
        "mail.hsbc.co.in",
        "indusind"
    )

    fun isLikelyBankNotification(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?
    ): Boolean {
        val haystack = listOfNotNull(title, text, bigText, subText)
            .joinToString(" ")
            .lowercase()

        return bankHints.any(haystack::contains)
    }
}
