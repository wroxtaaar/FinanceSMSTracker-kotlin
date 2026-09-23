package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.AccountType
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

        val bank = parserResult.bank
            ?.trim()
            ?.lowercase()

        val lastFour = parserResult.accountLastFour
            ?.trim()

        // Account/card memory requires both bank and last 4 digits.
        if (bank.isNullOrBlank() || lastFour.isNullOrBlank()) {
            return null
        }

        val accountType = when (parserResult.accountType) {
            AccountType.CREDIT_CARD -> "CREDIT_CARD"
            AccountType.BANK_ACCOUNT -> "BANK_ACCOUNT"
            AccountType.UNKNOWN -> return null
        }

        return "BANK|$bank|$accountType|$lastFour"
    }
}
