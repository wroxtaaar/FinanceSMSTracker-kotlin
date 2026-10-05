package com.example.financesmstracker.transfer

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalTransferCandidateDetectorTest {

    @Test
    fun detectsOppositeBankAccountsWithSameReferenceEvenOutsideTimeWindow() {
        val debit = transaction(
            id = 1L,
            amountPaise = 300L,
            type = TransactionType.DEBIT,
            bank = "AXIS",
            lastFour = "3370",
            timestamp = 1_000L,
            ref = "UPI/P2A/185534369134/ABDUL WASIQ"
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 300L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 60_000L,
            ref = "185534369134"
        )

        val candidates = InternalTransferCandidateDetector.findCandidates(
            listOf(debit, credit),
            windowMillis = 1_000L
        )

        assertEquals(1, candidates.size)
        assertEquals(1L, candidates.single().debit.id)
        assertEquals(2L, candidates.single().credit.id)
    }

    @Test
    fun detectsOppositeBankAccountsWithSameAmountAndCurrencyWithinWindow() {
        val debit = transaction(
            id = 1L,
            amountPaise = 200L,
            type = TransactionType.DEBIT,
            bank = "AXIS",
            lastFour = "3370",
            timestamp = 1_000L
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 200L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 4_000L
        )

        val candidates = InternalTransferCandidateDetector.findCandidates(
            listOf(debit, credit),
            windowMillis = 5_000L
        )

        assertEquals(1, candidates.size)
        assertEquals(1L, candidates.single().debit.id)
        assertEquals(2L, candidates.single().credit.id)
        assertEquals(3_000L, candidates.single().timeDifferenceMillis)
    }

    @Test
    fun rejectsDifferentCurrency() {
        val debit = transaction(
            id = 1L,
            amountPaise = 200L,
            type = TransactionType.DEBIT,
            bank = "AXIS",
            lastFour = "3370",
            timestamp = 1_000L,
            currency = "INR"
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 200L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 2_000L,
            currency = "USD"
        )

        assertTrue(
            InternalTransferCandidateDetector.findCandidates(listOf(debit, credit)).isEmpty()
        )
    }

    @Test
    fun rejectsTransactionsOutsideWindowWhenReferenceIsMissing() {
        val debit = transaction(
            id = 1L,
            amountPaise = 200L,
            type = TransactionType.DEBIT,
            bank = "AXIS",
            lastFour = "3370",
            timestamp = 1_000L
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 200L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 20_000L
        )

        assertTrue(
            InternalTransferCandidateDetector.findCandidates(
                listOf(debit, credit),
                windowMillis = 5_000L
            ).isEmpty()
        )
    }

    @Test
    fun rejectsSameKnownBankAndAccount() {
        val debit = transaction(
            id = 1L,
            amountPaise = 200L,
            type = TransactionType.DEBIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 1_000L
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 200L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 2_000L
        )

        assertTrue(
            InternalTransferCandidateDetector.findCandidates(listOf(debit, credit)).isEmpty()
        )
    }

    @Test
    fun ignoresCreditCardsAndOnlyConsidersBankAccounts() {
        val debit = transaction(
            id = 1L,
            amountPaise = 200L,
            type = TransactionType.DEBIT,
            bank = "AXIS",
            lastFour = "3370",
            timestamp = 1_000L,
            accountType = AccountType.CREDIT_CARD
        )
        val credit = transaction(
            id = 2L,
            amountPaise = 200L,
            type = TransactionType.CREDIT,
            bank = "HDFC",
            lastFour = "9591",
            timestamp = 2_000L
        )

        assertTrue(
            InternalTransferCandidateDetector.findCandidates(listOf(debit, credit)).isEmpty()
        )
    }

    private fun transaction(
        id: Long,
        amountPaise: Long,
        type: TransactionType,
        bank: String,
        lastFour: String,
        timestamp: Long,
        currency: String = "INR",
        accountType: AccountType = AccountType.BANK_ACCOUNT,
        ref: String? = null
    ) = Transaction(
        id = id,
        amountPaise = amountPaise,
        currency = currency,
        transactionType = type,
        paymentMethod = PaymentMethod.UPI,
        accountType = accountType,
        bank = bank,
        merchantName = null,
        payeeId = null,
        accountLastFour = lastFour,
        refNumber = ref,
        timestamp = timestamp,
        smsHash = "hash-$id",
        category = null,
        parserConfidence = 1.0f
    )
}
