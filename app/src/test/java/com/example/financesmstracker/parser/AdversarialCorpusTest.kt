package com.example.financesmstracker.parser

import com.example.financesmstracker.util.HashUtil
import org.junit.Assert.*
import org.junit.Test

class AdversarialCorpusTest {

    private val parserManager = SmsParserManager()

    @Test
    fun testA_OtpMessages() {
        val sms = "Your OTP for transaction of Rs. 500.00 is 123456. Do not share."
        val result = parserManager.parse("VM-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testB_PromotionalOffers() {
        val sms = "Get 50% off on your shopping up to Rs. 500. Shop now!"
        val result = parserManager.parse("AD-AXISBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testC_CashbackAdvertisements() {
        val sms = "Congratulations! You won cashback of Rs. 100 on your last payment."
        val result = parserManager.parse("MARKETING", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testD_LoanAdvertisements() {
        val sms = "Pre-approved personal loan of Rs. 5,00,000 is available instantly. Apply now."
        val result = parserManager.parse("LOANS", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testE_CreditCardMarketing() {
        val sms = "Upgrade your HDFC Credit Card today and enjoy lifetime free benefits and Rs. 1000 bonus."
        val result = parserManager.parse("PROMO", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testF_BalanceOnlyMessages() {
        val sms = "Your a/c xx1234 balance is Rs. 12,345.67 as of today."
        val result = parserManager.parse("JD-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testG_AvailableLimitOnlyMessages() {
        val sms = "Available limit on your Axis Card XX1175 is INR 194024.78."
        val result = parserManager.parse("AD-AXISBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testH_FailedTransactionNotifications() {
        val sms = "Transaction of Rs. 500.00 at Amazon failed due to network timeout."
        val result = parserManager.parse("JD-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testI_PendingTransactionNotifications() {
        val sms = "Pending charge of Rs. 200.00 at Uber. Will be posted soon."
        val result = parserManager.parse("JD-HDFCBK-S", sms)
        // If pending without debit confirmation, should be non-transaction or handled safely
        assertFalse(result.isTransaction)
    }

    @Test
    fun testJ_FutureDatedMandateNotifications() {
        val sms = "Auto-debit mandate of Rs. 1000.00 scheduled for next month."
        val result = parserManager.parse("JD-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testK_PhishingScamMessages() {
        val sms = "Claim your lottery prize of Rs. 10,000 by sending money to scam@upi"
        val result = parserManager.parse("SCAMMER", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testL_MultipleMonetaryAmountsFiltered() {
        val sms = "Spent INR 300 at Amazon. Avl Limit: INR 193034.78"
        val result = parserManager.parse("AD-AXISBK-S", sms)
        assertTrue(result.isTransaction)
        assertEquals(30000L, result.amountPaise) // picks 300, ignores limit
    }

    @Test
    fun testM_ForeignCurrencySgd() {
        val sms = "Txn reversal of SGD 1.38 at ORACLE SIN was successful."
        val result = parserManager.parse("AD-AXISBK-S", sms)
        assertTrue(result.isTransaction)
        assertEquals(138L, result.amountPaise)
        assertEquals("SGD", result.currency)
    }

    @Test
    fun testN_MalformedTransactionText() {
        val sms = "Rs abc debited from account."
        val result = parserManager.parse("JD-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testO_SpoofedBankNameInBodyFromUntrustedSender() {
        val sender = "+919876543210" // Untrusted
        val sms = "HDFC Bank credited Rs. 500.00 to your account."
        val trust = SenderTrustManager.classifySender(sender)
        assertNotEquals(SenderTrustStatus.TRUSTED, trust)
        val isFinancial = SenderTrustManager.isFinancialLooking(sms)
        assertTrue(isFinancial) // flagged for review as unrecognized
    }

    @Test
    fun testP_TrustedSenderNonFinancialContent() {
        val sms = "Dear customer, please update your KYC details immediately."
        val result = parserManager.parse("VM-HDFCBK-S", sms)
        assertFalse(result.isTransaction)
    }

    @Test
    fun testQ_DuplicateSmsHashDetection() {
        val body = "Rs. 500 debited from a/c xx1234"
        val h1 = HashUtil.sha256(body)
        val h2 = HashUtil.sha256(body)
        assertEquals(h1, h2)
    }

    @Test
    fun testR_MultipartSmsReconstruction() {
        val part1 = "Sent Rs.400.00 From HDFC Bank A/C *9591 To AJAY MECAL"
        val part2 = "STORE on 12-OCT-23 Ref 625674065"
        val full = part1 + part2
        val result = parserManager.parse("JD-HDFCBK-S", full)
        assertTrue(result.isTransaction)
        assertEquals(40000L, result.amountPaise)
    }
}
