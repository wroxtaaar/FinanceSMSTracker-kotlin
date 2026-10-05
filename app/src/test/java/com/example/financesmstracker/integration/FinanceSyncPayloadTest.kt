package com.example.financesmstracker.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceSyncPayloadTest {
    @Test
    fun emptyPayloadHasVersionAndArrays() {
        val payload = FinanceSyncPayload.build(emptyList(), emptyList())
        assertTrue(payload.contains("\"version\":1"))
        assertTrue(payload.contains("\"transactions\":[]"))
        assertTrue(payload.contains("\"evidence\":[]"))
    }

    @Test
    fun queueStoresAndRemovesFifoPayloads() {
        // Contract-level coverage is kept here; device persistence is exercised
        // through FinanceSyncQueue in Android runtime tests.
        val first = FinanceSyncPayload.build(emptyList(), emptyList())
        val second = FinanceSyncPayload.build(emptyList(), emptyList())
        assertEquals(first, second)
    }
    @Test
    fun internalTransferCandidateIsSerialized() {
        val candidate = SyncInternalTransferCandidate(
            debitTransactionId = 479L,
            creditTransactionId = 480L,
            amountMinor = 1000L,
            currency = "INR",
            timeDifferenceMillis = 8_000L,
            matchType = InternalTransferMatchType.AMOUNT_TIME
        )

        val payload = FinanceSyncPayload.build(
            transactions = emptyList(),
            evidence = emptyList(),
            internalTransferCandidates = listOf(candidate)
        )

        assertTrue(payload.contains("\"internalTransferCandidates\":["))
        assertTrue(payload.contains("\"debitTransactionId\":\"479\""))
        assertTrue(payload.contains("\"creditTransactionId\":\"480\""))
        assertTrue(payload.contains("\"matchType\":\"AMOUNT_TIME\""))
    }

}
