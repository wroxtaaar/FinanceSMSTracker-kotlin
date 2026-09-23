package com.example.financesmstracker.parser

import java.util.regex.Pattern

object MerchantParser {
    private val INVALID_MERCHANT_PHRASES = setOf(
        "bank account",
        "your bank account",
        "my account",
        "account",
        "savings",
        "current",
        "self",
        "self account",
        "bank",
        "a/c",
        "ac",
        "your",
        "my",
        "the",
        "a",
        "an",
        "his",
        "her",
        "their",
        "atm",
        "branch",
        "https",
        "http"
    )

    private val TIME_PATTERN = Pattern.compile("^[0-9]{1,2}:[0-9]{2}$")
    private val DATE_PATTERN = Pattern.compile("^[0-9]{1,2}-[a-zA-Z]{3}-[0-9]{2,4}$")

    fun extractMerchantAndVpa(messageBody: String): Pair<String?, String?> {
        var merchant: String? = null
        var vpa: String? = null

        // 1. Check UPI path format: UPI/MerchantName/vpa@handle
        val upiPathMatcher = Pattern.compile("UPI/([^/]+)/([a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        if (upiPathMatcher.find()) {
            merchant = upiPathMatcher.group(1)?.trim()
            vpa = upiPathMatcher.group(2)?.trim()
        }

        // 2. Standalone VPA
        if (vpa == null) {
            val vpaMatcher = Pattern.compile("([a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
            while (vpaMatcher.find()) {
                val candidate = vpaMatcher.group(1) ?: continue
                val lower = candidate.lowercase()
                if (lower.endsWith(".com") || lower.endsWith(".org") || lower.endsWith(".net") || lower.endsWith(".in")) {
                    if (!lower.contains("upi") && !lower.contains("paytm") && !lower.contains("oksbi") && !lower.contains("okaxis")) {
                        continue
                    }
                }
                if (candidate.contains("@") && !candidate.startsWith("@") && !candidate.endsWith("@")) {
                    vpa = candidate
                    break
                }
            }
        }

        // 3. Merchant name after "to", "paid to", "sent to", "towards", "at"
        if (merchant == null) {
            val merchantMatcher = Pattern.compile("(?:to|paid\\s+to|sent\\s+to|towards|at)\\s+([a-zA-Z0-9\\s._-]+?)(?:\\s+(?:Ref|UPI|A/C|a/c|on|at|via|IMPS|NEFT|card|ending|\\*|\\d{2}-)|$)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
            if (merchantMatcher.find()) {
                val rawMerchant = merchantMatcher.group(1)?.trim()
                if (!rawMerchant.isNullOrBlank() && !rawMerchant.equals("UPI", ignoreCase = true)) {
                    if (isValidMerchant(rawMerchant)) {
                        merchant = rawMerchant
                    }
                }
            }
        }

        // 4. Fallback for raw identifiers like "82184053ptyes"
        if (merchant == null && vpa == null) {
            val fallbackMatcher = Pattern.compile("(?:to|paid\\s+to|sent\\s+to|at)\\s+([a-zA-Z0-9._-]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
            if (fallbackMatcher.find()) {
                val rawFallback = fallbackMatcher.group(1)?.trim()
                if (rawFallback != null && isValidMerchant(rawFallback)) {
                    merchant = rawFallback
                }
            }
        }

        return Pair(merchant, vpa)
    }

    private fun isValidMerchant(candidate: String): Boolean {
        val trimmed = candidate.trim()
        val lower = trimmed.lowercase()
        if (INVALID_MERCHANT_PHRASES.contains(lower)) {
            return false
        }
        if (TIME_PATTERN.matcher(trimmed).matches()) {
            return false
        }
        if (DATE_PATTERN.matcher(trimmed).matches()) {
            return false
        }
        if (trimmed.startsWith("http://", ignoreCase = true) || 
            trimmed.startsWith("https://", ignoreCase = true) || 
            lower.contains("://") || 
            lower.contains("www.") ||
            lower.startsWith("https", ignoreCase = true) ||
            lower.startsWith("http", ignoreCase = true)) {
            return false
        }
        if (trimmed.all { it.isDigit() || it.isWhitespace() }) {
            return false
        }
        return true
    }
}
