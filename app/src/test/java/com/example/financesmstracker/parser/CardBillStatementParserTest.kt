package com.example.financesmstracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardBillStatementParserTest {
    @Test
    fun parsesAxis9206StatementSms() {
        val result = CardBillStatementParser.parse(
            "AD-AXISBK",
            """
            Your statement for Axis Bank Credit Card no. XX9206 is generated.
            Due on: 21-10-26
            Total amt: INR Dr. 53136.06
            Min amt due: INR Dr. 1063.00
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(5313606L, result!!.amountPaise)
        assertEquals("AXIS", result.bank)
        assertEquals("9206", result.accountLastFour)
    }

    @Test
    fun parsesAxis1175StatementSms() {
        val result = CardBillStatementParser.parse(
            "AD-AXISBK-S",
            """
            Your statement for Axis Bank Credit Card no. XX1175 is generated.
            Due on: 21-10-26
            Total amt: INR Dr. 45859.00
            Min amt due: INR Dr. 918.00
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(4585900L, result!!.amountPaise)
        assertEquals("AXIS", result.bank)
        assertEquals("1175", result.accountLastFour)
    }

    @Test
    fun doesNotTreatCardPurchaseAsBill() {
        val result = CardBillStatementParser.parse(
            "AD-AXISBK-S",
            """
            Spent INR 9402
            Axis Bank Card no. XX9206
            02-10-26 17:31:24 IST
            BIOTECH PHA
            Avl Limit: INR 120491.14
            """.trimIndent()
        )

        assertNull(result)
    }

    @Test
    fun parsesExplicitCreditCardPaymentConfirmationSms() {
        val result = CardBillPaymentSmsParser.parse(
            "AD-SBICRD",
            "Payment of Rs.7,773.00 received towards SBI Credit Card XXXX-0065. Ref no. 1234567890."
        )

        assertNotNull(result)
        assertEquals(777300L, result!!.amountPaise)
        assertEquals("SBI", result.cardBank)
        assertEquals("0065", result.cardLastFour)
        assertEquals(PaymentMethod.UNKNOWN, result.paymentMethod)
        assertEquals("1234567890", result.reference)
    }

    @Test
    fun doesNotTreatBankDebitToCredAsCardBillPayment() {
        val result = CardBillPaymentSmsParser.parse(
            "AD-HDFCBK-T",
            """
            Sent Rs.7773.00
            From HDFC Bank A/C *9591
            To CRED Club
            On 02/10/26
            Ref 664110896238
            """.trimIndent()
        )

        assertNull(result)
    }

    @Test
    fun doesNotTreatStatementDueAsCardBillPayment() {
        val result = CardBillPaymentSmsParser.parse(
            "AD-AXISBK-S",
            """
            Your statement for Axis Bank Credit Card no. XX9206 is generated.
            Due on: 21-10-26
            Total amt: INR Dr. 53136.06
            """.trimIndent()
        )

        assertNull(result)
    }

}
