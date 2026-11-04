package com.example.financesmstracker.gmail

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationContentTest {
    @Test
    fun payloadJoinsOnlyThePresentFieldsInOrder() {
        val content = NotificationContent("T", null, "body", null, "sub", null, null)
        assertEquals("T\nbody\nsub", content.payload)
    }

    @Test
    fun previewPrefersTextThenBigTextThenLines() {
        assertEquals("t", NotificationContent("T", null, "t", "b", null, null, "l").preview)
        assertEquals("b", NotificationContent("T", null, null, "b", null, null, "l").preview)
        assertEquals("l", NotificationContent("T", null, null, null, null, null, "l").preview)
    }
}
