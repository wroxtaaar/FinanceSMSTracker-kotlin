package com.example.financesmstracker.gmail

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailNotificationParserTest {

    @Test
    fun parsesHdfcUpiDebitNotification() {
        val result = GmailNotificationParser.parse(
            "HDFC Bank InstaAlerts",
            "Rs.3.00 is debited from your account ending 9591 towards VPA 9205971964@axl (ABDUL WASIQ) on 02-10-26. UPI transaction reference no.: 908490065795.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(300L, result!!.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("HDFC", result.bank)
        assertEquals("9591", result.accountLastFour)
        assertEquals("9205971964@axl", result.payeeId)
        assertEquals("908490065795", result.refNumber)
    }

    @Test
    fun parsesAxisCreditNotification() {
        val result = GmailNotificationParser.parse(
            "Axis Bank Alerts",
            "INR 3.00 was credited to your A/c.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(300L, result!!.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals("AXIS", result.bank)
        assertNull(result.accountLastFour)
        assertNull(result.refNumber)
    }

    @Test
    fun rejectsNonTransactionNotification() {
        val result = GmailNotificationParser.parse(
            "Axis Bank Alerts",
            "Your monthly statement is ready.",
            null,
            null
        )

        assertTrue(result == null)
    }
}
