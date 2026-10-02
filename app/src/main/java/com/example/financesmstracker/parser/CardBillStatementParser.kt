package com.example.financesmstracker.parser

import java.math.BigDecimal
import java.math.RoundingMode

data class CardBillStatement(
    val amountPaise: Long,
    val bank: String?,
    val accountLastFour: String?,
    val accountLastTwo: String?,
    val confidence: Float
)

object CardBillStatementParser {
    private val amountPatterns = listOf(
        Regex("""(?is)total\s+amount\s+due.{0,120}?(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""),
        Regex("""(?is)total\s+amt\s*[:\-]?\s*(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""),
        Regex("""(?is)(?:total\s+amount|total\s+amt)\s*[:\-]?\s*(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""")
    )

    private val cardRegex = Regex(
        """(?i)(?:credit\s+card\s+(?:no\.?|number|ending(?:\s+in)?)|card\s+(?:no\.?|number|ending(?:\s+in)?))[^0-9]{0,30}(?:X{1,8}|\*{1,8})?\s*(\d{2,4})\b"""
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

    fun parse(sender: String?, messageBody: String): CardBillStatement? {
        if (messageBody.isBlank()) return null
        val text = messageBody.replace("\u00A0", " ").trim()
        val lower = text.lowercase()

        val statementSignal =
            Regex("""\b(?:statement|bill|total\s+amount\s+due|total\s+amt)\b""").containsMatchIn(lower) &&
                Regex("""\b(?:due|generated|amount\s+due)\b""").containsMatchIn(lower)

        if (!statementSignal) return null

        val amountMatch = amountPatterns.firstNotNullOfOrNull { it.find(text) } ?: return null
        val amountPaise = runCatching {
            BigDecimal(amountMatch.groupValues[1].replace(",", ""))
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull() ?: return null
        if (amountPaise <= 0L) return null

        val bank = bankRegexes.firstOrNull { it.second.containsMatchIn(text) }?.first
        val cardMatch = cardRegex.find(text)
        val visible = cardMatch?.groupValues?.getOrNull(1)
        if (visible.isNullOrBlank()) return null

        val digits = visible.filter { it.isDigit() }
        val lastFour = digits.takeIf { it.length == 4 }
        val lastTwo = digits.takeIf { it.length >= 2 }?.takeLast(2)

        return CardBillStatement(
            amountPaise = amountPaise,
            bank = bank,
            accountLastFour = lastFour,
            accountLastTwo = lastTwo,
            confidence = if (lastFour != null) 0.98f else 0.94f
        )
    }
}
