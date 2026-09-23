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
        accountType: AccountType = AccountType.UNKNOWN
    ): ParserResult {
        return ParserResult(
            isTransaction = true,
            amountPaise = 10000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UNKNOWN,
            accountType = accountType,
            bank = bank,
            merchantName = null,
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
    fun `bank account creates bank account key`() {
        val parserResult = result(
            bank = " HDFC ",
            lastFour = "1234",
            accountType = AccountType.BANK_ACCOUNT
        )

        assertEquals(
            "BANK|hdfc|BANK_ACCOUNT|1234",
            CategoryMemoryKey.from(parserResult)
        )
    }

    @Test
    fun `credit card creates credit card key`() {
        val parserResult = result(
            bank = " HDFC ",
            lastFour = "1234",
            accountType = AccountType.CREDIT_CARD
        )

        assertEquals(
            "BANK|hdfc|CREDIT_CARD|1234",
            CategoryMemoryKey.from(parserResult)
        )
    }

    @Test
    fun `different banks do not collide`() {
        val hdfc = result(
            bank = "HDFC",
            lastFour = "1234",
            accountType = AccountType.BANK_ACCOUNT
        )

        val axis = result(
            bank = "Axis",
            lastFour = "1234",
            accountType = AccountType.BANK_ACCOUNT
        )

        val hdfcKey = CategoryMemoryKey.from(hdfc)
        val axisKey = CategoryMemoryKey.from(axis)

        assertEquals(
            "BANK|hdfc|BANK_ACCOUNT|1234",
            hdfcKey
        )

        assertEquals(
            "BANK|axis|BANK_ACCOUNT|1234",
            axisKey
        )
    }

    @Test
    fun `bank account and credit card do not collide`() {
        val bankAccount = result(
            bank = "HDFC",
            lastFour = "1234",
            accountType = AccountType.BANK_ACCOUNT
        )

        val creditCard = result(
            bank = "HDFC",
            lastFour = "1234",
            accountType = AccountType.CREDIT_CARD
        )

        assertEquals(
            "BANK|hdfc|BANK_ACCOUNT|1234",
            CategoryMemoryKey.from(bankAccount)
        )

        assertEquals(
            "BANK|hdfc|CREDIT_CARD|1234",
            CategoryMemoryKey.from(creditCard)
        )
    }

    @Test
    fun `missing last four does not create bank memory key`() {
        val parserResult = result(
            bank = "HDFC",
            lastFour = null,
            accountType = AccountType.BANK_ACCOUNT
        )

        assertNull(CategoryMemoryKey.from(parserResult))
    }

    @Test
    fun `missing bank does not create bank memory key`() {
        val parserResult = result(
            bank = null,
            lastFour = "1234",
            accountType = AccountType.BANK_ACCOUNT
        )

        assertNull(CategoryMemoryKey.from(parserResult))
    }

    @Test
    fun `unknown account type does not create weak memory key`() {
        val parserResult = result(
            bank = "HDFC",
            lastFour = "1234",
            accountType = AccountType.UNKNOWN
        )

        assertNull(CategoryMemoryKey.from(parserResult))
    }

    @Test
    fun `merchant name alone does not create memory key`() {
        val parserResult = ParserResult(
            isTransaction = true,
            amountPaise = 10000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.UNKNOWN,
            bank = null,
            merchantName = "Swiggy",
            payeeId = null,
            accountLastFour = null,
            refNumber = null,
            confidence = 1.0f
        )

        assertNull(CategoryMemoryKey.from(parserResult))
    }
}
