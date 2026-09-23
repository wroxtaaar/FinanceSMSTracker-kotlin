package com.example.financesmstracker.parser

import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {
    private val manager = SmsParserManager()

    @Test
    fun testHdfcDebit() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Rs. 500.00 debited from a/c xx1234 on 12-OCT-23 to UPI/Swiggy/swiggy@upi Ref:123456"
        )
        assertTrue(result.isTransaction)
        assertEquals(50000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("HDFC", result.bank)
        assertEquals("swiggy@upi", result.payeeId)
        assertEquals("1234", result.accountLastFour)
        assertEquals("123456", result.refNumber)
    }

    @Test
    fun testAxisDebit() {
        val result = manager.parse(
            "AD-AXISBK-S",
            "INR 1,234.50 debited from A/C no. XXXX5678 on 12-OCT-23 towards UPI/Zomato/zomato@upi Ref 789012"
        )
        assertTrue(result.isTransaction)
        assertEquals(123450L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("AXIS", result.bank)
        assertEquals("zomato@upi", result.payeeId)
        assertEquals("5678", result.accountLastFour)
    }

    @Test
    fun testUpiCredit() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Rs 2,500.00 credited to a/c xx1234 by UPI/Ramesh/rameshkumar@upi Ref:999888"
        )
        assertTrue(result.isTransaction)
        assertEquals(250000L, result.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals("rameshkumar@upi", result.payeeId)
    }

    @Test
    fun testCardTransaction() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Card ending 9999 used for Rs. 4,500.00 at Amazon Ecom on 15-OCT-23"
        )
        assertTrue(result.isTransaction)
        assertEquals(450000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.CARD, result.paymentMethod)
        assertEquals(AccountType.CREDIT_CARD, result.accountType)
        assertEquals("9999", result.accountLastFour)
    }

    @Test
    fun testAtmWithdrawal() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "ATM WDL of Rs. 10,000.00 at HDFC ATM a/c xx1234 on 15-OCT-23"
        )
        assertTrue(result.isTransaction)
        assertEquals(1000000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.ATM, result.paymentMethod)
    }

    @Test
    fun testNeftTransaction() {
        val result = manager.parse(
            "AD-AXISBK-S",
            "INR 50,000.00 debited from A/C XXXX1234 via NEFT Ref UTR123456789"
        )
        assertTrue(result.isTransaction)
        assertEquals(5000000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.NEFT, result.paymentMethod)
        assertEquals("UTR123456789", result.refNumber)
    }

    @Test
    fun testImpsTransaction() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Rs. 5,000.00 sent via IMPS from a/c xx1234 Ref 112233"
        )
        assertTrue(result.isTransaction)
        assertEquals(500000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.IMPS, result.paymentMethod)
    }

    @Test
    fun testRtgsTransaction() {
        val result = manager.parse(
            "AD-AXISBK-S",
            "INR 2,00,000.00 debited via RTGS from A/C XXXX1234 Ref RTGS999"
        )
        assertTrue(result.isTransaction)
        assertEquals(20000000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.RTGS, result.paymentMethod)
    }

    @Test
    fun testRefund() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Your a/c xx1234 has been credited with Rs. 299.00 towards refund from Flipkart Ref 444555"
        )
        assertTrue(result.isTransaction)
        assertEquals(29900L, result.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
    }

    @Test
    fun testSalaryCredit() {
        val result = manager.parse(
            "AD-AXISBK-S",
            "A/C XXXX1234 credited with INR 75,000.00 on 01-NOV-23 by SALARY/PAYROLL"
        )
        assertTrue(result.isTransaction)
        assertEquals(7500000L, result.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
    }

    @Test
    fun testOtpMessageRejection() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Your OTP for transaction of Rs. 500.00 is 123456. Do not share."
        )
        assertFalse(result.isTransaction)
    }

    @Test
    fun testPromotionalMessageRejection() {
        val result = manager.parse(
            "AMAZON",
            "Get 50% off on your shopping up to Rs. 500. Shop now!"
        )
        assertFalse(result.isTransaction)
    }

    @Test
    fun testMultipleAmountFormats() {
        val fixtures = listOf(
            "₹500" to 50000L,
            "Rs. 500" to 50000L,
            "Rs 500" to 50000L,
            "INR 500" to 50000L,
            "500 INR" to 50000L,
            "500.00 INR" to 50000L,
            "500/-" to 50000L,
            "₹1,234.50" to 123450L
        )

        for ((text, expectedPaise) in fixtures) {
            val amt = AmountParser.parseAmountToPaise(text)
            assertEquals("Failed for text: $text", expectedPaise, amt)
        }
    }

    @Test
    fun testCommaSeparatedIndianAmounts() {
        val text = "INR 1,23,456.78 debited from A/C"
        val paise = AmountParser.parseAmountToPaise(text)
        assertEquals(12345678L, paise)
    }

    @Test
    fun testAmbiguousMessage() {
        val result = manager.parse(
            "UNKNOWN",
            "Your account balance is 500"
        )
        assertFalse(result.isTransaction)
    }

    @Test
    fun testVpaExtractionValidFormats() {
        val r1 = manager.parse("JD-HDFCBK-S", "Rs. 100 debited from a/c xx1234 to ramesh@upi")
        assertEquals("ramesh@upi", r1.payeeId)

        val r2 = manager.parse("JD-HDFCBK-S", "Rs. 200 debited to merchant@paytm via UPI")
        assertEquals("merchant@paytm", r2.payeeId)

        val r3 = manager.parse("JD-HDFCBK-S", "Rs. 300 paid to abc.xyz@okaxis")
        assertEquals("abc.xyz@okaxis", r3.payeeId)

        val r4 = manager.parse("JD-HDFCBK-S", "Rs. 400 sent to user-123@oksbi")
        assertEquals("user-123@oksbi", r4.payeeId)
    }

    @Test
    fun testVpaExtractionAbsentInNonUpiSms() {
        val cardRes = manager.parse("JD-HDFCBK-S", "Card ending 9999 used for Rs. 4,500.00 at Amazon Ecom on 15-OCT-23")
        assertNull(cardRes.payeeId)

        val atmRes = manager.parse("JD-HDFCBK-S", "ATM WDL of Rs. 10,000.00 at HDFC ATM a/c xx1234 on 15-OCT-23")
        assertNull(atmRes.payeeId)
    }

    @Test
    fun testUnrelatedTextNotTreatedAsVpa() {
        val res = manager.parse("JD-HDFCBK-S", "Your alert is sent to support@hdfcbank.com regarding account security update")
        assertNull(res.payeeId)
    }

    @Test
    fun testMerchantNameExtractionAjayMecalStore() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Sent Rs.400.00 From HDFC Bank A/C *9591 To AJAY MECAL STORE on 12-OCT-23"
        )
        assertTrue(result.isTransaction)
        assertEquals("AJAY MECAL STORE", result.merchantName)
        assertNull(result.payeeId)
    }

    @Test
    fun testMerchantNameExtractionTahirConfectionery() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Paid Rs. 200 to Tahir confectionery via UPI"
        )
        assertTrue(result.isTransaction)
        assertEquals("Tahir confectionery", result.merchantName)
    }

    @Test
    fun testMerchantNameExtractionRawIdentifier() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Sent Rs. 150 to 82184053ptyes"
        )
        assertTrue(result.isTransaction)
        assertEquals("82184053ptyes", result.merchantName)
    }

    @Test
    fun testUpiMerchantAndVpaBothExtracted() {
        val result = manager.parse(
            "JD-HDFCBK-S",
            "Rs. 500 debited to UPI/Swiggy/swiggy@upi Ref 123456"
        )
        assertTrue(result.isTransaction)
        assertEquals("Swiggy", result.merchantName)
        assertEquals("swiggy@upi", result.payeeId)
    }

    @Test
    fun testMultipartSmsReconstructionParsing() {
        val part1 = "Sent Rs.400.00 From HDFC Bank A/C *9591 To AJAY MECAL"
        val part2 = "STORE on 12-OCT-23 Ref 625674065"
        val fullBody = part1 + part2

        val result = manager.parse("JD-HDFCBK-S", fullBody)
        assertTrue(result.isTransaction)
        assertEquals(40000L, result.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals("HDFC", result.bank)
        assertNotNull(result.merchantName)
        assertTrue(result.merchantName!!.contains("AJAY MECAL"))
    }

    @Test
    fun testFalsePositiveSafetyCases() {
        val c1 = manager.parse("HDFC", "Your account has been credited with Rs. 1000")
        assertTrue(c1.isTransaction)
        assertNull(c1.merchantName)
        assertNull(c1.payeeId)

        val c2 = manager.parse("HDFC", "Rs. 500 transferred to your bank account")
        assertTrue(c2.isTransaction)
        assertNull(c2.merchantName)
        assertNull(c2.payeeId)

        val c3 = manager.parse("HDFC", "Rs. 500 paid at 10:30")
        assertTrue(c3.isTransaction)
        assertNull(c3.merchantName)
        assertNull(c3.payeeId)

        val c4 = manager.parse("HDFC", "UPI transaction processed successfully")
        assertFalse(c4.isTransaction)

        val c5 = manager.parse("HDFC", "Rs. 500 sent. Alert sent to test@example.com")
        assertTrue(c5.isTransaction)
        assertNull(c5.payeeId)

        val c6 = manager.parse("HDFC", "Rs. 500 spent. View statement at https://example.com")
        assertTrue(c6.isTransaction)
        assertNull(c6.payeeId)
        assertNull(c6.merchantName)

        val c7 = manager.parse("HDFC", "Rs. 500 debited to UPI/Swiggy/swiggy@upi")
        assertTrue(c7.isTransaction)
        assertEquals("Swiggy", c7.merchantName)
        assertEquals("swiggy@upi", c7.payeeId)

        val c8 = manager.parse("HDFC", "Paid Rs 500 to swiggy@upi via UPI")
        assertTrue(c8.isTransaction)
        assertEquals("swiggy@upi", c8.payeeId)

        val c9 = manager.parse("HDFC", "Sent Rs. 500 To AJAY MECAL STORE Ref 12345")
        assertTrue(c9.isTransaction)
        assertEquals("AJAY MECAL STORE", c9.merchantName)
        assertEquals("12345", c9.refNumber)

        val c10 = manager.parse("HDFC", "Sent Rs. 500 To Tahir confectionery on 12-OCT-23")
        assertTrue(c10.isTransaction)
        assertEquals("Tahir confectionery", c10.merchantName)
        assertNull(c10.payeeId)
    }
}
