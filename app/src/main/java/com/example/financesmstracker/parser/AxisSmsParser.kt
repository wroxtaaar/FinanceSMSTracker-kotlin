package com.example.financesmstracker.parser

import com.example.financesmstracker.util.TransactionReferenceExtractor
import java.util.regex.Pattern

class AxisSmsParser : SmsParser {
    override fun parse(sender: String, messageBody: String): ParserResult? {
        if (!sender.contains("AXIS", ignoreCase = true)) {
            return null
        }

        if (SenderTrustManager.isNonTransactionalFinancialMessage(messageBody)) {
            return ParserResult(isTransaction = false)
        }

        val lowerBody = messageBody.lowercase()

        val currency = AmountParser.parseCurrency(messageBody)
        val amountPaise = AmountParser.parseAmountToPaise(messageBody) ?: 0L

        val isCredit = lowerBody.contains("credited") || lowerBody.contains("received") || lowerBody.contains("added") || lowerBody.contains("refund")
        val isDebit = lowerBody.contains("debited") || lowerBody.contains("deducted") || lowerBody.contains("spent") || lowerBody.contains("paid") || lowerBody.contains("charged") || lowerBody.contains("transferred") || lowerBody.contains("transfer")

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
            else -> PaymentMethod.UNKNOWN
        }

        val accountType = when {
            lowerBody.contains("card") -> AccountType.CREDIT_CARD
            lowerBody.contains("a/c") || lowerBody.contains("account") || paymentMethod == PaymentMethod.UPI -> AccountType.BANK_ACCOUNT
            else -> AccountType.UNKNOWN
        }

        val accMatcher = Pattern.compile("(?:a/c|account|ac|card)\\s*(?:no\\.)?\\s*(?:[xX]+|XXXX|\\*+)?([0-9]{4})", Pattern.CASE_INSENSITIVE).matcher(messageBody)
        val accountLastFour = if (accMatcher.find()) accMatcher.group(1) else null

        val (merchantName, payeeId) = MerchantParser.extractMerchantAndVpa(messageBody)

        // Reference/RRN is the stable identity. Accept every common Axis
        // representation and normalize it to the same value used by email and
        // notification sources.
        val refNumber = TransactionReferenceExtractor.extract(messageBody)

        return ParserResult(
            isTransaction = true,
            amountPaise = amountPaise,
            currency = currency,
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
