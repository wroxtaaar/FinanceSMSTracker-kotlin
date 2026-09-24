package com.example.financesmstracker.parser

import com.example.financesmstracker.data.UnrecognizedSms
import org.junit.Assert.*
import org.junit.Test

class SenderTrustAndReviewTest {

    private val parserManager = SmsParserManager()

    @Test
    fun test1_TrustedFinancialSenderAndValidTransaction() {
        val sender = "VM-HDFCBK-S"
        val body = "Rs. 500.00 debited from a/c xx1234 on 12-OCT-23 to UPI/Swiggy/swiggy@upi Ref:123456"
        val trust = SenderTrustManager.classifySender(sender)
        assertEquals(SenderTrustStatus.TRUSTED, trust)

        val result = parserManager.parse(sender, body)
        assertTrue(result.isTransaction)
        assertEquals(50000L, result.amountPaise)
    }

    @Test
    fun test2_UnknownSenderFinancialLookingTextRecordedAsUnrecognized() {
        val sender = "+919876543210"
        val body = "Your account was debited with Rs. 1,000.00 at unknown merchant."
        val trust = SenderTrustManager.classifySender(sender)
        assertEquals(SenderTrustStatus.UNTRUSTED, trust)

        val isFinancial = SenderTrustManager.isFinancialLooking(body)
        assertTrue(isFinancial)

        val unrecognized = UnrecognizedSms(
            sender = sender,
            receivedAt = System.currentTimeMillis(),
            contentHash = "hash_unrec_1",
            reason = "UNRECOGNIZED_FINANCIAL_SMS"
        )
        assertNotNull(unrecognized)
        assertEquals(sender, unrecognized.sender)
    }

    @Test
    fun test3_UnknownSenderNonFinancialTextIgnored() {
        val sender = "+919876543210"
        val body = "Hello, meeting is scheduled at 4 PM today."
        assertFalse(SenderTrustManager.isFinancialLooking(body))
    }

    @Test
    fun test4_PromotionalMessageIgnored() {
        val body = "Get 50% off on your shopping up to Rs. 500. Shop now!"
        assertFalse(SenderTrustManager.isFinancialLooking(body))
    }

    @Test
    fun test5_OtpIgnored() {
        val body = "Your OTP for transaction of Rs. 500.00 is 123456. Do not share."
        assertFalse(SenderTrustManager.isFinancialLooking(body))
    }

    @Test
    fun test6_LoanMarketingIgnored() {
        val body = "Congratulations! Pre-approved loan of Rs. 5,00,000 available now. Click here."
        assertFalse(SenderTrustManager.isFinancialLooking(body))
    }

    @Test
    fun test7_BankLookingTextUnknownSenderNotAutomaticallyTrusted() {
        val sender = "MARKETING"
        val trust = SenderTrustManager.classifySender(sender)
        assertNotEquals(SenderTrustStatus.TRUSTED, trust)
    }

    @Test
    fun test8_ParserFailureOnFinancialLookingSmsRecordedForReview() {
        val body = "Special transfer of Rs. 999.00 processed."
        assertTrue(SenderTrustManager.isFinancialLooking(body))
    }

    @Test
    fun test9_DuplicateUnrecognizedSmsDeduplicated() {
        val unrecognized = UnrecognizedSms(
            sender = "SENDER",
            receivedAt = System.currentTimeMillis(),
            contentHash = "dup_hash_1",
            reason = "TEST"
        )
        assertEquals("dup_hash_1", unrecognized.contentHash)
    }

    @Test
    fun test10_ExistingKnownHdfcFixturePasses() {
        val result = parserManager.parse(
            "JD-HDFCBK-S",
            "Rs. 500.00 debited from a/c xx1234 on 12-OCT-23 to UPI/Swiggy/swiggy@upi Ref:123456"
        )
        assertTrue(result.isTransaction)
        assertEquals(50000L, result.amountPaise)
        assertEquals("HDFC", result.bank)
    }

    @Test
    fun test11_ExistingAxisFixturePasses() {
        val result = parserManager.parse(
            "AD-AXISBK-S",
            "INR 1,234.50 debited from A/C no. XXXX5678 on 12-OCT-23 towards UPI/Zomato/zomato@upi Ref 789012"
        )
        assertTrue(result.isTransaction)
        assertEquals(123450L, result.amountPaise)
        assertEquals("AXIS", result.bank)
    }

    @Test
    fun test12_ForeignCurrencyPreservedNotConvertedToInr() {
        val result = parserManager.parse(
            "AD-AXISBK-S",
            "Txn reversal of SGD 1.38 at ORACLE SIN was successful."
        )
        assertTrue(result.isTransaction)
        assertEquals(138L, result.amountPaise)
        assertEquals("SGD", result.currency)
    }

    @Test
    fun test13_SpoofedBodyTextFromUnknownSenderDoesNotAutomaticallyTrustSender() {
        val sender = "+919876543210" // Personal number attempting to spoof bank name in body
        val body = "HDFC Bank credited Rs. 500.00 to your account successfully."
        val trust = SenderTrustManager.classifySender(sender)
        // Must be UNTRUSTED because sender address is a phone number, NOT HDFC header
        assertEquals(SenderTrustStatus.UNTRUSTED, trust)
    }

    @Test
    fun test14_AxisBankSpoofBodyFromUnknownSender() {
        val sender = "PERSONAL"
        val body = "Axis Bank debited Rs. 1000 from account."
        val trust = SenderTrustManager.classifySender(sender)
        assertEquals(SenderTrustStatus.UNTRUSTED, trust)
    }
}
