package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionCategorizerHealthcareTest {
    private fun categorize(merchant: String): String =
        TransactionCategorizer.categorize(
            ParserResult(
                isTransaction = true, amountPaise = 150_000L, transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UPI, accountType = AccountType.BANK_ACCOUNT, bank = "AXIS",
                merchantName = merchant, payeeId = null, accountLastFour = "3370", refNumber = null,
                confidence = 1f
            ),
            messageBody = "Rs 1500 debited via UPI"
        )

    @Test
    fun healthcareBrandsAreMedical() {
        assertEquals("MEDICAL", categorize("Maxhealthcare"))
        assertEquals("MEDICAL", categorize("Apollo Health"))
    }

    @Test
    fun unrelatedMerchantsAreUnaffected() {
        assertEquals("GROCERIES", categorize("Abdul Gaffa"))
    }
}
