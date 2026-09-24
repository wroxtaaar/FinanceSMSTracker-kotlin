package com.example.financesmstracker.parser

enum class SenderTrustStatus {
    TRUSTED,
    UNTRUSTED,
    UNKNOWN
}

object SenderTrustManager {
    // Authorized bank DLT headers / sender strings (e.g. VM-HDFCBK-S, AD-AXISBK-S, HDFC, AXIS)
    private val TRUSTED_SENDERS = listOf("HDFC", "AXIS", "ICICI", "SBI", "KOTAK", "PAYTM", "PHONEPE", "HDFCBK", "AXISBK", "ICICIB", "SBICARD")

    fun classifySender(sender: String): SenderTrustStatus {
        if (sender.isBlank() || sender.equals("UNKNOWN", ignoreCase = true)) {
            return SenderTrustStatus.UNKNOWN
        }
        val upperSender = sender.uppercase()
        
        // Strict sender/header inspection (isolated from message body)
        for (trusted in TRUSTED_SENDERS) {
            if (upperSender.contains(trusted)) {
                return SenderTrustStatus.TRUSTED
            }
        }

        // Standard DLT header pattern
        if (upperSender.matches(Regex("^[A-Z]{2}-[A-Z0-9]+(-[A-Z0-9]+)?$"))) {
            return SenderTrustStatus.UNKNOWN
        }

        return SenderTrustStatus.UNTRUSTED
    }

    fun isFinancialLooking(messageBody: String): Boolean {
        val lower = messageBody.lowercase()
        val hasKeyword = lower.contains("debited") || lower.contains("credited") || 
                         lower.contains("spent") || lower.contains("paid") || 
                         lower.contains("received") || lower.contains("transfer") || 
                         lower.contains("a/c") || lower.contains("account") || lower.contains("card")
        val hasAmount = lower.contains("rs") || lower.contains("inr") || lower.contains("₹") || lower.contains("sgd") || lower.contains("usd")
        val isOtpOrPromo = lower.contains("otp") || lower.contains("one time password") || 
                           lower.contains("verification code") || lower.contains("promotional") || 
                           lower.contains("offer") || lower.contains("discount") || lower.contains("win") ||
                           lower.contains("loan") || lower.contains("pre-approved") || lower.contains("credit card limit") ||
                           lower.contains("failed") || lower.contains("pending") || lower.contains("mandate")

        return hasKeyword && hasAmount && !isOtpOrPromo
    }
}
