package com.example.financesmstracker.util

/**
 * Extracts the stable transaction reference from any bank/source representation.
 *
 * The reference/RRN/UTR is the transaction identity. Source-specific wrappers
 * such as Axis' UPI/P2A/... format are normalized before storage/comparison.
 */
object TransactionReferenceExtractor {
    private val labelledRegex = Regex(
        """(?i)\b(?:transaction\s+)?(?:reference|ref|utr|rrn|transaction\s*(?:id|no|number))\s*(?:no\.?|number)?\s*[:#=-]?\s*([A-Z0-9][A-Z0-9/_.-]{4,})"""
    )

    private val axisUpiRegex = Regex(
        """(?i)\bUPI/[^/\s]+/([A-Z0-9]{6,})"""
    )

    private val upiReferenceRegex = Regex(
        """(?i)\bUPI\s*(?:transaction\s*)?(?:reference|ref)?\s*(?:no\.?|number)?\s*[:#=-]?\s*([0-9]{8,})"""
    )

    private val parenthesizedUpiRegex = Regex(
        """(?i)\(\s*UPI\s+([0-9]{8,})\s*\)"""
    )

    fun extract(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val text = value.replace(' ', ' ').trim()
        if (text.isBlank()) return null

        val raw = listOf(
            labelledRegex.find(text)?.groupValues?.getOrNull(1),
            axisUpiRegex.find(text)?.groupValues?.getOrNull(1),
            upiReferenceRegex.find(text)?.groupValues?.getOrNull(1),
            parenthesizedUpiRegex.find(text)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() }

        return TransactionReferenceNormalizer.normalize(raw)
    }
}
