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
                return result
            }
        }

        return ParserResult(isTransaction = false)
    }
}
