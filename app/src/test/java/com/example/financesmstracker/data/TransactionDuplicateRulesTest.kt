package com.example.financesmstracker.data

import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.TransactionReferenceExtractor
import com.example.financesmstracker.util.TransactionReferenceNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionDuplicateRulesTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L

    private fun tx(
        id: Long,
        hash: String,
        amountPaise: Long = 182_000L,
        type: TransactionType = TransactionType.DEBIT,
        accountType: AccountType = AccountType.CREDIT_CARD,
        bank: String? = null,
        last4: String? = "9206",
        ref: String? = null,
        merchant: String? = null,
        payment: PaymentMethod = PaymentMethod.UNKNOWN,
        timestamp: Long = t0
    ) = Transaction(
        id = id,
        amountPaise = amountPaise,
        transactionType = type,
        paymentMethod = payment,
        accountType = accountType,
        bank = bank,
        merchantName = merchant,
        payeeId = null,
        accountLastFour = last4,
        refNumber = ref,
        timestamp = timestamp,
        smsHash = hash,
        category = "GROCERIES",
        parserConfidence = 0.9f
    )

    // --- reference residue ("...601.FOR any queries") ---------------------------------

    @Test
    fun normalizerDropsSentenceResidueGluedOntoNumericReference() {
        assertEquals("665513407363", TransactionReferenceNormalizer.normalize("665513407363.FOR"))
        assertEquals("270905172601", TransactionReferenceNormalizer.normalize("270905172601.FOR"))
        assertEquals("AXIS.BANK", TransactionReferenceNormalizer.normalize("AXIS.BANK"))
        assertEquals("715732131571", TransactionReferenceNormalizer.normalize("715732131571"))
    }

    @Test
    fun extractorReturnsCleanReferenceFromEmailText() {
        assertEquals(
            "270905172601",
            TransactionReferenceExtractor.extract("UPI Reference No: 270905172601.FOR any queries, call us")
        )
        assertEquals(
            "665513407363",
            TransactionReferenceExtractor.extract("Transaction Reference 665513407363.FOR")
        )
    }

    @Test
    fun strictRuleTreatsResidueReferenceAsSameReference() {
        val sms = tx(1, "sms:1", accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", last4 = "9591",
            ref = "665513407363", payment = PaymentMethod.UPI, merchant = "DEOBAND")
        val notification = tx(2, "notification:2", accountType = AccountType.BANK_ACCOUNT, bank = "HDFC",
            last4 = "9591", ref = "665513407363.FOR", merchant = "DEOBAND", timestamp = t0 + minute)
        assertTrue(TransactionDuplicateRules.safeDuplicates(sms, notification))
    }

    // --- card spend: SMS and notification minutes apart, no reference ------------------

    @Test
    fun cardSpendSmsAndLateNotificationAreOnePair() {
        val sms = tx(1, "sms:1", bank = "AXIS", merchant = "RAJ", payment = PaymentMethod.CARD)
        val notification = tx(2, "notification:2", timestamp = t0 + 7 * minute)
        val pairs = TransactionDuplicateRules.selectLoosePairs(listOf(notification, sms))
        assertEquals(1, pairs.size)
        assertEquals(setOf(1L, 2L), setOf(pairs[0].first.id, pairs[0].second.id))
    }

    @Test
    fun cardSpendOutsideFifteenMinutesIsNotPaired() {
        val sms = tx(1, "sms:1", bank = "AXIS")
        val notification = tx(2, "notification:2", timestamp = t0 + 16 * minute)
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(sms, notification)).isEmpty())
    }

    @Test
    fun twoRealSameAmountSmsRowsAreNeverMerged() {
        val a = tx(1, "sms:1", bank = "AXIS")
        val b = tx(2, "sms:2", bank = "AXIS", timestamp = t0 + 5 * minute)
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(a, b)).isEmpty())
    }

    @Test
    fun ambiguousNotificationIsLeftAlone() {
        val a = tx(1, "sms:1", bank = "AXIS")
        val b = tx(2, "sms:2", bank = "AXIS", timestamp = t0 + 2 * minute)
        val notification = tx(3, "notification:3", timestamp = t0 + 3 * minute)
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(a, b, notification)).isEmpty())
    }

    @Test
    fun differentLastFourIsNotPaired() {
        val sms = tx(1, "sms:1", bank = "AXIS", last4 = "9206")
        val notification = tx(2, "notification:2", last4 = "1111", timestamp = t0 + minute)
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(sms, notification)).isEmpty())
    }

    // --- Axis credit: e-mail has the reference, notification has none, neither has last-4 ---

    private fun axisCredit(id: Long, hash: String, ref: String?, ts: Long) = tx(
        id, hash, amountPaise = 500L, type = TransactionType.CREDIT,
        accountType = AccountType.BANK_ACCOUNT, bank = "AXIS", last4 = null, ref = ref, timestamp = ts
    )

    @Test
    fun axisEmailAndNotificationCreditArePaired() {
        val email = axisCredit(1, "oracle:gmail:abc", "715732131571", t0 + 2 * minute)
        val notification = axisCredit(2, "notification:xyz", null, t0)
        val pairs = TransactionDuplicateRules.selectLoosePairs(listOf(email, notification))
        assertEquals(1, pairs.size)
    }

    @Test
    fun axisCreditsWithDifferentReferencesAreKept() {
        val a = axisCredit(1, "oracle:gmail:a", "111111111111", t0)
        val b = axisCredit(2, "oracle:gmail:b", "222222222222", t0 + minute)
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(a, b)).isEmpty())
    }

    @Test
    fun oppositeSideOfSelfTransferIsNeverPaired() {
        val hdfcDebit = tx(1, "sms:1", amountPaise = 500L, type = TransactionType.DEBIT,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", last4 = "9591",
            ref = "715732131571", payment = PaymentMethod.UPI)
        val axisCredit = axisCredit(2, "oracle:gmail:abc", "715732131571", t0)
        assertFalse(TransactionDuplicateRules.safeDuplicates(hdfcDebit, axisCredit))
        assertTrue(TransactionDuplicateRules.selectLoosePairs(listOf(hdfcDebit, axisCredit)).isEmpty())
    }
}
