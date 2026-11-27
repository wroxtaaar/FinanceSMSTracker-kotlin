package com.example.financesmstracker

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.ui.HomeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFormatListTest {
    private fun tx(
        amount: Long,
        category: String?,
        type: TransactionType = TransactionType.DEBIT,
        currency: String = "INR"
    ) = Transaction(
        amountPaise = amount, currency = currency, transactionType = type, paymentMethod = PaymentMethod.UPI,
        accountType = AccountType.BANK_ACCOUNT, bank = "AXIS", merchantName = null, payeeId = null,
        accountLastFour = "1234", refNumber = null, timestamp = 1L, smsHash = "h$amount$category",
        category = category, parserConfidence = 1f
    )

    @Test
    fun daySpendCountsOnlyRupeeSpending() {
        val day = listOf(
            tx(150_000, "MEDICAL"),
            tx(48_000, "GROCERIES"),
            tx(500_000, "TRANSFER"),            // own-account transfer
            tx(900_000, "BILL_PAYMENT"),        // card bill payment
            tx(100_000, "SALARY", TransactionType.CREDIT),
            tx(2_000, "SHOPPING", currency = "USD")
        )
        assertEquals(198_000L, HomeFormat.daySpendMinor(day))
    }

    @Test
    fun dueLabelsReadNaturally() {
        assertEquals("Due today", HomeFormat.dueLabel(0))
        assertEquals("Due tomorrow", HomeFormat.dueLabel(1))
        assertEquals("Due in 5 days", HomeFormat.dueLabel(5))
        assertTrue(HomeFormat.dueIsUrgent(3))
        assertFalse(HomeFormat.dueIsUrgent(4))
    }

    @Test
    fun bankAndMethodLabels() {
        assertEquals("Axis", HomeFormat.bankLabel("AXIS"))
        assertEquals("HDFC", HomeFormat.bankLabel("hdfc"))
        assertEquals("SBI", HomeFormat.bankLabel("SBI"))
        assertNull(HomeFormat.bankLabel("  "))
        assertEquals("UPI", HomeFormat.sentenceCase("UPI"))
        assertEquals("Bank transfer", HomeFormat.sentenceCase("BANK_TRANSFER"))
        assertEquals("Groceries", HomeFormat.sentenceCase("GROCERIES"))
    }
}
