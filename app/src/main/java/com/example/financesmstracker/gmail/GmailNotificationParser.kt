package com.example.financesmstracker.gmail

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses the transaction details that are already present in a Gmail notification.
 *
 * Gmail remains the authoritative clarification source. The notification parser
 * is deliberately scoped to the transaction line containing the amount so that
 * unrelated footer text such as "credit card", "card payment", or bill-payment
 * instructions cannot change the transaction's direction/account type.
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

    private val explicitDebitRegex = Regex(
        """(?i)\b(?:was|has been|is|is being)?\s*debited\b|\b(?:spent|purchased|purchase|charged|paid|withdrawn)\b"""
    )
    private val explicitCreditRegex = Regex(
        """(?i)\b(?:was|has been|is|is being)?\s*credited\b|\b(?:received|deposited)\b"""
    )

    /**
     * Keep classification scoped to the line that contains the first amount.
     * Gmail notifications frequently append unrelated card/bill information
     * after the transaction line.
     */
    private fun transactionLine(haystack: String, amountMatch: MatchResult): String {
        val lines = haystack.split("\n")
        return lines.firstOrNull { amountRegex.containsMatchIn(it) }
            ?: haystack.substring(
                maxOf(0, amountMatch.range.first - 160),
                minOf(haystack.length, amountMatch.range.last + 161)
            )
    }

    fun parse(title: String?, text: String?, bigText: String?, subText: String?): GmailNotificationTransaction? {
        val haystack = listOfNotNull(title, text, bigText, subText)
            .joinToString("\n")
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

        val line = transactionLine(haystack, amountMatch)
        val lowerLine = line.lowercase()
        val issuerText = listOfNotNull(title, line).joinToString(" ").lowercase()

        // Direction is determined from the transaction line, never from the
        // entire notification. This prevents a bank-account credit from being
        // changed into a debit merely because the Gmail footer says "credit card".
        val hasDebit = explicitDebitRegex.containsMatchIn(lowerLine)
        val hasCredit = explicitCreditRegex.containsMatchIn(lowerLine)
        val direction = when {
            hasDebit && !hasCredit -> TransactionType.DEBIT
            hasCredit && !hasDebit -> TransactionType.CREDIT
            hasDebit && hasCredit -> {
                when {
                    Regex("""(?i)\bdebited\b""").containsMatchIn(lowerLine) -> TransactionType.DEBIT
                    Regex("""(?i)\bcredited\b""").containsMatchIn(lowerLine) -> TransactionType.CREDIT
                    else -> TransactionType.UNKNOWN
                }
            }
            else -> TransactionType.UNKNOWN
        }

        if (direction == TransactionType.UNKNOWN || amountPaise <= 0L) return null

        // Identify the issuer from the complete notification. Counterparty VPAs
        // can contain bank names (for example @axisb) and must not override the
        // issuer inferred from the notification itself.
        val bank = when {
            Regex("""\baxis(?:\s+bank)?\b""").containsMatchIn(issuerText) -> "AXIS"
            Regex("""\bhdfc(?:\s+bank)?\b""").containsMatchIn(issuerText) -> "HDFC"
            Regex("""\bicici(?:\s+bank)?\b""").containsMatchIn(issuerText) -> "ICICI"
            Regex("""\bsbi(?:\s+card|\s+bank)?\b""").containsMatchIn(issuerText) -> "SBI"
            Regex("""\bindusind(?:\s+bank)?\b""").containsMatchIn(issuerText) -> "INDUSIND"
            Regex("""\bhsbc(?:\s+bank)?\b""").containsMatchIn(issuerText) -> "HSBC"
            else -> null
        }

        // Account/card identity must come from the transaction line too. A
        // footer can mention another card and should not become this transaction's
        // account number.
        val accountLastFour = lastFourRegex.find(line)?.groupValues?.getOrNull(1)
        val reference = referenceRegex.find(line)?.groupValues?.getOrNull(1)
        val vpa = vpaRegex.find(line)?.groupValues?.getOrNull(1)

        val merchantName = if (vpa != null) {
            parenthesizedNameRegex.find(line)?.groupValues?.getOrNull(1)?.trim()
        } else {
            null
        }

        val isCreditCard = Regex(
            """(?i)\bcredit\s+card\b|\bcard\s+(?:no\.?|number|ending)\b"""
        ).containsMatchIn(lowerLine)

        val accountType = if (isCreditCard) {
            AccountType.CREDIT_CARD
        } else {
            AccountType.BANK_ACCOUNT
        }

        val paymentMethod = when {
            vpa != null || Regex("""(?i)\bUPI\b""").containsMatchIn(lowerLine) -> PaymentMethod.UPI
            isCreditCard -> PaymentMethod.CARD
            Regex("""(?i)\bNEFT\b""").containsMatchIn(lowerLine) -> PaymentMethod.NEFT
            Regex("""(?i)\bIMPS\b""").containsMatchIn(lowerLine) -> PaymentMethod.IMPS
            Regex("""(?i)\bRTGS\b""").containsMatchIn(lowerLine) -> PaymentMethod.RTGS
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
