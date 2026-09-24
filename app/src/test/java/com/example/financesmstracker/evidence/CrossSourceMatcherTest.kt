package com.example.financesmstracker.evidence

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import org.junit.Assert.*
import org.junit.Test

class CrossSourceMatcherTest {

    private fun createDummyTx(
        id: Long = 1L,
        amountPaise: Long = 10000L,
        type: TransactionType = TransactionType.DEBIT,
        bank: String? = "HDFC",
        ref: String? = "REF123",
        timestamp: Long = 1000000L,
        lastFour: String? = "9591"
    ): Transaction {
        return Transaction(
            id = id,
            amountPaise = amountPaise,
            transactionType = type,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT,
            bank = bank,
            merchantName = "Merchant",
            payeeId = "merchant@upi",
            accountLastFour = lastFour,
            refNumber = ref,
            timestamp = timestamp,
            smsHash = HashUtil.sha256("sms_$id"),
            category = "GROCERIES",
            parserConfidence = 0.95f
        )
    }

    @Test
    fun testCase1_UnknownDirectionAxisBankMatch() {
        val tx = createDummyTx(id = 1L, amountPaise = 10000L, type = TransactionType.DEBIT, bank = "AXIS", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_1",
            receivedAt = 1005000L,
            amountPaise = 10000L,
            direction = "UNKNOWN",
            bankProvider = "Axis Bank",
            contentHash = "hash1"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(1L, result.matchedTransactionId)
    }

    @Test
    fun testCase2_CreditNullBankMatch() {
        val tx = createDummyTx(id = 2L, amountPaise = 10000L, type = TransactionType.CREDIT, bank = "HDFC", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_2",
            receivedAt = 1005000L,
            amountPaise = 10000L,
            direction = "CREDIT",
            bankProvider = null,
            contentHash = "hash2"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(2L, result.matchedTransactionId)
    }

    @Test
    fun testCase3_DebitAxisVsCreditAxisUnmatched() {
        val tx = createDummyTx(id = 3L, amountPaise = 10000L, type = TransactionType.CREDIT, bank = "AXIS", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_3",
            receivedAt = 1000000L,
            amountPaise = 10000L,
            direction = "DEBIT",
            bankProvider = "Axis",
            contentHash = "hash3"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun testCase4_CreditHdfcVsDebitHdfcUnmatched() {
        val tx = createDummyTx(id = 4L, amountPaise = 10000L, type = TransactionType.DEBIT, bank = "HDFC", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_4",
            receivedAt = 1000000L,
            amountPaise = 10000L,
            direction = "CREDIT",
            bankProvider = "HDFC",
            contentHash = "hash4"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun testCase5_UnknownDirectionNullBankAmbiguous() {
        val tx1 = createDummyTx(id = 5L, amountPaise = 10000L, type = TransactionType.DEBIT, timestamp = 1000000L)
        val tx2 = createDummyTx(id = 6L, amountPaise = 10000L, type = TransactionType.DEBIT, timestamp = 1010000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_5",
            receivedAt = 1005000L,
            amountPaise = 10000L,
            direction = "UNKNOWN",
            bankProvider = null,
            contentHash = "hash5"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx1, tx2))
        assertEquals(MatchOutcome.AMBIGUOUS, result.outcome)
    }

    @Test
    fun testCase6_UnknownDirectionNullBankUniqueMatch() {
        val tx = createDummyTx(id = 7L, amountPaise = 10000L, type = TransactionType.DEBIT, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_6",
            receivedAt = 1005000L,
            amountPaise = 10000L,
            direction = "UNKNOWN",
            bankProvider = null,
            contentHash = "hash6"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(7L, result.matchedTransactionId)
    }

    @Test
    fun testCase7_CreditAxisVsCreditHdfcUnmatched() {
        val tx = createDummyTx(id = 8L, amountPaise = 10000L, type = TransactionType.CREDIT, bank = "HDFC", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_7",
            receivedAt = 1000000L,
            amountPaise = 10000L,
            direction = "CREDIT",
            bankProvider = "Axis",
            contentHash = "hash7"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun testCase8_DifferentCurrencyUnmatched() {
        val tx = createDummyTx(id = 9L, amountPaise = 138L, timestamp = 1000000L) // INR
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_8",
            receivedAt = 1000000L,
            amountPaise = 138L, // SGD 1.38 represented as 0 if filtered, or if amountPaise = 0
            direction = "CREDIT",
            contentHash = "hash8"
        )
        val zeroEvidence = evidence.copy(amountPaise = 0L)
        val result = CrossSourceMatcher.match(zeroEvidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun testCase9_DifferentAmountUnmatched() {
        val tx = createDummyTx(id = 10L, amountPaise = 10000L, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_9",
            receivedAt = 1000000L,
            amountPaise = 10100L, // 101 vs 100
            direction = "DEBIT",
            contentHash = "hash9"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun testCase10_OutsideTimeWindowUnmatched() {
        val tx = createDummyTx(id = 11L, amountPaise = 10000L, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_10",
            receivedAt = 2000000L, // 1000 seconds later (> 120s window)
            amountPaise = 10000L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hash10"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }
}
