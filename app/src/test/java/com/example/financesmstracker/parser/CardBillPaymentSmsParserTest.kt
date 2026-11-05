package com.example.financesmstracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardBillPaymentSmsParserTest {
    @Test
    fun sbiBusinessChatConfirmationNamesTheIssuerButNoCardDigits() {
        val parsed = CardBillPaymentSmsParser.parse(
            "SBI CARDS",
            "We have received payment of Rs.1,770.10 via BBPS & the same has been credited to your SBI Credit Card. Your available limit is Rs.113,025.00."
        )!!
        assertEquals(177_010L, parsed.amountPaise)
        assertEquals("SBI", parsed.cardBank)
        // The available limit (113,025.00) must never be read as card digits.
        assertNull(parsed.cardLastFour)
    }

    @Test
    fun aMessageThatNamesTheCardDigitsStillUsesThem() {
        val parsed = CardBillPaymentSmsParser.parse(
            "SBICRD",
            "Payment of Rs.5,000.00 received and credited to your SBI Credit Card ending 0065."
        )!!
        assertEquals("0065", parsed.cardLastFour)
        assertEquals(500_000L, parsed.amountPaise)
    }

    @Test
    fun ordinaryOffersAreNotCardPayments() {
        assertNull(
            CardBillPaymentSmsParser.parse(
                "SBI CARDS",
                "Get 10% Instant Discount at Flipkart Diwali Sale with SBI Credit Card. Valid till 10 Nov'26."
            )
        )
    }
}
