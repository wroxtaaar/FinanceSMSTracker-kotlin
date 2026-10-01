package com.example.financesmstracker.parser

class SmsParserManager {
    private val parsers = listOf(
        HdfcSmsParser(),
        AxisSmsParser(),
        GenericSmsParser()
    )

    fun parse(sender: String, messageBody: String): ParserResult {
        // Hard rejection happens before bank-specific parsing. This is the key
        // safety boundary: a trusted bank sender must not override an OTP,
        // promotion, future/failed payment, or informational message.
        if (SenderTrustManager.isNonTransactionalFinancialMessage(messageBody)) {
            return ParserResult(isTransaction = false)
        }

        for (parser in parsers) {
            val result = parser.parse(sender, messageBody)
            if (result != null) {
                // A parser must never turn malformed text into a ledger transaction.
                // Amount extraction is mandatory for every canonical transaction.
                if (!result.isTransaction || result.amountPaise <= 0L) {
                    return ParserResult(isTransaction = false)
                }
                return result
            }
        }

        return ParserResult(isTransaction = false)
    }
}
