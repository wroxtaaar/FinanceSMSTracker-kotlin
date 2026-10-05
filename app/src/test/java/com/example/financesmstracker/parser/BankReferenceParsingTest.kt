package com.example.financesmstracker.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class BankReferenceParsingTest {

    @Test
    fun axisP2AReferenceIsExtracted() {
        val result = AxisSmsParser().parse(
            sender = "AXIS",
            messageBody = """
                INR 3.00 debited
                A/c no. XX3370
                05-10-26, 15:04:19
                UPI/P2A/185534369134/ABDUL WASIQ
            """.trimIndent()
        )

        assertEquals("185534369134", result?.refNumber)
    }

    @Test
    fun hdfcUpiReferenceIsExtracted() {
        val result = HdfcSmsParser().parse(
            sender = "HDFC",
            messageBody = """
                Credit Alert!
                Rs.3.00 credited to HDFC Bank A/c XX9591
                on 05-10-26 from VPA 9205971964@axl
                (UPI 185534369134)
            """.trimIndent()
        )

        assertEquals("185534369134", result?.refNumber)
    }
}
