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
    fun hdfcNotificationDoesNotMistakeAxisVpaForIssuingBank() {
        val result = GmailNotificationParser.parse(
            "HDFC Bank InstaAlerts",
            "Rs.7773.00 is debited from your account ending 9591 towards VPA cred.club@axisb (CRED Club) on 02-10-26. UPI transaction reference no.: 664110896238.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(TransactionType.DEBIT, result!!.transactionType)
        assertEquals("HDFC", result.bank)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals(PaymentMethod.UPI, result.paymentMethod)
        assertEquals("9591", result.accountLastFour)
        assertEquals("cred.club@axisb", result.payeeId)
        assertEquals("664110896238", result.refNumber)
    }

    @Test
    fun axisBankCreditIsNotChangedByCreditCardFooter() {
        val result = GmailNotificationParser.parse(
            "Axis Bank Alerts",
            "INR 3.00 was credited to your A/c.\nThe transaction is subject to your credit card payment terms.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(300L, result!!.amountPaise)
        assertEquals(TransactionType.CREDIT, result.transactionType)
        assertEquals(AccountType.BANK_ACCOUNT, result.accountType)
        assertEquals(PaymentMethod.UNKNOWN, result.paymentMethod)
        assertEquals("AXIS", result.bank)
    }

    @Test
    fun parsesAxisCreditCardSpendNotificationAsDebit() {
        val result = GmailNotificationParser.parse(
            "Axis Bank Alerts",
            "INR 100 spent on credit card no. XX9206\n02-10-2026\nFurt shop\nAvl Limit: INR 129893.14",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(10000L, result!!.amountPaise)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(AccountType.CREDIT_CARD, result.accountType)
        assertEquals(PaymentMethod.CARD, result.paymentMethod)
        assertEquals("AXIS", result.bank)
        assertEquals("9206", result.accountLastFour)
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
    @Test
    fun parsesAxisUpiReferenceFromNotificationPayload() {
        val result = GmailNotificationParser.parse(
            "Axis Bank Alerts",
            "INR 10.00 was debited from your A/c. UPI/P2A/911389419630/ABDUL WASIQ",
            null,
            null
        )

        assertNotNull(result)
        assertEquals("911389419630", result!!.refNumber)
    }


    @Test
    fun foreignCurrencySpendIsNotReplacedByAvailableLimit() {
        val result = GmailNotificationParser.parse(
            "Axis Bank",
            "Spent USD 205.7 using Axis Bank Credit Card no. XX1175 on 21-10-26 22:19:26 IST at UZAIRWAYS*C. " +
                "Avl limit: INR 61312.07. Not you? SMS BLOCK 1175 to +919951860002.",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(20570L, result!!.amountPaise)
        assertEquals("USD", result.currency)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(AccountType.CREDIT_CARD, result.accountType)
        assertEquals("1175", result.accountLastFour)
    }

    @Test
    fun alertWithOnlyAnAvailableLimitIsNotATransaction() {
        val result = GmailNotificationParser.parse(
            "Axis Bank",
            "Your card limit update. Avl limit: INR 61312.07.",
            null,
            null
        )
        assertNull(result)
    }

    @Test
    fun parsesHsbcCardUsdEmailNotification() {
        val result = GmailNotificationParser.parse(
            "HSBC",
            "Dear Customer, We're writing to confirm that your HSBC Credit Card xx5145 was used for a transaction of USD 4.00 at HUGGINGFACE on 21/10/26. Available limit: INR 169620.11 Amount due: INR 379.89",
            null,
            null
        )

        assertNotNull(result)
        assertEquals(400L, result!!.amountPaise)
        assertEquals("USD", result.currency)
        assertEquals(TransactionType.DEBIT, result.transactionType)
        assertEquals(AccountType.CREDIT_CARD, result.accountType)
        assertEquals("HSBC", result.bank)
        assertEquals("5145", result.accountLastFour)
    }
}
