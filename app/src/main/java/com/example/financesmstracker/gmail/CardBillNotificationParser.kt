package com.example.financesmstracker.gmail

import java.math.BigDecimal
import java.math.RoundingMode

data class CardBillNotification(
    val amountPaise: Long?,
    val bank: String?,
    val accountLastFour: String?,
    val accountLastTwo: String?
)

object CardBillNotificationParser {
    private val amountPatterns = listOf(
        Regex("""(?is)total\s+amount\s+due.{0,160}?(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""),
        Regex("""(?is)total\s+amt\s*[:\-]?\s*(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""")
    )

    private val cardRegex = Regex(
        """(?i)\b(?:credit\s+card|card)\s+(?:no\.?|number|ending(?:\s+in)?)\s*(?:X{1,8}|\*{1,8})?\s*[- ]?(\d{2,4})\b"""
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

    fun parse(title: String?, text: String?, bigText: String?, subText: String?): CardBillNotification? {
        val combined = listOfNotNull(title, text, bigText, subText)
            .joinToString("\n")
            .replace("\u00A0", " ")
            .trim()

        if (combined.isBlank()) return null

        val statementSignal =
            Regex("""(?i)\bstatement\b|\btotal\s+amount\s+due\b|\be[- ]?statement\b""")
                .containsMatchIn(combined)

        if (!statementSignal) return null

        val bank = bankRegexes.firstOrNull { it.second.containsMatchIn(combined) }?.first
        val cardMatch = cardRegex.find(combined)
        if (cardMatch == null) return null

        val visible = cardMatch.groupValues[1]
        val amount = amountPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(combined)?.let {
                runCatching {
                    BigDecimal(it.groupValues[1].replace(",", ""))
                        .setScale(2, RoundingMode.HALF_UP)
                        .movePointRight(2)
                        .longValueExact()
                }.getOrNull()
            }
        }

        return CardBillNotification(
            amountPaise = amount,
            bank = bank,
            accountLastFour = visible.takeIf { it.length == 4 },
            accountLastTwo = visible.takeLast(2)
        )
    }
}
