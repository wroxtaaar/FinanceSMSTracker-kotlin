package com.example.financesmstracker.util

/** Parses an amount typed by the user into minor units (paise), or null if it is not a number. */
object MoneyInput {
    fun parseMinor(text: String, allowNegative: Boolean = false): Long? {
        val value = text.trim().replace(",", "").toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        if (value < 0 && !allowNegative) return null
        return kotlin.math.round(value * 100.0).toLong()
    }
}
