package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionCategorizerCreditTest {

    @Test
    fun `credit ignores remembered groceries category`() {
        val result = ParserResult(
            isTransaction = true,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.BANK_TRANSFER
        )

        assertEquals(
            "TRANSFER",
            TransactionCategorizer.categorize(
                result,
                "INR 25000 credited to your account",
                rememberedCategory = "GROCERIES"
            )
        )
    }

    @Test
    fun `credit salary is always salary even when remembered category exists`() {
        val result = ParserResult(
            isTransaction = true,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.BANK_TRANSFER
        )

        assertEquals(
            "SALARY",
            TransactionCategorizer.categorize(
                result,
                "Salary credited to your account",
                rememberedCategory = "GROCERIES"
            )
        )
    }

    @Test
    fun `credit refund is always refund even when remembered category exists`() {
        val result = ParserResult(
            isTransaction = true,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.UPI
        )

        assertEquals(
            "REFUND",
            TransactionCategorizer.categorize(
                result,
                "Refund credited to your account",
                rememberedCategory = "GROCERIES"
            )
        )
    }

    @Test
    fun `credit with no salary or refund keywords becomes transfer`() {
        val result = ParserResult(
            isTransaction = true,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.UPI
        )

        assertEquals(
            "TRANSFER",
            TransactionCategorizer.categorize(
                result,
                "INR 11100 credited through UPI",
                rememberedCategory = "FOOD"
            )
        )
    }

    @Test
    fun `debit still honors remembered category`() {
        val result = ParserResult(
            isTransaction = true,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UPI
        )

        assertEquals(
            "SHOPPING",
            TransactionCategorizer.categorize(
                result,
                "Paid via UPI to unknown merchant",
                rememberedCategory = "SHOPPING"
            )
        )
    }
}
