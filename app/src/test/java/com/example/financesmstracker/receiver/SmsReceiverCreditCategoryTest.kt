package com.example.financesmstracker.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsReceiverCreditCategoryTest {

    @Test
    fun `credit allowed categories contain only salary transfer refund`() {
        val allowed = setOf(
            "SALARY",
            "TRANSFER",
            "REFUND"
        )

        assertEquals(3, allowed.size)
        assertTrue("SALARY" in allowed)
        assertTrue("TRANSFER" in allowed)
        assertTrue("REFUND" in allowed)
        assertTrue("GROCERIES" !in allowed)
        assertTrue("FOOD" !in allowed)
        assertTrue("SHOPPING" !in allowed)
    }
}
