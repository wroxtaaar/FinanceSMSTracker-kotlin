package com.example.financesmstracker

import com.example.financesmstracker.util.IndianMoney
import java.util.Locale

internal fun displayAccountName(rawName: String): String {
    val withoutDigits = rawName.replace(Regex("""\s*\.{3}\s*\d{4}\s*$"""), "").trim()
    return withoutDigits.replace(
        Regex("""\b(icici|sbi|hdfc)\b""", RegexOption.IGNORE_CASE)
    ) { it.value.uppercase() }
}

/** Plain "1234.50" for text inputs (no grouping, so it can be edited and parsed back). */
internal fun formatDecimalMinor(minor: Long): String =
    String.format(Locale.US, "%.2f", minor / 100.0)

/** Display amount in Indian grouping: ₹1,52,922.05. */
internal fun formatMinor(minor: Long): String = IndianMoney.format(minor)
