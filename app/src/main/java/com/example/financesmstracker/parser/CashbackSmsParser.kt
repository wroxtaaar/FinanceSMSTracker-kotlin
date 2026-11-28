package com.example.financesmstracker.parser

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A credit-card cashback confirmation, for example
 * "INR 868 cashback has been credited to your Airtel Axis Bank Credit Card no. XX9206 ...".
 * It is a credit on the card (the bill goes down) but it is a reward, not a bill payment and not
 * money someone paid back, so it gets its own CASHBACK category.
 */
data class CashbackSms(
    val amountPaise: Long,
    val cardBank: String,
    val cardLastFour: String?,
    val confidence: Float = 0.97f
)

object CashbackSmsParser {
    private val amountRegex = Regex("""(?i)(?:INR|Rs\.?|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)""")
    private val creditedRegex = Regex("""(?i)\b(?:has|have)\s+been\s+credited\b""")
    private val lastFourRegex = Regex("""(?i)credit\s+card\s*(?:no\.?|number|ending(?:\s+in)?)?\s*(?:X{2,}|\*{2,})?\s*[- ]?([0-9]{4})\b""")
    private val banks = listOf(
        "HDFC" to Regex("""(?i)\bHDFC\b"""),
        "AXIS" to Regex("""(?i)\bAxis\b"""),
        "ICICI" to Regex("""(?i)\bICICI\b"""),
        "SBI" to Regex("""(?i)\bSBI\b"""),
        "INDUSIND" to Regex("""(?i)\bIndusInd\b"""),
        "KOTAK" to Regex("""(?i)\bKotak\b""")
    )

    fun parse(messageBody: String): CashbackSms? {
        val text = messageBody.replace(" ", " ").trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (!lower.contains("cashback") || !Regex("""\bcredit\s+card\b""").containsMatchIn(lower)) return null
        // Offers ("get 5% cashback") and promises ("will be credited") are not credits yet.
        if (!creditedRegex.containsMatchIn(text)) return null

        // The first amount is the total; a break-up with smaller amounts may follow.
        val amount = amountRegex.find(text)?.groupValues?.get(1) ?: return null
        val amountPaise = runCatching {
            BigDecimal(amount.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull() ?: return null
        if (amountPaise <= 0L) return null

        val bank = banks.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        val lastFour = lastFourRegex.find(text)?.groupValues?.getOrNull(1)
        return CashbackSms(amountPaise, bank, lastFour)
    }
}
