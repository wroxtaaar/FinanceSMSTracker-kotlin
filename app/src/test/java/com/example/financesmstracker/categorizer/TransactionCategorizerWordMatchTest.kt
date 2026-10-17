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
}
