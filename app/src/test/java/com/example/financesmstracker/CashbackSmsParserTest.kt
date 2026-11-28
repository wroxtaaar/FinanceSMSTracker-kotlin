package com.example.financesmstracker

import com.example.financesmstracker.parser.CardBillPaymentSmsParser
import com.example.financesmstracker.parser.CashbackSmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CashbackSmsParserTest {
    private val airtel =
        "Congratulations! INR 868 cashback has been credited to your Airtel Axis Bank Credit Card no. XX9206 for NOV'26 spends. " +
            "Break-up: 25% cashback on eligible Airtel spends: INR 337, 10% cashback on eligible Airtel spends: INR 143, " +
            "1% regular cashback on other spends: INR 388."
    private val ace =
        "Congratulations! Cashback of INR 3014 has been credited to your Axis Bank Ace Credit Card XX1175 towards your last month spends - Axis Bank"

    @Test
    fun airtelAxisCashbackReadsTheTotalNotTheBreakUp() {
        val parsed = CashbackSmsParser.parse(airtel)!!
        assertEquals(86_800L, parsed.amountPaise)
        assertEquals("AXIS", parsed.cardBank)
        assertEquals("9206", parsed.cardLastFour)
    }

    @Test
    fun aceCashbackIsRead() {
        val parsed = CashbackSmsParser.parse(ace)!!
        assertEquals(301_400L, parsed.amountPaise)
        assertEquals("AXIS", parsed.cardBank)
        assertEquals("1175", parsed.cardLastFour)
    }

    @Test
    fun cashbackIsNeverTakenForABillPayment() {
        assertNull(CardBillPaymentSmsParser.parse("AXISBK", airtel))
        assertNull(CardBillPaymentSmsParser.parse("AXISBK", ace))
    }

    @Test
    fun offersAndPromisesAreNotCredits() {
        assertNull(CashbackSmsParser.parse("Get 5% cashback on your Axis Bank Credit Card XX1175 spends this month"))
        assertNull(CashbackSmsParser.parse("Cashback of INR 500 will be credited to your Axis Bank Credit Card XX1175"))
        assertNotNull(CashbackSmsParser.parse(ace))
    }
}
