package com.example.financesmstracker.truecaller

import org.junit.Assert.*
import org.junit.Test

class TruecallerNotificationParserTest {

    @Test
    fun testCreditNotificationFixture() {
        val title = "+ Rs.2.00"
        val text = "Credit Alert! Rs.2.00 credited to HDFC Bank"
        val result = TruecallerNotificationParser.parse(title, text, 1000L, "hash1")

        assertTrue(result.isNotificationTransaction)
        assertEquals(200L, result.amountPaise)
        assertEquals("INR", result.currency)
        assertEquals(NotificationDirection.CREDIT, result.direction)
        assertEquals("HDFC Bank", result.bankProvider)
    }

    @Test
    fun testDebitNotificationFixture() {
        val title = "− Rs.500.00"
        val text = "Spent Rs.500 at Amazon using HDFC Bank"
        val result = TruecallerNotificationParser.parse(title, text, 1000L, "hash2")

        assertTrue(result.isNotificationTransaction)
        assertEquals(50000L, result.amountPaise)
        assertEquals("INR", result.currency)
        assertEquals(NotificationDirection.DEBIT, result.direction)
    }

    @Test
    fun testAmountPlusUnrelatedLimitIgnored() {
        val title = "Spent INR 300"
        val text = "Axis Bank Card no. XX9206\nSANJEEV\nAvl Limit: INR 193034.78"
        val result = TruecallerNotificationParser.parse(title, text, 1000L, "hash3")

        assertTrue(result.isNotificationTransaction)
        assertEquals(30000L, result.amountPaise) // Should pick 300, not 193034.78
        assertEquals("INR", result.currency)
        assertEquals(NotificationDirection.DEBIT, result.direction)
    }

    @Test
    fun testNonFinancialNotification() {
        val title = "WhatsApp"
        val text = "Hello, how are you doing today?"
        val result = TruecallerNotificationParser.parse(title, text, 1000L, "hash4")

        assertFalse(result.isNotificationTransaction)
    }

    @Test
    fun testMalformedNotification() {
        val result = TruecallerNotificationParser.parse(null, null, 1000L, "hash5")
        assertFalse(result.isNotificationTransaction)
    }

    @Test
    fun testForeignCurrencySgdNotConverted() {
        val title = "SGD 1.38"
        val text = "Txn reversal of SGD 1.38 at ORACLE SIN was successful."
        val result = TruecallerNotificationParser.parse(title, text, 1000L, "hash6")

        // Foreign currency should be parsed correctly in SGD without becoming INR
        assertTrue(result.isNotificationTransaction)
        assertEquals(138L, result.amountPaise)
        assertEquals("SGD", result.currency)
    }
}
