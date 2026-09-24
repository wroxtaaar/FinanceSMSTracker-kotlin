package com.example.financesmstracker.parser

import java.util.regex.Pattern

class HdfcSmsParser : SmsParser {
    override fun parse(sender: String, messageBody: String): ParserResult? {
        if (!sender.contains("HDFC", ignoreCase = true)) {
            return null
        }

        val lowerBody = messageBody.lowercase()
        if (lowerBody.contains("otp") || lowerBody.contains("one time password") || 
            lowerBody.contains("verification code") || lowerBody.contains("promotional") ||
            lowerBody.contains("offer") || lowerBody.contains("reward points") ||
            lowerBody.contains("congratulations") || lowerBody.contains("loan") ||
            lowerBody.contains("failed") || lowerBody.contains("pending") ||
            lowerBody.contains("scheduled") || lowerBody.contains("mandate") ||
            lowerBody.contains("bonus") || lowerBody.contains("off")) {
            if (!lowerBody.contains("debited") && !lowerBody.contains("credited") && !lowerBody.contains("spent") && !lowerBody.contains("received") && !lowerBody.contains("transferred")) {
                return ParserResult(isTransaction = false)
            }
        }

        val amountPaise = AmountParser.parseAmountToPaise(messageBody) ?: return null
        val currency = AmountParser.parseCurrency(messageBody)

        val isCredit = lowerBody.contains("credited") || lowerBody.contains("received") || 
                       lowerBody.contains("added") || lowerBody.contains("refund") || 
                       lowerBody.contains("reversal") || lowerBody.contains("cr")
        val isDebit = lowerBody.contains("debited") || lowerBody.contains("deducted") || 
                      lowerBody.contains("spent") || lowerBody.contains("paid") || 
                      lowerBody.contains("charged") || lowerBody.contains("sent") || 
                      lowerBody.contains("dr") || lowerBody.contains("used for") ||
                      lowerBody.contains("atm wdl") || lowerBody.contains("withdrawn") ||
                      lowerBody.contains("transferred") || lowerBody.contains("transfer")

        if (!isCredit && !isDebit) {
            return null
        }

        val transactionType = when {
            isCredit && !isDebit -> TransactionType.CREDIT
            isDebit -> TransactionType.DEBIT
            else -> TransactionType.UNKNOWN
        }

        val paymentMethod = when {
            lowerBody.contains("upi") || messageBody.contains("@") -> PaymentMethod.UPI
            lowerBody.contains("atm") || lowerBody.contains("wdl") -> PaymentMethod.ATM
            lowerBody.contains("neft") -> PaymentMethod.NEFT
            lowerBody.contains("imps") -> PaymentMethod.IMPS
            lowerBody.contains("rtgs") -> PaymentMethod.RTGS
            lowerBody.contains("card") || lowerBody.contains("pos") || lowerBody.contains("ecom") -> PaymentMethod.CARD
            lowerBody.contains("transfer") -> PaymentMethod.BANK_TRANSFER
            else -> PaymentMethod.UNKNOWN
        }

        val accountType = when {
            lowerBody.contains("card") || lowerBody.contains("credit card") -> AccountType.CREDIT_CARD
            lowerBody.contains("a/c") || lowerBody.contains("account") || lowerBody.contains("ac") || paymentMethod == PaymentMethod.UPI -> AccountType.BANK_ACCOUNT
            else -> AccountType.UNKNOWN
        }

        val cardEndingMatcher = Pattern.compile("card\\s+ending\\s+([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val accountLastFour = when {
            cardEndingMatcher.find() -> cardEndingMatcher.group(1)
            else -> {
                val accMatcher = Pattern.compile("(?:a/c|acct|account|card)\\s*(?:no\\.)?\\s*(?:\\*+|xx|XXXX)?([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(messageBody)
                if (accMatcher.find()) accMatcher.group(1) else null
            }
        }

        val (merchantName, payeeId) = MerchantParser.extractMerchantAndVpa(messageBody)

        val refMatcher = Pattern.compile("(?:ref|upi ref|imps ref|utr)\\.?\\s*:?\\s*([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val refNumber = if (refMatcher.find()) refMatcher.group(1) else null

        return ParserResult(
            isTransaction = true,
            amountPaise = amountPaise,
            currency = currency,
            transactionType = transactionType,
            paymentMethod = paymentMethod,
            accountType = accountType,
            bank = "HDFC",
            merchantName = merchantName,
            payeeId = payeeId,
            accountLastFour = accountLastFour,
            refNumber = refNumber,
            confidence = 0.95f
        )
    }
}
