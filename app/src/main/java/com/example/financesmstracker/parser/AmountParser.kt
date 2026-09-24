package com.example.financesmstracker.parser

import java.util.regex.Pattern

object AmountParser {
    private val FOREIGN_CURRENCIES = listOf("sgd", "usd", "eur", "gbp", "aud", "cad", "jpy")

    private val PATTERNS = listOf(
        // e.g. Rs. 1,234.50, Rs 500, INR 500, ₹ 500, ₹1,234.50, INR 11100.00, Rs.1000.00
        Pattern.compile("(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE),
        // e.g. 500 INR, 500/-
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:INR|/-)", Pattern.CASE_INSENSITIVE)
    )

    fun parseAmountToPaise(text: String): Long? {
        val lowerText = text.lowercase()
        for (fc in FOREIGN_CURRENCIES) {
            if (lowerText.contains(fc) && !lowerText.contains("inr") && !lowerText.contains("rs") && !lowerText.contains("₹")) {
                return null
            }
        }

        for (pattern in PATTERNS) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val start = matcher.start()
                val raw = matcher.group(1) ?: continue
                
                val prefix = text.substring(maxOf(0, start - 35), start).lowercase()
                if (prefix.contains("avl limit") || prefix.contains("available limit") || 
                    prefix.contains("avbl bal") || prefix.contains("avl bal") || 
                    prefix.contains("available balance") || prefix.contains("balance") || 
                    prefix.contains("limit") || prefix.contains("bal")) {
                    continue
                }

                val cleaned = raw.replace(",", "")
                try {
                    val d = cleaned.toDouble()
                    return Math.round(d * 100)
                } catch (_: Exception) {}
            }
        }
        
        // Fallback: search for numbers near financial keywords
        val keywordPattern = Pattern.compile("(?:debited|credited|deducted|charged|sent|received|added|paid|spent|sum|amt[:\\s]*)[^0-9]*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
        val matcher = keywordPattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val raw = matcher.group(1) ?: continue
            val prefix = text.substring(maxOf(0, start - 35), start).lowercase()
            if (prefix.contains("avl limit") || prefix.contains("available limit") || 
                prefix.contains("avbl bal") || prefix.contains("avl bal") || 
                prefix.contains("available balance") || prefix.contains("balance") || 
                prefix.contains("limit") || prefix.contains("bal")) {
                continue
            }
            val cleaned = raw.replace(",", "")
            try {
                val d = cleaned.toDouble()
                return Math.round(d * 100)
            } catch (_: Exception) {}
        }

        return null
    }
}
