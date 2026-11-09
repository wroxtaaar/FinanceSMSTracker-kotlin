package com.example.financesmstracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionSourceTest {
    @Test
    fun readsTheSourceFromTheStoredMarker() {
        assertEquals("SMS", TransactionSource.label("3f9a1c0d77e2"))
        assertEquals("Notification", TransactionSource.label("notification:abc"))
        assertEquals("E-mail", TransactionSource.label("oracle:gmail:42"))
        assertEquals("Marked by you", TransactionSource.label("manual-card-payment:7"))
        assertEquals("Card confirmation", TransactionSource.label("card-bill-payment:abc"))
    }
}
