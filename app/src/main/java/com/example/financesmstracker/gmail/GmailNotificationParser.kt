package com.example.financesmstracker.gmail

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses the transaction details that are already present in a Gmail notification.
 *
 * This is intentionally a fast, conservative parser. Gmail remains the
 * authoritative clarification source; notification data is used immediately
 * so the transaction can appear without waiting for an IMAP scan.
 */
data class GmailNotificationTransaction(
    val amountPaise: Long,
    val currency: String = "INR",
    val transactionType: TransactionType,
    val paymentMethod: PaymentMethod,
    val accountType: AccountType,
    val bank: String?,
    val merchantName: String?,
    val payeeId: String?,
    val accountLastFour: String?,
    val refNumber: String?,
    val confidence: Float
)

object GmailNotificationParser {
    private val amountRegex = Regex(
        """(?i)(?:INR|Rs\.?|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""
    )
    private val lastFourRegex = Regex(
        """(?i)(?:(?:account|a/c)\s*(?:ending|no\.?|number)?|(?:credit\s+)?card\s*(?:ending|no\.?|number)?)\s*[:#-]?\s*(?:x{2,}|\*{2,})?\s*(\d{4})"""
    )
    private val referenceRegex = Regex(
        """(?i)(?:transaction\s+)?reference\s*(?:no\.?|number)?\s*[:#-]?\s*([A-Za-z0-9-]{6,})"""
    )
    private val vpaRegex = Regex(
        """(?i)(?:towards|to)\s+(?:VPA\s+)?([A-Za-z0-9._-]+@[A-Za-z0-9._-]+)"""
    )
    private val parenthesizedNameRegex = Regex("""\(([^()]{2,80})\)""")

    fun parse(title: String?, text: String?, bigText: String?, subText: String?): GmailNotificationTransaction? {
        val haystack = listOfNotNull(title, text, bigText, subText)
            .joinToString(" ")
            .replace("\u00A0", " ")
            .trim()

        if (haystack.isBlank()) return null

        val amountMatch = amountRegex.find(haystack) ?: return null
        val amountPaise = runCatching {
            BigDecimal(amountMatch.groupValues[1].replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return null

        val lower = haystack.lowercase()

        // Debit must win when a credit-card purchase says "spent on credit card".
        // The word "credit" by itself is not proof of a credit transaction.
        val direction = when {
            Regex("""\b(?:debited|debit|paid|withdrawn|spent|purchase|charged)\b""").containsMatchIn(lower) ->
                TransactionType.DEBIT
            Regex("""\b(?:credited|received|deposited)\b""").containsMatchIn(lower) ->
                TransactionType.CREDIT
            else -> TransactionType.UNKNOWN
        }

        if (direction == TransactionType.UNKNOWN || amountPaise <= 0L) return null

        val bank = when {
            lower.contains("axis") -> "AXIS"
            lower.contains("hdfc") -> "HDFC"
            lower.contains("icici") -> "ICICI"
            lower.contains("sbi") -> "SBI"
            lower.contains("indusind") -> "INDUSIND"
            lower.contains("hsbc") -> "HSBC"
            else -> null
        }

        val accountLastFour = lastFourRegex.find(haystack)?.groupValues?.getOrNull(1)

        val reference = referenceRegex.find(haystack)?.groupValues?.getOrNull(1)

        val vpa = vpaRegex.find(haystack)?.groupValues?.getOrNull(1)

        val merchantName = when {
            vpa != null -> {
                parenthesizedNameRegex.find(haystack)?.groupValues?.getOrNull(1)?.trim()
            }
            else -> null
        }

        val isCreditCard = lower.contains("credit card") ||
            lower.contains("credit_cards") ||
            lower.contains("sbi card") ||
            lower.contains("card payment")

        val accountType = if (isCreditCard) {
            AccountType.CREDIT_CARD
        } else {
            AccountType.BANK_ACCOUNT
        }

        val paymentMethod = when {
            vpa != null || lower.contains("upi") -> PaymentMethod.UPI
            isCreditCard || lower.contains("card") -> PaymentMethod.CARD
            lower.contains("neft") -> PaymentMethod.NEFT
            lower.contains("imps") -> PaymentMethod.IMPS
            lower.contains("rtgs") -> PaymentMethod.RTGS
            else -> PaymentMethod.UNKNOWN
        }

        var confidence = 0.70f
        if (bank != null) confidence += 0.10f
        if (accountLastFour != null) confidence += 0.08f
        if (reference != null) confidence += 0.08f
        if (vpa != null) confidence += 0.04f

        return GmailNotificationTransaction(
            amountPaise = amountPaise,
            transactionType = direction,
            paymentMethod = paymentMethod,
            accountType = accountType,
            bank = bank,
            merchantName = merchantName,
            payeeId = vpa,
            accountLastFour = accountLastFour,
            refNumber = reference,
            confidence = confidence.coerceAtMost(0.98f)
        )
    }
}
