package com.example.financesmstracker.gmail

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses CRED bill-payment notifications into the credit-card side of the
 * ledger. The bank SMS/Gmail transaction represents the cash leaving the
 * bank; this notification identifies which card liability was paid.
 */
data class CardBillPaymentNotification(
    val amountPaise: Long,
    val cardBank: String,
    val cardLastFour: String,
    val merchantName: String = "CRED Club",
    val confidence: Float = 0.98f
)

object CardBillPaymentNotificationParser {
    private val amountRegex = Regex(
        """(?i)(?:INR|Rs\.?|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""
    )

    private val cardRegex = Regex(
        """(?i)your\s+([A-Za-z]+(?:\s+[A-Za-z]+)?)\s+credit\s+card\s+(?:x{2,}|\*{2,}|X{2,}|\*?X+)\s*[- ]?\s*(\d{4})"""
    )

    private val genericCardRegex = Regex(
        """(?i)\b([A-Za-z]+)\s+credit\s+card\s+(?:x{2,}|\*{2,}|\*?X+)\s*[- ]?\s*(\d{4})\b"""
    )

    fun parse(title: String?, text: String?, bigText: String?, subText: String?): CardBillPaymentNotification? {
        val haystack = listOfNotNull(title, text, bigText, subText)
            .joinToString(" ")
            .replace("\u00A0", " ")
            .trim()

        if (haystack.isBlank()) return null

        val lower = haystack.lowercase()
        val billPaymentSignal =
            Regex("""\bpayment\s+of\b""").containsMatchIn(lower) &&
                Regex("""\bcredit\s+card\b""").containsMatchIn(lower) &&
                Regex("""\b(?:processed|successful|completed|paid)\b""").containsMatchIn(lower)

        if (!billPaymentSignal) return null

        val amountMatch = amountRegex.find(haystack) ?: return null
        val amountPaise = runCatching {
            BigDecimal(amountMatch.groupValues[1].replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return null

        if (amountPaise <= 0L) return null

        val cardMatch = cardRegex.find(haystack) ?: genericCardRegex.find(haystack) ?: return null
        val bank = normalizeBank(cardMatch.groupValues[1]) ?: return null
        val lastFour = cardMatch.groupValues[2]

        return CardBillPaymentNotification(
            amountPaise = amountPaise,
            cardBank = bank,
            cardLastFour = lastFour
        )
    }

    private fun normalizeBank(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "sbi" -> "SBI"
            "axis" -> "AXIS"
            "hdfc" -> "HDFC"
            "icici" -> "ICICI"
            "indusind" -> "INDUSIND"
            "hsbc" -> "HSBC"
            "kotak" -> "KOTAK"
            "amex", "american express" -> "AMEX"
            else -> null
        }
    }
}
