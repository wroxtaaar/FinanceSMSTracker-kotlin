package com.example.financesmstracker.integration

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.evidence.EvidenceStatus
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.SourceType
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleIdsTest {
    private val scheme = OracleIds.Scheme(prefix = "a1b2c3", legacyMaxTransactionId = 40L, legacyMaxEvidenceId = 55L)

    @Test
    fun rowsThatExistedBeforeTheSchemeKeepTheirBareIds() {
        assertEquals("1", scheme.transactionId(1L))
        assertEquals("40", scheme.transactionId(40L))
        assertEquals("55", scheme.evidenceId(55L))
    }

    @Test
    fun newRowsGetAnInstallPrefixedId() {
        assertEquals("a1b2c3-41", scheme.transactionId(41L))
        assertEquals("a1b2c3-e56", scheme.evidenceId(56L))
    }

    @Test
    fun aFreshInstallPrefixesEveryRowSoItCannotCollideWithOlderHistory() {
        val fresh = OracleIds.Scheme("zz9y8x", 0L, 0L)
        assertEquals("zz9y8x-1", fresh.transactionId(1L))
        assertNotEquals(scheme.transactionId(1L), fresh.transactionId(1L))
    }

    @Test
    fun twoInstallsNeverShareAPrefix() {
        assertNotEquals(OracleIds.newPrefix(), OracleIds.newPrefix())
        assertEquals(6, OracleIds.newPrefix().length)
    }

    @Test
    fun bareSchemeKeepsTodaysBehaviour() {
        assertEquals("123", OracleIds.BARE.transactionId(123L))
        assertEquals("9", OracleIds.BARE.evidenceId(9L))
    }

    @Test
    fun voidIdUsesSchemeForPhoneRowsAndRemoteIdForOracleRows() {
        assertEquals("a1b2c3-77", FinanceSyncBridge.oracleVoidId(77L, "notification:x", scheme))
        assertEquals("gmail:abc", FinanceSyncBridge.oracleVoidId(77L, "oracle:gmail:gmail:abc", scheme))
        assertEquals("12", FinanceSyncBridge.oracleVoidId(12L, null, scheme))
    }

    @Test
    fun syncPayloadCarriesTheServerIdsForTransactionsAndEvidence() {
        val tx = Transaction(
            id = 77L, amountPaise = 1000L, transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UPI, accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC", merchantName = "M", payeeId = null, accountLastFour = "9591",
            refNumber = null, timestamp = 1L, smsHash = "h", category = "OTHER", parserConfidence = 0.9f
        )
        val ev = SourceEvidence(
            id = 90L, sourceType = SourceType.SMS, sourceKey = "k", receivedAt = 1L,
            transactionId = 77L, amountPaise = 1000L, currency = "INR", direction = "DEBIT",
            bankProvider = "HDFC", accountLastFour = "9591", reference = null,
            contentHash = "c", confidence = 0.9f, status = EvidenceStatus.MATCHED
        )

        val payload = FinanceSyncPayload.build(
            listOf(FinanceSyncMapper.toSyncTransaction(tx, scheme)),
            listOf(FinanceSyncMapper.toSyncEvidence(ev, scheme))
        )

        assertTrue(payload.contains("\"id\":\"a1b2c3-77\""))
        assertTrue(payload.contains("\"id\":\"a1b2c3-e90\""))
        assertTrue(payload.contains("\"transactionId\":\"a1b2c3-77\""))
        assertTrue(payload.contains("\"matchedTransactionId\":\"a1b2c3-77\""))

        val legacy = FinanceSyncPayload.build(
            listOf(FinanceSyncMapper.toSyncTransaction(tx)),
            emptyList()
        )
        assertTrue(legacy.contains("\"id\":\"77\""))
    }
}
