package com.example.financesmstracker.truecaller

import org.junit.Assert.*
import org.junit.Test

class TruecallerDedupManagerTest {

    @Test
    fun testSameKeySameAmountIsDuplicateUpdate() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val eval1 = manager.evaluate("key1", "com.truecaller", 1, null, 1000L, 200L, NotificationDirection.CREDIT, "HDFC", "hashA")
        val eval2 = manager.evaluate("key1", "com.truecaller", 1, null, 1050L, 200L, NotificationDirection.CREDIT, "HDFC", "hashB")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.DUPLICATE_NOTIFICATION_UPDATE, eval2.result)
    }

    @Test
    fun testSameKeyChangedContentHashIsDuplicateUpdate() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val eval1 = manager.evaluate("key1", "com.truecaller", 1, null, 1000L, 200L, NotificationDirection.CREDIT, "HDFC", "hashA")
        val eval2 = manager.evaluate("key1", "com.truecaller", 1, null, 1020L, 200L, NotificationDirection.CREDIT, "HDFC", "hashChanged")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.DUPLICATE_NOTIFICATION_UPDATE, eval2.result)
    }

    @Test
    fun testDifferentKeysSameAmountKeptSeparate() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val eval1 = manager.evaluate("key1", "com.truecaller", 1, null, 1000L, 200L, NotificationDirection.CREDIT, "HDFC", "hashA")
        val eval2 = manager.evaluate("key2", "com.truecaller", 2, null, 1010L, 200L, NotificationDirection.CREDIT, "HDFC", "hashB")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.NEW_NOTIFICATION, eval2.result)
    }

    @Test
    fun testSameKeyAfterTtlExpiresIsNewObservation() {
        val manager = TruecallerDedupManager(ttlMillis = 100L)
        val eval1 = manager.evaluate("key1", "com.truecaller", 1, null, 1000L, 200L, NotificationDirection.CREDIT, "HDFC", "hashA")
        
        Thread.sleep(150L)

        val eval2 = manager.evaluate("key1", "com.truecaller", 1, null, 1200L, 200L, NotificationDirection.CREDIT, "HDFC", "hashB")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.NEW_NOTIFICATION, eval2.result)
    }

    @Test
    fun testDifferentKeyDifferentAmountSeparate() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val eval1 = manager.evaluate("key1", "com.truecaller", 1, null, 1000L, 200L, NotificationDirection.CREDIT, "HDFC", "hashA")
        val eval2 = manager.evaluate("key2", "com.truecaller", 2, null, 1000L, 500L, NotificationDirection.DEBIT, "Axis", "hashB")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.NEW_NOTIFICATION, eval2.result)
    }

    @Test
    fun testMissingNotificationKeyFallbackBehavior() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val eval1 = manager.evaluate(null, "com.truecaller", 10, "tagA", 1000L, 300L, NotificationDirection.DEBIT, "Axis", "hashA")
        val eval2 = manager.evaluate(null, "com.truecaller", 10, "tagA", 1020L, 300L, NotificationDirection.DEBIT, "Axis", "hashB")

        assertEquals(DedupResult.NEW_NOTIFICATION, eval1.result)
        assertEquals(DedupResult.FALLBACK_DUPLICATE, eval2.result)
    }

    @Test
    fun testMultipleRapidCallbacksForSameKeyOnlyOneNew() {
        val manager = TruecallerDedupManager(ttlMillis = 5000L)
        val evals = (1..5).map { i ->
            manager.evaluate("rapidKey", "com.truecaller", 1, null, 1000L + i * 10, 200L, NotificationDirection.CREDIT, "HDFC", "hash$i")
        }

        assertEquals(DedupResult.NEW_NOTIFICATION, evals[0].result)
        for (i in 1 until evals.size) {
            assertEquals(DedupResult.DUPLICATE_NOTIFICATION_UPDATE, evals[i].result)
        }
    }
}
