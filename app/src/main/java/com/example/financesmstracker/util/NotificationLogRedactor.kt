package com.example.financesmstracker.util

/**
 * Keeps notification text useful for debugging without writing identifying
 * detail to logcat: long digit runs (references, phone numbers, card numbers)
 * are masked and the text is truncated. Amounts such as "INR 1820.00" and short
 * last-four fragments are left readable.
 */
object NotificationLogRedactor {
    private const val MAX_LENGTH = 90
    private val longDigits = Regex("""\d{6,}""")

    fun redact(value: CharSequence?): String {
        if (value.isNullOrBlank()) return "-"
        val masked = longDigits.replace(value.toString().replace('\n', ' ').trim()) { match ->
            "#".repeat(match.value.length)
        }
        return if (masked.length <= MAX_LENGTH) masked else masked.take(MAX_LENGTH) + "…"
    }
}
