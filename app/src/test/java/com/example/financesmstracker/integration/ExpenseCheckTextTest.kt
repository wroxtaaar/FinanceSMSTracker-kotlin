package com.example.financesmstracker.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseCheckTextTest {
    private val money = { minor: Long -> "₹" + (minor / 100) }
    private val date = { _: Long -> "24 Nov" }

    @Test
    fun notConnectedAndErrorsAreSaidPlainly() {
        assertTrue(ExpenseCheckText.format(ExpenseCheck(false, null, "2026-11-25", 0, 0, emptyList()), money, date).contains("not connected"))
        assertTrue(ExpenseCheckText.format(ExpenseCheck(true, "Expense HTTP 503", "2026-11-25", 0, 0, emptyList()), money, date).contains("503"))
    }

    @Test
    fun listsWhatIsMissingWithATotal() {
        val check = ExpenseCheck(
            true, null, "2026-11-25", 4, 1,
            listOf(ExpenseCheckItem(1, "NAUSHAD ·9206", 9_000), ExpenseCheckItem(2, "AABDA", 3_000))
        )
        val lines = ExpenseCheckText.format(check, money, date).lines()
        assertEquals("NOT IN EXPENSE YET (2), total ₹120", lines[0])
        assertEquals("24 Nov  ₹90  NAUSHAD ·9206", lines[1])
        assertEquals("4 in Expense, 1 skipped.", lines[3])
    }

    @Test
    fun allDoneSaysSo() {
        val text = ExpenseCheckText.format(ExpenseCheck(true, null, "2026-11-25", 7, 0, emptyList()), money, date)
        assertEquals("Everything since 2026-11-25 is in Expense (7 in Expense).", text)
    }
}
