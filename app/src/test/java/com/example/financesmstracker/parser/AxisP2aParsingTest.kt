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
}
