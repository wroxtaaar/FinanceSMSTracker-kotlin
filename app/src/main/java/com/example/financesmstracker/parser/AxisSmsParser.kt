package com.example.financesmstracker.parser

import java.util.regex.Pattern

class AxisSmsParser : SmsParser {
    override fun parse(sender: String, messageBody: String): ParserResult? {
        if (!sender.contains("AXIS", ignoreCase = true)) {
            return null
        }

        val lowerBody = messageBody.lowercase()
        if (lowerBody.contains("otp") || lowerBody.contains("one time password") || 
            lowerBody.contains("verification code") || lowerBody.contains("promotional") ||
            lowerBody.contains("offer")) {
            if (!lowerBody.contains("debited") && !lowerBody.contains("credited")) {
                return ParserResult(isTransaction = false)
            }
        }

        val amountPaise = AmountParser.parseAmountToPaise(messageBody) ?: return null

        val isCredit = lowerBody.contains("credited") || lowerBody.contains("received") || lowerBody.contains("added") || lowerBody.contains("refund")
        val isDebit = lowerBody.contains("debited") || lowerBody.contains("deducted") || lowerBody.contains("spent") || lowerBody.contains("paid") || lowerBody.contains("charged")

        val transactionType = when {
            isCredit && !isDebit -> TransactionType.CREDIT
            isDebit || lowerBody.contains("spent") || lowerBody.contains("paid") -> TransactionType.DEBIT
            else -> TransactionType.UNKNOWN
        }

        val paymentMethod = when {
            lowerBody.contains("upi") || messageBody.contains("@") -> PaymentMethod.UPI
            lowerBody.contains("atm") || lowerBody.contains("wdl") -> PaymentMethod.ATM
            lowerBody.contains("neft") -> PaymentMethod.NEFT
            lowerBody.contains("imps") -> PaymentMethod.IMPS
            lowerBody.contains("rtgs") -> PaymentMethod.RTGS
            lowerBody.contains("card") || lowerBody.contains("pos") || lowerBody.contains("ecom") -> PaymentMethod.CARD
            else -> PaymentMethod.UNKNOWN
        }

        val accountType = when {
            lowerBody.contains("card") -> AccountType.CREDIT_CARD
            lowerBody.contains("a/c") || lowerBody.contains("account") || paymentMethod == PaymentMethod.UPI -> AccountType.BANK_ACCOUNT
            else -> AccountType.UNKNOWN
        }

        val accMatcher = Pattern.compile("(?:a/c|account|ac)\\s*(?:no\\.)?\\s*(?:x+|XXXX|\\*+)?([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val accountLastFour = if (accMatcher.find()) accMatcher.group(1) else null

        val (merchantName, payeeId) = MerchantParser.extractMerchantAndVpa(messageBody)

        val refMatcher = Pattern.compile("(?:ref|utr)\\.?\\s*:?\\s*([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val refNumber = if (refMatcher.find()) refMatcher.group(1) else null

        return ParserResult(
            isTransaction = true,
            amountPaise = amountPaise,
            transactionType = transactionType,
            paymentMethod = paymentMethod,
            accountType = accountType,
            bank = "AXIS",
            merchantName = merchantName,
            payeeId = payeeId,
            accountLastFour = accountLastFour,
            refNumber = refNumber,
            confidence = 0.95f
        )
    }
}
