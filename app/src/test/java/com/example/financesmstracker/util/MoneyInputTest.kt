package com.example.financesmstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyInputTest {
    @Test
    fun parsesRupeesIntoPaiseIgnoringCommasAndSpaces() {
        assertEquals(123_456L, MoneyInput.parseMinor(" 1,234.56 "))
        assertEquals(0L, MoneyInput.parseMinor("0"))
    }

    @Test
    fun rejectsNonNumbersAndNegativesUnlessAllowed() {
        assertNull(MoneyInput.parseMinor(""))
        assertNull(MoneyInput.parseMinor("abc"))
        assertNull(MoneyInput.parseMinor("-5"))
        assertEquals(-500L, MoneyInput.parseMinor("-5", allowNegative = true))
    }
}
