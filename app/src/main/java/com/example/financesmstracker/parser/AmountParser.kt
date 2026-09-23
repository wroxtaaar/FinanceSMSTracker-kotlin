package com.example.financesmstracker.parser

import java.util.regex.Pattern

object AmountParser {
    private val PATTERNS = listOf(
        // e.g. Rs. 1,234.50, Rs 500, INR 500, ₹ 500, ₹1,234.50
        Pattern.compile("(?:Rs\\.?|INR|₹)\\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE),
        // e.g. 500 INR, 500/-
        Pattern.compile("([0-9]{1,3}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:INR|/-)", Pattern.CASE_INSENSITIVE)
    )

    fun parseAmountToPaise(text: String): Long? {
        for (pattern in PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val cleaned = raw.replace(",", "")
                try {
                    val d = cleaned.toDouble()
                    return Math.round(d * 100)
                } catch (_: Exception) {}
            }
        }
        
        // Fallback: search for numbers near financial keywords
        val keywordPattern = Pattern.compile("(?:debited|credited|deducted|charged|sent|received|added|paid|sum|amt[:\\s]*)[^0-9]*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
        val matcher = keywordPattern.matcher(text)
        if (matcher.find()) {
            val raw = matcher.group(1) ?: return null
            val cleaned = raw.replace(",", "")
            try {
                val d = cleaned.toDouble()
                return Math.round(d * 100)
            } catch (_: Exception) {}
        }

        return null
    }
}
