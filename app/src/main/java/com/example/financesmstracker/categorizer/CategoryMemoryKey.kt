package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.ParserResult

object CategoryMemoryKey {

    fun from(parserResult: ParserResult): String? {
        val payeeId = parserResult.payeeId
            ?.trim()
            ?.lowercase()

        // VPA is the most specific and stable payee identity.
        if (!payeeId.isNullOrBlank()) {
            return "VPA|$payeeId"
        }

        // Without a VPA the payee's name is the stable identity ("One Mobikwik
        // Systems Limi"). There is deliberately no account-wide fallback: a key
        // for "HDFC account 9591" would re-categorise every payment from it.
        val name = parserResult.merchantName
            ?.trim()
            ?.lowercase()

        return if (name.isNullOrBlank()) null else "PAYEE|$name"
    }
}
