package com.example.financesmstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLogRedactorTest {
    @Test
    fun blankBecomesDash() {
        assertEquals("-", NotificationLogRedactor.redact(null))
        assertEquals("-", NotificationLogRedactor.redact("   "))
    }

    @Test
    fun amountsAndShortFragmentsStayReadable() {
        assertEquals(
            "INR 1820.00 was debited from a/c XX3370",
            NotificationLogRedactor.redact("INR 1820.00 was debited from a/c XX3370")
        )
    }

    @Test
    fun longDigitRunsAreMasked() {
        val out = NotificationLogRedactor.redact("UPI Ref 715732131571 to 9876543210")
        assertFalse(out.contains("715732131571"))
        assertFalse(out.contains("9876543210"))
        assertTrue(out.contains("############"))
    }

    @Test
    fun longTextIsTruncatedAndNewlinesFlattened() {
        val out = NotificationLogRedactor.redact("a\n".repeat(200))
        assertFalse(out.contains("\n"))
        assertTrue(out.length <= 91)
    }
}
