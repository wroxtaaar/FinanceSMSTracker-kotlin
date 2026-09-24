package com.example.financesmstracker.parser

import java.util.regex.Pattern

object AmountParser {
    private val PATTERNS = listOf(
        // e.g. Rs. 1,234.50, Rs 500, INR 500, ₹ 500, ₹1,234.50, INR 11100.00, Rs.1000.00, SGD 1.38
        Pattern.compile("(?:Rs\\.?|INR|₹|SGD|USD|EUR|GBP|AUD|CAD)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE),
        // e.g. 500 INR, 500/-, 1.38 SGD
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:INR|SGD|USD|EUR|GBP|AUD|CAD|/-)", Pattern.CASE_INSENSITIVE)
    )

    fun parseCurrency(text: String): String {
        val upper = text.uppercase()
        return when {
            upper.contains("SGD") -> "SGD"
            upper.contains("USD") -> "USD"
            upper.contains("EUR") -> "EUR"
            upper.contains("GBP") -> "GBP"
            upper.contains("AUD") -> "AUD"
            upper.contains("CAD") -> "CAD"
            else -> "INR"
        }
    }

    fun parseAmountToPaise(text: String): Long? {
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
