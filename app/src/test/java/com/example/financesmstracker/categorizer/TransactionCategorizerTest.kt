package com.example.financesmstracker.categorizer

import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.PaymentMethod
import org.junit.Assert.*
import org.junit.Test

class TransactionCategorizerTest {

    @Test
    fun testFoodCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("FOOD", TransactionCategorizer.categorize(res, "Paid via UPI to Swiggy"))
        assertEquals("FOOD", TransactionCategorizer.categorize(res, "Paid at Zomato restaurant"))
        assertEquals("FOOD", TransactionCategorizer.categorize(res, "Coffee at local cafe"))
    }

    @Test
    fun testShoppingCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.CARD)
        assertEquals("SHOPPING", TransactionCategorizer.categorize(res, "Spent on Amazon shopping"))
        assertEquals("SHOPPING", TransactionCategorizer.categorize(res, "Purchase at Flipkart"))
        assertEquals("SHOPPING", TransactionCategorizer.categorize(res, "Myntra order placed"))
    }

    @Test
    fun testFuelCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.CARD)
        assertEquals("FUEL", TransactionCategorizer.categorize(res, "IOCL petrol pump payment"))
        assertEquals("FUEL", TransactionCategorizer.categorize(res, "BPCL fuel station"))
        assertEquals("FUEL", TransactionCategorizer.categorize(res, "HPCL diesel purchase"))
    }

    @Test
    fun testTravelCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("TRAVEL", TransactionCategorizer.categorize(res, "Uber ride booking"))
        assertEquals("TRAVEL", TransactionCategorizer.categorize(res, "Ola cab trip"))
        assertEquals("TRAVEL", TransactionCategorizer.categorize(res, "IRCTC train ticket"))
        assertEquals("TRAVEL", TransactionCategorizer.categorize(res, "Airline flight booking"))
    }

    @Test
    fun testSubscriptionCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.CARD)
        assertEquals("SUBSCRIPTION", TransactionCategorizer.categorize(res, "Netflix monthly plan"))
        assertEquals("SUBSCRIPTION", TransactionCategorizer.categorize(res, "Spotify subscription"))
        assertEquals("SUBSCRIPTION", TransactionCategorizer.categorize(res, "Amazon Prime renewal"))
    }

    @Test
    fun testBillsCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("BILLS", TransactionCategorizer.categorize(res, "Electricity bill payment"))
        assertEquals("BILLS", TransactionCategorizer.categorize(res, "Broadband recharge"))
        assertEquals("BILLS", TransactionCategorizer.categorize(res, "Mobile recharge"))
    }

    @Test
    fun testTransferCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.NEFT)
        assertEquals("TRANSFER", TransactionCategorizer.categorize(res, "NEFT transfer to friend"))
        assertEquals("TRANSFER", TransactionCategorizer.categorize(res, "IMPS fund transfer"))
        assertEquals("TRANSFER", TransactionCategorizer.categorize(res, "RTGS transfer"))
    }

    @Test
    fun testSalaryCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.BANK_TRANSFER)
        assertEquals("SALARY", TransactionCategorizer.categorize(res, "Monthly salary credited"))
        assertEquals("SALARY", TransactionCategorizer.categorize(res, "Payroll credit from employer"))
    }

    @Test
    fun testRefundCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("REFUND", TransactionCategorizer.categorize(res, "Refund processed for order"))
        assertEquals("REFUND", TransactionCategorizer.categorize(res, "Reversal of failed transaction"))
    }

    @Test
    fun testAtmCategories() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.ATM)
        assertEquals("ATM", TransactionCategorizer.categorize(res, "ATM cash withdrawal"))
    }

    @Test
    fun testDefaultUpiToGroceries() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("GROCERIES", TransactionCategorizer.categorize(res, "Paid Rs 100 via UPI"))
    }

    @Test
    fun testDefaultNonUpiToOther() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.CARD)
        assertEquals("OTHER", TransactionCategorizer.categorize(res, "Unknown transaction"))
    }

    @Test
    fun testRememberedCategoryMappingPriority() {
        val res = ParserResult(isTransaction = true, paymentMethod = PaymentMethod.UPI)
        assertEquals("SHOPPING", TransactionCategorizer.categorize(res, "Paid via UPI to store@upi", rememberedCategory = "SHOPPING"))
    }
}
