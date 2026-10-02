package com.example.financesmstracker.parser

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Detects the destination-side confirmation that a credit-card bill payment
 * was actually applied to a card. This is intentionally stricter than normal
 * debit parsing: a bank-account debit alone is never treated as bill payment.
 */
data class CardBillPaymentSms(
    val amountPaise: Long,
    val cardBank: String,
    val cardLastFour: String,
    val paymentMethod: PaymentMethod,
    val reference: String?,
    val confidence: Float = 0.98f
)

object CardBillPaymentSmsParser {
    private val amountRegex = Regex(
        """(?i)(?:INR|Rs\.?|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""
    )

    private val cardWithBankRegex = Regex(
        """(?i)\b(SBI|AXIS|HDFC|ICICI|INDUSIND|HSBC|KOTAK)(?:\s+BANK)?\s+(?:CREDIT\s+CARD|CARD)\b.{0,40}?(?:NO\.?|NUMBER|ENDING(?:\s+IN)?|XX+|X{2,}|\*{2,})?\s*[- ]?([0-9]{2,4})\b"""
    )

    private val cardSuffixRegex = Regex(
        """(?i)\b(?:credit\s+card|card)\s+(?:no\.?|number|ending(?:\s+in)?|ending)?\s*(?:XXXX|XXX|XX|X{2,}|\*{2,})?[- ]?([0-9]{4})\b"""
    )

    private val bankRegexes = listOf(
        "HDFC" to Regex("""(?i)\bHDFC(?:\s+Bank)?\b"""),
        "AXIS" to Regex("""(?i)\bAxis(?:\s+Bank)?\b"""),
        "ICICI" to Regex("""(?i)\bICICI(?:\s+Bank)?\b"""),
        "SBI" to Regex("""(?i)\bSBI(?:\s+Card|\s+Bank)?\b"""),
        "INDUSIND" to Regex("""(?i)\bIndusInd(?:\s+Bank)?\b"""),
        "HSBC" to Regex("""(?i)\bHSBC(?:\s+Bank)?\b"""),
        "KOTAK" to Regex("""(?i)\bKotak(?:\s+Bank)?\b""")
    )

    private val referenceRegex = Regex(
        """(?i)\b(?:ref(?:erence)?|txn(?:action)?\s+ref(?:erence)?|transaction\s+id)\s*(?:no\.?|number|:)\s*([A-Za-z0-9-]{6,})\b"""
    )

    fun parse(sender: String?, messageBody: String): CardBillPaymentSms? {
        if (messageBody.isBlank()) return null

        val text = messageBody.replace("\u00A0", " ").trim()
        val lower = text.lowercase()
        if (!Regex("""\bcredit\s+card\b""").containsMatchIn(lower)) return null

        val destinationPaymentSignal =
            Regex("""(?is)(?:credited\s+to|payment\s+(?:received|processed|successful|completed|credited)|received\s+(?:towards|for|on)|paid\s+(?:towards|for|on|to)).{0,120}\bcredit\s+card\b""").containsMatchIn(lower) ||
            Regex("""(?is)\bcredit\s+card\b.{0,100}(?:payment\s+(?:received|processed|successful|completed)|has\s+been\s+(?:processed|credited)|credited\s+to)""").containsMatchIn(lower)

        if (!destinationPaymentSignal) return null

        val amountMatch = amountRegex.find(text) ?: return null
        val amountPaise = runCatching {
            BigDecimal(amountMatch.groupValues[1].replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return null
        if (amountPaise <= 0L) return null

        val bankAndCard = cardWithBankRegex.find(text)
        val lastFour = bankAndCard?.groupValues?.getOrNull(2)
            ?: cardSuffixRegex.find(text)?.groupValues?.getOrNull(1)
            ?: return null

        val bank = bankAndCard?.groupValues?.getOrNull(1)?.let(::normalizePaymentBank)
            ?: bankRegexes.firstOrNull { it.second.containsMatchIn(text) }?.first
            ?: return null

        val method = when {
            Regex("""(?i)\bUPI\b""").containsMatchIn(text) -> PaymentMethod.UPI
            Regex("""(?i)\bBBPS\b""").containsMatchIn(text) -> PaymentMethod.BANK_TRANSFER
            Regex("""(?i)\bNEFT\b""").containsMatchIn(text) -> PaymentMethod.NEFT
            Regex("""(?i)\bIMPS\b""").containsMatchIn(text) -> PaymentMethod.IMPS
            Regex("""(?i)\bRTGS\b""").containsMatchIn(text) -> PaymentMethod.RTGS
            else -> PaymentMethod.UNKNOWN
        }

        return CardBillPaymentSms(
            amountPaise = amountPaise,
            cardBank = bank,
            cardLastFour = lastFour,
            paymentMethod = method,
            reference = referenceRegex.find(text)?.groupValues?.getOrNull(1)
        )
    }

    private fun normalizePaymentBank(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "sbi" -> "SBI"
            "axis" -> "AXIS"
            "hdfc" -> "HDFC"
            "icici" -> "ICICI"
            "indusind" -> "INDUSIND"
            "hsbc" -> "HSBC"
            "kotak" -> "KOTAK"
            else -> null
        }
    }
}
