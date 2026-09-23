package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod

object TransactionCategorizer {
    fun categorize(parserResult: ParserResult, messageBody: String, rememberedCategory: String? = null): String {
        if (!rememberedCategory.isNullOrBlank()) {
            return rememberedCategory
        }

        val lowerBody = messageBody.lowercase()
        val merchant = (parserResult.merchantName ?: "").lowercase()
        val payee = (parserResult.payeeId ?: "").lowercase()
        val combined = "$lowerBody $merchant $payee"

        return when {
            combined.contains("salary") || combined.contains("payroll") || 
            combined.contains("stipend") -> "SALARY"

            combined.contains("refund") || combined.contains("reversal") || 
            combined.contains("reversed") -> "REFUND"

            combined.contains("atm") || combined.contains("cash withdrawal") || 
            parserResult.paymentMethod == PaymentMethod.ATM -> "ATM"

            combined.contains("netflix") || combined.contains("spotify") || 
            combined.contains("prime") || combined.contains("subscription") || 
            combined.contains("hotstar") || combined.contains("google play") || 
            combined.contains("apple") -> "SUBSCRIPTION"

            combined.contains("electricity") || combined.contains("broadband") || 
            combined.contains("recharge") || combined.contains("bill") || 
            combined.contains("utility") || combined.contains("water") || 
            combined.contains("gas bill") -> "BILLS"

            combined.contains("iocl") || combined.contains("bpcl") || 
            combined.contains("hpcl") || combined.contains("petrol") || 
            combined.contains("fuel") || combined.contains("diesel") || 
            combined.contains("gas station") -> "FUEL"

            combined.contains("uber") || combined.contains("ola") || 
            combined.contains("irctc") || combined.contains("airline") || 
            combined.contains("flight") || combined.contains("train") || 
            combined.contains("metro") || combined.contains("cab") -> "TRAVEL"

            combined.contains("swiggy") || combined.contains("zomato") || 
            combined.contains("restaurant") || combined.contains("cafe") || 
            combined.contains("food") || combined.contains("dining") -> "FOOD"

            combined.contains("amazon") || combined.contains("flipkart") || 
            combined.contains("myntra") || combined.contains("shopping") || 
            combined.contains("store") || combined.contains("mall") -> "SHOPPING"

            combined.contains("neft") || combined.contains("imps") || 
            combined.contains("rtgs") || combined.contains("bank transfer") || 
            combined.contains("fund transfer") || parserResult.paymentMethod == PaymentMethod.NEFT || 
            parserResult.paymentMethod == PaymentMethod.IMPS || 
            parserResult.paymentMethod == PaymentMethod.RTGS || 
            parserResult.paymentMethod == PaymentMethod.BANK_TRANSFER -> "TRANSFER"

            parserResult.paymentMethod == PaymentMethod.UPI -> "GROCERIES"

            else -> "OTHER"
        }
    }
}
