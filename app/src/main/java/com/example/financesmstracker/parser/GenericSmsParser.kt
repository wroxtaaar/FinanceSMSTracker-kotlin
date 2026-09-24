package com.example.financesmstracker.parser

import java.util.regex.Pattern

class GenericSmsParser : SmsParser {
    override fun parse(sender: String, messageBody: String): ParserResult? {
        val lowerBody = messageBody.lowercase()

        if (lowerBody.contains("otp") || lowerBody.contains("one time password") || 
            lowerBody.contains("verification code") || lowerBody.contains("promotional") ||
            lowerBody.contains("offer") || lowerBody.contains("discount") ||
            lowerBody.contains("win") || lowerBody.contains("cashback offer")) {
            if (!lowerBody.contains("debited") && !lowerBody.contains("credited") && !lowerBody.contains("sent") && !lowerBody.contains("received")) {
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
                      lowerBody.contains("dr")

        if (!isCredit && !isDebit) {
            return null
        }

        val transactionType = when {
            isCredit && !isDebit -> TransactionType.CREDIT
            isDebit || isCredit -> TransactionType.DEBIT
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
            lowerBody.contains("card") -> AccountType.CREDIT_CARD
            lowerBody.contains("a/c") || lowerBody.contains("account") || paymentMethod == PaymentMethod.UPI -> AccountType.BANK_ACCOUNT
            else -> AccountType.UNKNOWN
        }

        val accMatcher = Pattern.compile("(?:a/c|account|ac|card)\\s*(?:no\\.)?\\s*(?:x+|XXXX|\\*+)?([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val accountLastFour = if (accMatcher.find()) accMatcher.group(1) else null

        val (merchantName, payeeId) = MerchantParser.extractMerchantAndVpa(messageBody)

        val refMatcher = Pattern.compile("(?:ref|utr)\\.?\\s*:?\\s*([0-9a-zA-Z]+)", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val refNumber = if (refMatcher.find()) refMatcher.group(1) else null

        return ParserResult(
            isTransaction = true,
            amountPaise = amountPaise,
            currency = currency,
            transactionType = transactionType,
            paymentMethod = paymentMethod,
            accountType = accountType,
            bank = sender,
            merchantName = merchantName,
            payeeId = payeeId,
            accountLastFour = accountLastFour,
            refNumber = refNumber,
            confidence = 0.80f
        )
    }
}
