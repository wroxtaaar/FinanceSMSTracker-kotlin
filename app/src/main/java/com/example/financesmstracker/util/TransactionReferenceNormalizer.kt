package com.example.financesmstracker.util

/**
 * Normalizes bank/UPI transaction references to the stable reference value.
 *
 * Axis account alerts can expose a reference as:
 *   UPI/P2A/<RRN>/COUNTERPARTY/BANK/...
 * while HDFC alerts usually expose the RRN directly.
 *
 * The RRN/reference is the transaction identity; transport-specific wrappers
 * must not make two representations compare differently.
 */
object TransactionReferenceNormalizer {
    private val axisTransactionInfoRegex = Regex(
        """(?i)^\s*UPI/[^/\s]+/([^/\s]+)"""
    )

    fun normalize(value: String?): String? {
        val raw = value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
            ?: return null

        val withoutLabel = raw
            .replace(Regex("""(?i)^\s*Transaction\s+Info\s*:\s*"""), "")
            .trim()

        val core = axisTransactionInfoRegex.find(withoutLabel)?.groupValues?.getOrNull(1)
            ?: withoutLabel

        return core
            .trim()
            .trim('.', ',', ';', ':', ')', ']')
            .takeIf { it.isNotBlank() }
            ?.uppercase()
    }
}
