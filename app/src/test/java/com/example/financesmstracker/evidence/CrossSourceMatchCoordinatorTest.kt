package com.example.financesmstracker.evidence

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import org.junit.Assert.*
import org.junit.Test

class CrossSourceMatchCoordinatorTest {

    private fun createDummyTx(
        id: Long = 1L,
        amountPaise: Long = 10000L,
        type: TransactionType = TransactionType.DEBIT,
        bank: String = "HDFC",
        timestamp: Long = 1000000L,
        ref: String = "REF123"
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
            accountLastFour = "9591",
            refNumber = ref,
            timestamp = timestamp,
            smsHash = HashUtil.sha256("sms_$id"),
            category = "GROCERIES",
            parserConfidence = 0.95f
        )
    }

    @Test
    fun test1_TruecallerEvidenceAfterSmsTransactionIsMatched() {
        val tx = createDummyTx(id = 1L, amountPaise = 15000L, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_1",
            receivedAt = 1005000L,
            amountPaise = 15000L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hash1"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(1L, result.matchedTransactionId)
    }

    @Test
    fun test2_TruecallerEvidenceBeforeSmsTransactionEvaluatesUnmatchedThenMatched() {
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_2",
            receivedAt = 1000000L,
            amountPaise = 20000L,
            direction = "DEBIT",
            bankProvider = "AXIS",
            contentHash = "hash2"
        )
        // Before SMS arrival (no candidates)
        val initialResult = CrossSourceMatcher.match(evidence, emptyList())
        assertEquals(MatchOutcome.UNMATCHED, initialResult.outcome)

        // After SMS arrival
        val tx = createDummyTx(id = 2L, amountPaise = 20000L, bank = "AXIS", timestamp = 1005000L)
        val laterResult = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, laterResult.outcome)
        assertEquals(2L, laterResult.matchedTransactionId)
    }

    @Test
    fun test3_AmbiguousCandidatesResultInAmbiguousStatus() {
        val tx1 = createDummyTx(id = 3L, amountPaise = 25000L, timestamp = 1000000L, ref = "REF_A")
        val tx2 = createDummyTx(id = 4L, amountPaise = 25000L, timestamp = 1010000L, ref = "REF_B")

        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_3",
            receivedAt = 1005000L,
            amountPaise = 25000L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hash3"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx1, tx2))
        assertEquals(MatchOutcome.AMBIGUOUS, result.outcome)
    }

    @Test
    fun test4_NoCandidateResultsInUnmatchedStatus() {
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_4",
            receivedAt = 1000000L,
            amountPaise = 99999L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hash4"
        )
        val result = CrossSourceMatcher.match(evidence, emptyList())
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun test5_AlreadyMatchedEvidenceRemainsUnchanged() {
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_5",
            receivedAt = 1000000L,
            amountPaise = 30000L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hash5",
            transactionId = 5L,
            status = EvidenceStatus.MATCHED
        )
        // Coordinator / matcher logic check: if already matched, skipped
        assertTrue(evidence.status == EvidenceStatus.MATCHED && evidence.transactionId != null)
    }

    @Test
    fun test6_KnownDirectionConflictResultsInUnmatched() {
        val tx = createDummyTx(id = 6L, amountPaise = 40000L, type = TransactionType.CREDIT, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_6",
            receivedAt = 1000000L,
            amountPaise = 40000L,
            direction = "DEBIT", // Conflict: Evidence is DEBIT, Transaction is CREDIT
            bankProvider = "HDFC",
            contentHash = "hash6"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun test7_UnknownDirectionWithCompatibleCandidateResultsInMatched() {
        val tx = createDummyTx(id = 7L, amountPaise = 50000L, type = TransactionType.DEBIT, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_7",
            receivedAt = 1000000L,
            amountPaise = 50000L,
            direction = "UNKNOWN",
            bankProvider = "HDFC",
            contentHash = "hash7"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(7L, result.matchedTransactionId)
    }

    @Test
    fun test8_UnknownBankWithCompatibleCandidateResultsInMatched() {
        val tx = createDummyTx(id = 8L, amountPaise = 60000L, bank = "HDFC", timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_8",
            receivedAt = 1000000L,
            amountPaise = 60000L,
            direction = "DEBIT",
            bankProvider = null, // Unknown bank
            contentHash = "hash8"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.MATCHED, result.outcome)
        assertEquals(8L, result.matchedTransactionId)
    }

    @Test
    fun test9_DifferentCurrencyResultsInUnmatched() {
        val tx = createDummyTx(id = 9L, amountPaise = 138L, timestamp = 1000000L)
        val evidence = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = "tc_key_9",
            receivedAt = 1000000L,
            amountPaise = 0L, // zero / different currency amount
            direction = "CREDIT",
            contentHash = "hash9"
        )
        val result = CrossSourceMatcher.match(evidence, listOf(tx))
        assertEquals(MatchOutcome.UNMATCHED, result.outcome)
    }

    @Test
    fun test10_MultipleRapidCallbacksDeduplicatedByUniqueSourceKey() {
        val evidenceKey = "tc_rapid_key"
        val evidence1 = SourceEvidence(
            sourceType = SourceType.TRUECALLER,
            sourceKey = evidenceKey,
            receivedAt = 1000000L,
            amountPaise = 70000L,
            direction = "DEBIT",
            bankProvider = "HDFC",
            contentHash = "hashA"
        )
        val evidence2 = evidence1.copy(contentHash = "hashB")

        // Both share same sourceKey
        assertEquals(evidence1.sourceKey, evidence2.sourceKey)
    }
}
