package com.example.financesmstracker.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsCheckTextTest {
    private val money = { minor: Long -> "₹" + (minor / 100) }
    private val date = { _: Long -> "24 Nov" }

    @Test
    fun notConnectedAndErrorsAreSaidPlainly() {
        assertTrue(SplitsCheckText.format(SplitsCheck(false, null, "2026-11-25", 0, emptyList(), emptyList()), money, date).contains("not connected"))
        assertTrue(SplitsCheckText.format(SplitsCheck(true, "Splits HTTP 503", "2026-11-25", 0, emptyList(), emptyList()), money, date).contains("503"))
    }

    @Test
    fun listsWhatIsMissingWithATotal() {
        val check = SplitsCheck(
            true, null, "2026-11-25", 4,
            listOf(SplitsCheckItem(1, "NAUSHAD ·9206", 9_000), SplitsCheckItem(2, "AABDA", 3_000)),
            listOf(SplitsCheckItem(0, "cash chai  2026-11-26", 2_000))
        )
        val lines = SplitsCheckText.format(check, money, date).lines()
        assertEquals("NOT IN SPLITS YET (2), total ₹120", lines[0])
        assertEquals("24 Nov  ₹90  NAUSHAD ·9206", lines[1])
        assertTrue(lines.contains("4 others are matched."))
        assertTrue(lines.any { it.startsWith("IN SPLITS, NOT IN THE APP (1)") })
    }

    @Test
    fun allMatchedSaysSo() {
        val text = SplitsCheckText.format(SplitsCheck(true, null, "2026-11-25", 7, emptyList(), emptyList()), money, date)
        assertEquals("Everything since 2026-11-25 is in Splits (7 matched).", text)
    }
}
