package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType

object TransactionCategorizer {

    fun categorize(
        parserResult: ParserResult,
        messageBody: String,
        rememberedCategory: String? = null
    ): String {

        val lowerBody = messageBody.lowercase()
        val merchant = (parserResult.merchantName ?: "").lowercase()
        val payee = (parserResult.payeeId ?: "").lowercase()
        val combined = "$lowerBody $merchant $payee"

        // Keywords must match whole words: plain substring checks made
        // "ola" match "Kolar", "cab" match "cabinet", etc., so unrelated
        // merchants (e.g. a medical store) ended up as TRAVEL.
        fun has(keyword: String): Boolean =
            Regex("(?<![a-z0-9])" + Regex.escape(keyword) + "s?(?![a-z0-9])").containsMatchIn(combined)

        // Word-start match for stems such as "pharm" (pharmacy, pharma, pharme).
        fun hasStem(stem: String): Boolean =
            Regex("(?<![a-z0-9])" + Regex.escape(stem)).containsMatchIn(combined)

        /*
         * CREDIT transactions have a deliberately restricted
         * category set:
         *
         * REFUND
         * SALARY
         * TRANSFER
         *
         * A remembered debit category must never override this.
         */
        if (parserResult.transactionType == TransactionType.CREDIT) {

            return when {
                has("refund") ||
                has("reversal") ||
                has("reversed") -> "REFUND"

                has("salary") ||
                has("payroll") ||
                has("stipend") -> "SALARY"

                else -> "TRANSFER"
            }
        }

        /*
         * Remembered categories are allowed for DEBIT transactions.
         */
        if (!rememberedCategory.isNullOrBlank()) {
            return rememberedCategory
        }

        return when {
            has("salary") ||
            has("payroll") ||
            has("stipend") -> "SALARY"

            has("refund") ||
            has("reversal") ||
            has("reversed") -> "REFUND"

            has("atm") ||
            has("cash withdrawal") ||
            parserResult.paymentMethod == PaymentMethod.ATM -> "ATM"

            hasStem("medic") ||
            hasStem("pharm") ||
            has("chemist") ||
            has("hospital") ||
            has("clinic") ||
            has("diagnostic") ||
            has("dental") ||
            has("dentist") ||
            has("doctor") -> "MEDICAL"

            has("netflix") ||
            has("spotify") ||
            has("prime") ||
            has("subscription") ||
            has("hotstar") ||
            has("google play") ||
            has("apple") -> "SUBSCRIPTION"

            has("electricity") ||
            has("broadband") ||
            has("recharge") ||
            has("bill") ||
            has("utility") ||
            has("water") ||
            has("gas bill") -> "BILLS"

            has("iocl") ||
            has("bpcl") ||
            has("hpcl") ||
            has("petrol") ||
            has("fuel") ||
            has("diesel") ||
            has("gas station") -> "FUEL"

            has("uber") ||
            has("ola") ||
            has("irctc") ||
            has("airline") ||
            has("flight") ||
            has("train") ||
            has("metro") ||
            has("cab") -> "TRAVEL"

            has("swiggy") ||
            has("zomato") ||
            has("restaurant") ||
            has("cafe") ||
            has("food") ||
            has("dining") -> "FOOD"

            has("amazon") ||
            has("flipkart") ||
            has("myntra") ||
            has("shopping") ||
            has("store") ||
            has("mall") -> "SHOPPING"

            has("neft") ||
            has("imps") ||
            has("rtgs") ||
            has("bank transfer") ||
            has("fund transfer") ||
            parserResult.paymentMethod == PaymentMethod.NEFT ||
            parserResult.paymentMethod == PaymentMethod.IMPS ||
            parserResult.paymentMethod == PaymentMethod.RTGS ||
            parserResult.paymentMethod == PaymentMethod.BANK_TRANSFER -> "TRANSFER"

            parserResult.paymentMethod == PaymentMethod.UPI -> "GROCERIES"

            // Unclassified debits default to GROCERIES. OTHER remains an explicit
            // user choice for transactions that should not contribute to Splitwise.
            else -> "GROCERIES"
        }
    }
}
