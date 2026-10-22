package com.example.financesmstracker.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsHandlingLogTest {
    @Test
    fun keepsOnlyNewestEntries() {
        var list = emptyList<SmsHandlingLog.Entry>()
        repeat(SmsHandlingLog.MAX_ENTRIES + 5) {
            list = SmsHandlingLog.appendCapped(list, SmsHandlingLog.Entry(it.toLong(), "S", "b", "o$it"))
        }
        assertEquals(SmsHandlingLog.MAX_ENTRIES, list.size)
        assertEquals("o5", list.first().outcome)
    }

    @Test
    fun snippetCollapsesWhitespaceAndTruncates() {
        val s = SmsHandlingLog.snippetOf("Payment of INR\n33136.06   " + "x".repeat(200))
        assertTrue(s.startsWith("Payment of INR 33136.06 x"))
        assertEquals(90, s.length)
    }
}
