package com.example.financesmstracker.truecaller

import com.example.financesmstracker.util.HashUtil
import java.util.concurrent.ConcurrentHashMap

enum class DedupResult {
    NEW_NOTIFICATION,
    DUPLICATE_NOTIFICATION_UPDATE,
    FALLBACK_DUPLICATE,
    KEPT_SEPARATE
}

data class DedupEvaluation(
    val result: DedupResult,
    val keyFingerprint: String,
    val contentHashPrefix: String
)

class TruecallerDedupManager(private val ttlMillis: Long = 10_000L) {
    private val activeNotifications = ConcurrentHashMap<String, Long>()
    private val fallbackCache = ConcurrentHashMap<String, Long>()

    fun evaluate(
        key: String?,
        packageName: String?,
        id: Int,
        tag: String?,
        postTime: Long,
        amountPaise: Long,
        direction: NotificationDirection,
        bank: String?,
        contentHash: String
    ): DedupEvaluation {
        val now = System.currentTimeMillis()
        cleanExpired(now)

        val safeKey = key?.trim().takeIf { !it.isNullOrBlank() }
        val safePkg = packageName?.trim() ?: "unknown"
        val keyFingerprint = if (safeKey != null) {
            HashUtil.sha256(safeKey).take(12)
        } else {
            "no_key"
        }
        val hashPrefix = contentHash.take(8)

        if (safeKey != null) {
            val lastSeen = activeNotifications[safeKey]
            if (lastSeen != null && (now - lastSeen) < ttlMillis) {
                activeNotifications[safeKey] = now
                return DedupEvaluation(DedupResult.DUPLICATE_NOTIFICATION_UPDATE, keyFingerprint, hashPrefix)
            } else {
                activeNotifications[safeKey] = now
                return DedupEvaluation(DedupResult.NEW_NOTIFICATION, keyFingerprint, hashPrefix)
            }
        } else {
            val fallbackFingerprint = "$safePkg|$id|${tag.orEmpty()}|$amountPaise|$direction|${bank.orEmpty()}"
            val lastSeenFallback = fallbackCache[fallbackFingerprint]
            if (lastSeenFallback != null && (now - lastSeenFallback) < ttlMillis) {
                fallbackCache[fallbackFingerprint] = now
                return DedupEvaluation(DedupResult.FALLBACK_DUPLICATE, keyFingerprint, hashPrefix)
            } else {
                fallbackCache[fallbackFingerprint] = now
                return DedupEvaluation(DedupResult.NEW_NOTIFICATION, keyFingerprint, hashPrefix)
            }
        }
    }

    private fun cleanExpired(now: Long) {
        if (activeNotifications.size > 200) {
            val iterator = activeNotifications.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((now - entry.value) > (ttlMillis * 2)) {
                    iterator.remove()
                }
            }
        }
        if (fallbackCache.size > 200) {
            val iterator = fallbackCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((now - entry.value) > (ttlMillis * 2)) {
                    iterator.remove()
                }
            }
        }
    }
}
