package com.example.financesmstracker.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardBillPaymentNotificationParserTest {

    @Test
    fun parsesCredSbiCardBillPayment() {
        val result = CardBillPaymentNotificationParser.parse(
            "paid instantly to SBI",
            "that was fast: payment of ₹7,773.00 on your SBI credit card XXXX-0065 has been processed. tap to check your latest bank balance.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(777300L, result!!.amountPaise)
        assertEquals("SBI", result.cardBank)
        assertEquals("0065", result.cardLastFour)
    }

    @Test
    fun parsesOtherSupportedCardIssuers() {
        val result = CardBillPaymentNotificationParser.parse(
            "Payment successful",
            "payment of INR 1234.50 on your HDFC credit card XXXX-9591 has been processed",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(123450L, result!!.amountPaise)
        assertEquals("HDFC", result.cardBank)
        assertEquals("9591", result.cardLastFour)
    }

    @Test
    fun rejectsOrdinaryCreditCardNotification() {
        val result = CardBillPaymentNotificationParser.parse(
            "SBI Card",
            "Your SBI credit card statement is ready.",
            null,
            null
        )

        assertNull(result)
    }
}
