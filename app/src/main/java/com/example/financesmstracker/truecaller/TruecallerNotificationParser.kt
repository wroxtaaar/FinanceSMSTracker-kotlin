package com.example.financesmstracker.truecaller

import com.example.financesmstracker.parser.AmountParser
import java.util.regex.Pattern

object TruecallerNotificationParser {

    fun parse(title: String?, text: String?, timestamp: Long, contentHash: String): ParsedNotification {
        val combined = "${title.orEmpty()} \n ${text.orEmpty()}"
        val lowerCombined = combined.lowercase()

        // Check non-financial rejection
        if (!lowerCombined.contains("rs") && !lowerCombined.contains("inr") && !lowerCombined.contains("₹") &&
            !lowerCombined.contains("credit") && !lowerCombined.contains("debit") && !lowerCombined.contains("spent") &&
            !lowerCombined.contains("credited") && !lowerCombined.contains("debited")) {
            return ParsedNotification(isNotificationTransaction = false, timestamp = timestamp, contentHash = contentHash)
        }

        val amountPaise = AmountParser.parseAmountToPaise(combined)
        if (amountPaise == null || amountPaise <= 0) {
            return ParsedNotification(isNotificationTransaction = false, timestamp = timestamp, contentHash = contentHash)
        }

        val isCredit = lowerCombined.contains("credit") || lowerCombined.contains("credited") || 
                       lowerCombined.contains("received") || lowerCombined.contains("added") || 
                       combined.contains("+")
        val isDebit = lowerCombined.contains("debit") || lowerCombined.contains("debited") || 
                      lowerCombined.contains("spent") || lowerCombined.contains("paid") || 
                      lowerCombined.contains("charged") || combined.contains("−") || combined.contains("-")

        val direction = when {
            isCredit && !isDebit -> NotificationDirection.CREDIT
            isDebit || isCredit -> NotificationDirection.DEBIT
            else -> NotificationDirection.UNKNOWN
        }

        // Extract bank/provider name if present
        val bankProvider = extractBankProvider(combined)

        return ParsedNotification(
            isNotificationTransaction = true,
            amountPaise = amountPaise,
            direction = direction,
            bankProvider = bankProvider,
            timestamp = timestamp,
            contentHash = contentHash
        )
    }

    private fun extractBankProvider(text: String): String? {
        val banks = listOf("HDFC Bank", "Axis Bank", "ICICI Bank", "SBI", "Kotak", "Paytm", "Google Pay", "PhonePe")
        for (bank in banks) {
            if (text.contains(bank, ignoreCase = true)) {
                return bank
            }
        }
        val matcher = Pattern.compile("(?:to|from|at|in|credited\\s+to|debited\\s+from)\\s+([A-Za-z]+\\s+Bank)", Pattern.CASE_INSENSITIVE).matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        return null
    }
}
