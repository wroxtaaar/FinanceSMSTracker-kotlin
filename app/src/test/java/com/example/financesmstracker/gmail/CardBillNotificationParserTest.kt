package com.example.financesmstracker.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import import org.junit.Assert.assertNull
import org.junit.Test

class CardBillNotificationParserTest {
    @Test
    fun detectsAxisStatementNotificationWithoutAmount() {
        val result = CardBillNotificationParser.parse(
            "cc.statements",
            """
            Airtel Axis Bank Rupay Credit Card ending XX06 - October 2026
            Dear Customer,
            Please find enclosed your credit card statement for OCTOBER 2026.
            Total Amount Due
            INR
            Minimum Amount Due
            """.trimIndent(),
            null,
            null
        )

        assertNotNull(result)
        assertEquals("AXIS", result!!.bank)
        assertEquals("06", result.accountLastTwo)
        assertEquals(null, result.amountPaise)
    }

    @Test
    fun detectsAxisStatementNotificationWithAmount() {
        val result = CardBillNotificationParser.parse(
            "cc.statements",
            "Your Axis Bank Credit Card statement ending XX75. Total Amount Due INR 45859 Dr.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals("AXIS", result!!.bank)
        assertEquals("75", result.accountLastTwo)
        assertEquals(4585900L, result.amountPaise)
    }
}
