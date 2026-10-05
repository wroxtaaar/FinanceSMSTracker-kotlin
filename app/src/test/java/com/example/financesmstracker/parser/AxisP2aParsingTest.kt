package com.example.financesmstracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisP2aParsingTest {
    private val manager = SmsParserManager()

    @Test
    fun testAxisP2aDebitWithMerchantAtEndOfMessage() {
        val sms = """
            INR 10.00 debited
            A/c no. XX3370
            05-10-26, 19:57:57
            UPI/P2A/911389419630/ABDUL WASIQ
            Not you? SMS BLOCKUPI Cust ID to 919951860002
            Axis Bank
        """.trimIndent()

        val result = manager.parse("AD-AXISBK-S", sms)

        assertTrue(result.isTransaction)
        assertEquals(1000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("AXIS", result.bank)
        assertEquals("3370", result.accountLastFour)
        assertEquals("911389419630", result.refNumber)
        assertEquals("ABDUL WASIQ", result.merchantName)
        assertNotNull(result.merchantName)
    }
    @Test
    fun testAxisBankAccountCreditFormat() {
        val sms = """
            INR 11100.00 credited
            A/c no. XX3370
            05-10-26, 17:48:39 IST
            UPI/P2A/452194820030/ABDUL WAS/HDFC/Paym - Axis Bank
        """.trimIndent()

        val result = manager.parse("AD-AXISBK-S", sms)

        assertTrue(result.isTransaction)
        assertEquals(1110000L, result.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("AXIS", result.bank)
        assertEquals("3370", result.accountLastFour)
        assertEquals("452194820030", result.refNumber)
    }

    @Test
    fun testAxisCreditCardSpendFormatStaysSeparateFromBankAccount() {
        val sms = """
            Spent INR 50
            Axis Bank Card no. XX9206
            05-10-26 19:28:20 IST
            Sharf Uddin
            Avl Limit: INR 106674.67
            Not you? SMS BLOCK 9206 to 919951860002
        """.trimIndent()

        val result = manager.parse("AD-AXISBK-S", sms)

        assertTrue(result.isTransaction)
        assertEquals(5000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.CARD, result.paymentMethod)
        assertEquals(AccountType.CREDIT_CARD, result.accountType)
        assertEquals("AXIS", result.bank)
        assertEquals("9206", result.accountLastFour)
        assertEquals("Sharf Uddin", result.merchantName)
    }

}
