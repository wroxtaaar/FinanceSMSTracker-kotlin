package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardPaymentMatchTest {
    private val t0 = 1_800_000_000_000L
    private fun credit(id: Long, hash: String, amount: Long = 100_000, last4: String = "8001", ts: Long = t0) =
        Transaction(
            id = id, amountPaise = amount, transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.UPI, accountType = AccountType.CREDIT_CARD, bank = "ICICI",
            merchantName = "Credit Card Bill Payment", payeeId = null, accountLastFour = last4,
            refNumber = null, timestamp = ts, smsHash = hash, category = "TRANSFER", parserConfidence = 1f
        )

    @Test
    fun findsTheMarkedPaymentWhenTheCardConfirmationArrivesLater() {
        val marked = credit(1, "manual-card-payment:9")
        val found = CardPaymentMatch.findExisting(listOf(marked), 100_000, "8001", t0 + 3_600_000, onlyManual = true)
        assertEquals(1L, found?.id)
    }

    @Test
    fun ignoresDifferentAmountCardOrAFarAwayDate() {
        val marked = credit(1, "manual-card-payment:9")
        assertNull(CardPaymentMatch.findExisting(listOf(marked), 99_000, "8001", t0))
        assertNull(CardPaymentMatch.findExisting(listOf(marked), 100_000, "1234", t0))
        assertNull(CardPaymentMatch.findExisting(listOf(marked), 100_000, "8001", t0 + 4L * 86_400_000))
    }

    @Test
    fun theSmsSideOnlyMatchesMarkedRowsButTheButtonMatchesAnyCardCredit() {
        val issuer = credit(2, "card-bill-payment:abc")
        assertNull(CardPaymentMatch.findExisting(listOf(issuer), 100_000, "8001", t0, onlyManual = true))
        assertEquals(2L, CardPaymentMatch.findExisting(listOf(issuer), 100_000, "8001", t0)?.id)
    }

    @Test
    fun aConfirmationWithoutCardDigitsMatchesByIssuer() {
        val issuer = credit(3, "card-bill-payment:x")
        assertEquals(3L, CardPaymentMatch.findExisting(listOf(issuer), 100_000, null, t0, bank = "icici")?.id)
        assertNull(CardPaymentMatch.findExisting(listOf(issuer), 100_000, null, t0, bank = "SBI"))
        assertNull(CardPaymentMatch.findExisting(listOf(issuer), 100_000, null, t0))
    }
}
