package com.example.financesmstracker.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitwiseChangesTextTest {
    private val money = { minor: Long -> "₹" + (minor / 100) }
    private val date = { _: Long -> "13 Nov" }

    private fun item(name: String?, delta: Long) =
        SplitwiseChangeItem(0, name, "DEBIT", "AXIS", "GROCERIES", kotlin.math.abs(delta), delta)

    @Test
    fun withoutAStartingPointItAsksForOne() {
        val text = SplitwiseChangesText.format(SplitwiseChanges(null, null, 0, 0, emptyList()), money, date)
        assertTrue(text.startsWith("No starting point yet"))
    }

    @Test
    fun listsEachRowWithItsSignedEffect() {
        val changes = SplitwiseChanges(1, 20_092_800, 1_308_600, 0, listOf(item("MAX HEALTHC", 1_118_600), item(null, -15_000)))
        val lines = SplitwiseChangesText.format(changes, money, date).lines()
        assertEquals("Set to ₹200928, now ₹214014 (+₹13086)", lines[0])
        assertEquals("13 Nov  +₹11186  MAX HEALTHC", lines[2])
        assertEquals("13 Nov  −₹150  AXIS", lines[3])
    }

    @Test
    fun editsToOlderRowsAreReportedSeparately() {
        val changes = SplitwiseChanges(1, 100, -7_000, -7_000, emptyList())
        val text = SplitwiseChangesText.format(changes, money, date)
        assertTrue(text.contains("No new transactions since then."))
        assertTrue(text.contains("−₹70 from edits to older rows"))
    }
}
