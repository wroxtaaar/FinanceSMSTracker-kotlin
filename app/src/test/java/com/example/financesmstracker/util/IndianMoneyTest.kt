package com.example.financesmstracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IndianMoneyTest {
    @Test
    fun groupsInLakhsAndCrores() {
        assertEquals("0.00", IndianMoney.group(0))
        assertEquals("0.05", IndianMoney.group(5))
        assertEquals("999.99", IndianMoney.group(99_999))
        assertEquals("1,000.00", IndianMoney.group(100_000))
        assertEquals("7,241.57", IndianMoney.group(724_157))
        assertEquals("1,52,922.05", IndianMoney.group(15_292_205))
        assertEquals("1,41,340.00", IndianMoney.group(14_134_000))
        assertEquals("12,34,56,789.00", IndianMoney.group(1_234_567_8900))
    }

    @Test
    fun formatsWithSymbolAndSign() {
        assertEquals("₹7,241.57", IndianMoney.format(724_157))
        assertEquals("-₹1,500.00", IndianMoney.format(-150_000))
        assertEquals("-₹1,500.00", IndianMoney.signed(150_000, credit = false))
        assertEquals("+₹45,000.00", IndianMoney.signed(4_500_000, credit = true))
    }
}
