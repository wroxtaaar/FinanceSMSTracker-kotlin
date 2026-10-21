package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.MerchantParser
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TransactionCategorizerWordMatchTest {
    private fun debit(merchant: String) = ParserResult(
        isTransaction = true,
        amountPaise = 182_000L,
        transactionType = TransactionType.DEBIT,
        paymentMethod = PaymentMethod.CARD,
        accountType = AccountType.CREDIT_CARD,
        bank = "AXIS",
        merchantName = merchant
    )

    @Test
    fun keywordsInsideOtherWordsDoNotMatch() {
        val body = "Spent INR 1820 at RAJ MEDICAL STORE Kolar cabinet"
        assertNotEquals("TRAVEL", TransactionCategorizer.categorize(debit("RAJ MEDICAL"), body))
    }

    @Test
    fun wholeWordKeywordsStillMatch() {
        assertEquals("TRAVEL", TransactionCategorizer.categorize(debit("UBER"), "Spent INR 200 at UBER"))
        assertEquals("FOOD", TransactionCategorizer.categorize(debit("ZOMATO"), "Spent INR 200 at ZOMATO"))
        assertEquals("BILLS", TransactionCategorizer.categorize(debit("x"), "Electricity bills paid"))
    }

    @Test
    fun multiLineMerchantIsNotTruncated() {
        val body = "Spent INR 1820\nAxis Bank Card no. XX9206\n16-10-26 16:17:00 IST\nRAJ MEDICAL STORE\nAvl Limit: INR 1000.00\nNot you? SMS BLOCK 9206 to 919951860002"
        assertEquals("RAJ MEDICAL STORE", MerchantParser.extractMerchantAndVpa(body).first)
    }

    @Test
    fun stepFourTerminatorsNeedWordBoundaries() {
        assertEquals("ASHU ONKAR", MerchantParser.extractMerchantAndVpa("Rs.50 paid to ASHU ONKAR on 16-10-26").first)
        assertEquals("MR SHARMA", MerchantParser.extractMerchantAndVpa("INR 15 sent to MR SHARMA\nRef 123456789012").first)
    }

    private fun axisCard(amount: Int, stamp: String, merchant: String) =
        "Spent INR $amount\nusing Axis Bank Credit Card no. XX9206 on\n$stamp IST at\n$merchant.\n" +
            "Avl limit: INR 64678.85. Not you? SMS BLOCK\n9206 to +919951860002."

    @Test
    fun newAxisCardLayoutKeepsFullMerchantName() {
        assertEquals("RAJNEESH PR", MerchantParser.extractMerchantAndVpa(axisCard(20, "15-10-26 17:16:04", "RAJNEESH PR")).first)
        assertEquals("ASHU PHARME", MerchantParser.extractMerchantAndVpa(axisCard(20, "16-10-26 16:45:50", "ASHU PHARME")).first)
        assertEquals("Tanveer hot", MerchantParser.extractMerchantAndVpa(axisCard(60, "17-10-26 20:02:42", "Tanveer hot")).first)
        assertEquals("JAVED", MerchantParser.extractMerchantAndVpa(axisCard(20, "17-10-26 18:48:52", "JAVED")).first)
    }

    @Test
    fun axisCardPaymentDueReminderIsNotATransaction() {
        val sms = "Payment of INR 53136.06 for Axis Bank Credit Card no. XX9206 is due on 21-10-26 with minimum amount due of INR 1063. Ignore if paid."
        val result = com.example.financesmstracker.parser.SmsParserManager().parse("AD-AXISBK-S", sms)
        org.junit.Assert.assertFalse(result.isTransaction)
    }

    @Test
    fun newAxisCardLayoutParsesAsCardSpend() {
        val result = com.example.financesmstracker.parser.SmsParserManager()
            .parse("AD-AXISBK", axisCard(20, "15-10-26 17:16:04", "RAJNEESH PR"))
        org.junit.Assert.assertTrue(result.isTransaction)
        assertEquals(2000L, result.amountPaise)
        assertEquals("9206", result.accountLastFour)
        assertEquals("RAJNEESH PR", result.merchantName)
        assertEquals(PaymentMethod.CARD, result.paymentMethod)
    }

    @Test
    fun medicalMerchantsGetMedicalCategory() {
        assertEquals("MEDICAL", TransactionCategorizer.categorize(debit("ASHU PHARME"), "Spent INR 20 at ASHU PHARME"))
        assertEquals("MEDICAL", TransactionCategorizer.categorize(debit("RAJ MEDICAL"), "Spent INR 1820 at RAJ MEDICAL"))
        assertEquals("MEDICAL", TransactionCategorizer.categorize(debit("CITY HOSPITAL"), "Spent INR 500 at CITY HOSPITAL"))
        assertNotEquals("MEDICAL", TransactionCategorizer.categorize(debit("JAVED"), "Spent INR 20 at JAVED"))
    }

    @Test
    fun upiMerchantPaymentKeepsMerchantNameAndReference() {
        val sms = "INR 33136.06 debited\nA/c no. XX3370\n21-10-26, 22:41:09\nUPI/P2M/629471619542/CRED Club\n" +
            "Not you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank"
        val result = com.example.financesmstracker.parser.SmsParserManager().parse("AD-AXISBK-S", sms)
        org.junit.Assert.assertTrue(result.isTransaction)
        assertEquals(3_313_606L, result.amountPaise)
        assertEquals("CRED Club", result.merchantName)
        assertEquals("629471619542", result.refNumber)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals("3370", result.accountLastFour)
    }

    @Test
    fun cardPaymentReceivedSmsIsRecognisedAsCardBillPayment() {
        val sms = "Payment of INR 33136.06 has been received towards your Axis Bank Credit Card XX9206 on 21-10-26 - Axis Bank"
        val parsed = com.example.financesmstracker.parser.CardBillPaymentSmsParser.parse("AD-AXISBK-S", sms)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals(3_313_606L, parsed!!.amountPaise)
        assertEquals("AXIS", parsed.cardBank)
        assertEquals("9206", parsed.cardLastFour)
    }
}
