package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryMemoryKeyTest {

    private fun result(
        bank: String? = null,
        payeeId: String? = null,
        lastFour: String? = null,
        accountType: AccountType = AccountType.UNKNOWN,
        merchant: String? = null
    ): ParserResult {
        return ParserResult(
            isTransaction = true,
            amountPaise = 10000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UNKNOWN,
            accountType = accountType,
            bank = bank,
            merchantName = merchant,
            payeeId = payeeId,
            accountLastFour = lastFour,
            refNumber = null,
            confidence = 1.0f
        )
    }

    @Test
    fun `vpa creates stable vpa key`() {
        val parserResult = result(
            payeeId = " Swiggy@UPI "
        )

        assertEquals(
            "VPA|swiggy@upi",
            CategoryMemoryKey.from(parserResult)
        )
    }

    @Test
    fun `payee name without a vpa creates a payee key`() {
        val key = CategoryMemoryKey.from(
            result(bank = "HDFC", lastFour = "9591", accountType = AccountType.BANK_ACCOUNT, merchant = "  One Mobikwik Systems Limi ")
        )
        assertEquals("PAYEE|one mobikwik systems limi", key)
    }

    @Test
    fun `an account alone never creates a memory key`() {
        // An account-wide key would re-categorise every payment from that account.
        assertNull(CategoryMemoryKey.from(result(bank = "HDFC", lastFour = "1234", accountType = AccountType.BANK_ACCOUNT)))
        assertNull(CategoryMemoryKey.from(result(bank = "HDFC", lastFour = "1234", accountType = AccountType.CREDIT_CARD)))
    }

    @Test
    fun `a vpa wins over the payee name`() {
        assertEquals("VPA|swiggy@upi", CategoryMemoryKey.from(result(payeeId = "Swiggy@UPI", merchant = "Swiggy")))
    }
}
