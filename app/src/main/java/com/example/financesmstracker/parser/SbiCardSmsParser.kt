package com.example.financesmstracker.parser

import com.example.financesmstracker.util.TransactionReferenceExtractor
import java.util.regex.Pattern

/**
 * SBI Card alerts, e.g.
 * "Rs.2,500.00 spent on your SBI Credit Card ending 0065 at RAJDHANISERVICESTATIO
 *  on 31/10/26. Trxn. not done by you? Report at https://sbicard.com/Dispute"
 *
 * Only credit-card spend and credit layouts are handled; other SBI messages
 * fall through to the generic parser.
 */
class SbiCardSmsParser : SmsParser {
    private val card = Pattern.compile(
        "SBI\\s+(?:Credit\\s+)?Card\\s+ending\\s+(?:with\\s+)?[Xx*]*\\s*([0-9]{4})",
        Pattern.CASE_INSENSITIVE
    )
    private val spent = Pattern.compile("\\bspent\\s+on\\b", Pattern.CASE_INSENSITIVE)
    private val credited = Pattern.compile("\\bcredited\\b|\\brefund(?:ed)?\\b|\\breversal\\b", Pattern.CASE_INSENSITIVE)
    private val merchantAt = Pattern.compile(
        "\\bat\\s+(.+?)\\s+on\\s+[0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    override fun parse(sender: String, messageBody: String): ParserResult? {
        val cardMatch = card.matcher(messageBody)
        if (!cardMatch.find()) return null
        if (SenderTrustManager.isNonTransactionalFinancialMessage(messageBody)) {
            return ParserResult(isTransaction = false)
        }

        val isSpend = spent.matcher(messageBody).find()
        val isCredit = !isSpend && credited.matcher(messageBody).find()
        if (!isSpend && !isCredit) return null

        val merchant = merchantAt.matcher(messageBody).let { m ->
            if (m.find()) m.group(1)?.trim()?.trimEnd('.', ',')?.trim()?.takeIf { it.isNotBlank() } else null
        }

        return ParserResult(
            isTransaction = true,
            amountPaise = AmountParser.parseAmountToPaise(messageBody) ?: 0L,
            currency = AmountParser.parseCurrency(messageBody),
            transactionType = if (isSpend) TransactionType.DEBIT else TransactionType.CREDIT,
            paymentMethod = PaymentMethod.CARD,
            accountType = AccountType.CREDIT_CARD,
            bank = "SBI",
            merchantName = merchant,
            accountLastFour = cardMatch.group(1),
            refNumber = TransactionReferenceExtractor.extract(messageBody),
            confidence = 0.95f
        )
    }
}
