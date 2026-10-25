package com.example.financesmstracker

import java.util.Locale

internal fun displayAccountName(rawName: String): String {
    val withoutDigits = rawName.replace(Regex("""\s*\.{3}\s*\d{4}\s*$"""), "").trim()
    return withoutDigits.replace(
        Regex("""\b(icici|sbi|hdfc|hsbc)\b""", RegexOption.IGNORE_CASE)
    ) { it.value.uppercase() }
}

internal fun formatDecimalMinor(minor: Long): String =
    String.format(Locale.getDefault(), "%.2f", minor / 100.0)

internal fun formatMinor(minor: Long): String {
    return String.format(Locale.getDefault(), "₹%,.2f", minor / 100.0)
}
