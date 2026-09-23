package com.example.financesmstracker.parser

class SmsParserManager {
    private val parsers = listOf(
        HdfcSmsParser(),
        AxisSmsParser(),
        GenericSmsParser()
    )

    fun parse(sender: String, messageBody: String): ParserResult {
        for (parser in parsers) {
            val result = parser.parse(sender, messageBody)
            if (result != null) {
                return result
            }
        }
        return ParserResult(isTransaction = false)
    }
}
