package com.example.financesmstracker.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmiNoteTest {
    private val money = { minor: Long -> "₹" + (minor / 100) }

    private fun item(shortfall: Boolean) = EmiReservation("Axis loan", "AXIS", "3370", 1_110_000, 10, shortfall)

    @Test
    fun coveredEmiIsAQuietReminder() {
        assertEquals("Reserved for Axis loan EMI ₹11100 (due 10th)", EmiNote.format(listOf(item(false)), money))
    }

    @Test
    fun lowBalanceAddsAWarning() {
        val text = EmiNote.format(listOf(item(true)), money)
        assertTrue(text.contains("AXIS ••3370 is below the EMI"))
    }

    @Test
    fun ordinalsHandleTeensAndEndings() {
        assertEquals("1st", EmiNote.ordinal(1))
        assertEquals("2nd", EmiNote.ordinal(2))
        assertEquals("3rd", EmiNote.ordinal(3))
        assertEquals("11th", EmiNote.ordinal(11))
        assertEquals("22nd", EmiNote.ordinal(22))
    }
}
