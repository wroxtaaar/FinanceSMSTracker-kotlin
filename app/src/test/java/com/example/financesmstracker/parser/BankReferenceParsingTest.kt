package com.example.financesmstracker.parser

import org.junit.Assert.assertEquals
import com.example.financesmstracker.util.TransactionReferenceExtractor
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
    @Test
    fun sharedExtractorHandlesAllCommonReferenceForms() {
        assertEquals(
            "911389419630",
            TransactionReferenceExtractor.extract("Transaction reference no.: 911389419630")
        )
        assertEquals(
            "911389419630",
            TransactionReferenceExtractor.extract("UPI/P2A/911389419630/ABDUL WASIQ/HDFC")
        )
        assertEquals(
            "911389419630",
            TransactionReferenceExtractor.extract("Credit received (UPI 911389419630)")
        )
    }

    @Test
    fun axisCreditAndHdfcCreditUseSameTransferReference() {
        val axisCredit = AxisSmsParser().parse(
            sender = "AD-AXISBK-S",
            messageBody = """
                INR 11100.00 credited
                A/c no. XX3370
                05-10-26, 17:48:39 IST
                UPI/P2A/452194820030/ABDUL WAS/HDFC/Paym - Axis Bank
            """.trimIndent()
        )
        val hdfcCredit = HdfcSmsParser().parse(
            sender = "VM-HDFCBK-S",
            messageBody = """
                Credit Alert!
                Rs.3.00 credited to HDFC Bank A/c XX9591
                on 05-10-26 from VPA 9205971964@axl
                (UPI 797836991220)
            """.trimIndent()
        )

        assertEquals(TransactionType.CREDIT, axisCredit?.transactionType)
        assertEquals(AccountType.BANK_ACCOUNT, axisCredit?.accountType)
        assertEquals("3370", axisCredit?.accountLastFour)
        assertEquals("452194820030", axisCredit?.refNumber)

        assertEquals(TransactionType.CREDIT, hdfcCredit?.transactionType)
        assertEquals(AccountType.BANK_ACCOUNT, hdfcCredit?.accountType)
        assertEquals("9591", hdfcCredit?.accountLastFour)
        assertEquals("797836991220", hdfcCredit?.refNumber)
    }


}
