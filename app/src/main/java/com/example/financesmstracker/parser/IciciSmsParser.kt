package com.example.financesmstracker.parser

import com.example.financesmstracker.util.TransactionReferenceExtractor
import java.util.regex.Pattern

/**
 * ICICI Bank card alerts, e.g.
 * "INR 1,854.00 spent using ICICI Bank Card XX1012 on 29-Oct-26 on AMAZON PAY IN E.
 *  Avl Limit: INR 3,47,728.19. If not you, call 1800 2662/SMS BLOCK 1012 to 9215676766."
 *
 * Only the card-spend and card-credit layouts are handled here; every other
 * ICICI message keeps falling through to the generic parser.
 */
class IciciSmsParser : SmsParser {
    private val cardMessage = Pattern.compile(
        "ICICI\\s+Bank\\s+(?:Credit\\s+)?Card\\s+[Xx*]*\\s*([0-9]{4})",
        Pattern.CASE_INSENSITIVE
    )
    private val spent = Pattern.compile("\\bspent\\s+using\\b", Pattern.CASE_INSENSITIVE)
    private val credited = Pattern.compile("\\bcredited\\b|\\brefund(?:ed)?\\b|\\breversal\\b", Pattern.CASE_INSENSITIVE)
    private val merchantAfterDate = Pattern.compile(
        "\\bon\\s+[0-9]{1,2}-[A-Za-z]{3}-[0-9]{2,4}\\s+on\\s+(.+?)\\s*(?:\\.\\s*(?:Avl|Avbl|Available|If\\s+not)\\b|\\.?\\s*$)",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    override fun parse(sender: String, messageBody: String): ParserResult? {
        val card = cardMessage.matcher(messageBody)
        if (!card.find()) return null
        if (SenderTrustManager.isNonTransactionalFinancialMessage(messageBody)) {
            return ParserResult(isTransaction = false)
        }

        val isSpend = spent.matcher(messageBody).find()
        val isCredit = !isSpend && credited.matcher(messageBody).find()
        if (!isSpend && !isCredit) return null

        val amountPaise = AmountParser.parseAmountToPaise(messageBody) ?: 0L
        val merchant = merchantAfterDate.matcher(messageBody).let { m ->
            if (m.find()) m.group(1)?.trim()?.trimEnd('.', ',')?.trim()?.takeIf { it.isNotBlank() } else null
        }

        return ParserResult(
            isTransaction = true,
            amountPaise = amountPaise,
            currency = AmountParser.parseCurrency(messageBody),
            transactionType = if (isSpend) TransactionType.DEBIT else TransactionType.CREDIT,
            paymentMethod = PaymentMethod.CARD,
            accountType = AccountType.CREDIT_CARD,
            bank = "ICICI",
            merchantName = merchant,
            accountLastFour = card.group(1),
            refNumber = TransactionReferenceExtractor.extract(messageBody),
            confidence = 0.95f
        )
    }
}
