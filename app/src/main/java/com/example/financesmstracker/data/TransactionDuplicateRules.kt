package com.example.financesmstracker.data

import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.util.TransactionReferenceNormalizer
import kotlin.math.abs

/**
 * Pure rules deciding when two stored rows are the same ledger-side event.
 * Kept free of Android/SQLite so they can be unit tested on the JVM.
 *
 * [safeDuplicates] is the strict rule (exact reference, or one reference plus
 * full same-side identity within two minutes). [looseMirrorDuplicates] covers
 * what the strict rule cannot: an instant notification and the SMS / e-mail for
 * the same event arriving minutes apart without a shared reference. It is only
 * used through [selectLoosePairs], which accepts a pair only when each row has
 * exactly one such partner, so two real same-amount payments are never merged.
 */
object TransactionDuplicateRules {
    private const val STRICT_WINDOW_MILLIS = 2L * 60L * 1000L
    private const val MIRROR_WINDOW_MILLIS = 15L * 60L * 1000L

    fun normalizedBank(value: String?): String? =
        value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
            ?.uppercase()
            ?.removeSuffix(" BANK")
            ?.removeSuffix(" LTD")
            ?.removeSuffix(" LIMITED")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun normalizedMerchant(value: String?): String? =
        value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) && it != "-" }
            ?.uppercase()

    fun merchantsCompatible(first: String?, second: String?): Boolean {
        val a = normalizedMerchant(first)
        val b = normalizedMerchant(second)
        if (a == null || b == null) return true
        return a == b || a.contains(b) || b.contains(a)
    }

    private enum class Kind { NOTIFICATION, ORACLE_GMAIL, REAL }

    private fun kind(transaction: Transaction): Kind = when {
        transaction.smsHash.startsWith("notification:") -> Kind.NOTIFICATION
        transaction.smsHash.startsWith("oracle:gmail:") -> Kind.ORACLE_GMAIL
        else -> Kind.REAL
    }

    private fun sameEventShape(first: Transaction, second: Transaction): Boolean =
        first.amountPaise == second.amountPaise &&
            first.currency.equals(second.currency, ignoreCase = true) &&
            first.transactionType == second.transactionType &&
            first.accountType == second.accountType

    private fun paymentCompatible(first: Transaction, second: Transaction): Boolean =
        first.paymentMethod == PaymentMethod.UNKNOWN ||
            second.paymentMethod == PaymentMethod.UNKNOWN ||
            first.paymentMethod == second.paymentMethod

    fun safeDuplicates(first: Transaction, second: Transaction): Boolean {
        if (!sameEventShape(first, second)) return false

        val firstBank = normalizedBank(first.bank)
        val secondBank = normalizedBank(second.bank)
        if (firstBank != null && secondBank != null && firstBank != secondBank) return false

        val firstLast4 = first.accountLastFour?.trim().orEmpty()
        val secondLast4 = second.accountLastFour?.trim().orEmpty()
        if (firstLast4.isNotEmpty() && secondLast4.isNotEmpty() && firstLast4 != secondLast4) return false

        val firstRef = TransactionReferenceNormalizer.normalize(first.refNumber)
        val secondRef = TransactionReferenceNormalizer.normalize(second.refNumber)

        if (firstRef != null && secondRef != null) {
            return firstRef == secondRef
        }

        // One source may expose the RRN while another does not. Only merge
        // that case when the complete same-side account identity is known and
        // the events are very close together.
        if ((firstRef == null) == (secondRef == null)) return false
        if (firstBank == null || secondBank == null || firstBank != secondBank) return false
        if (firstLast4.isEmpty() && secondLast4.isEmpty()) return false
        if (!paymentCompatible(first, second)) return false
        if (abs(first.timestamp - second.timestamp) > STRICT_WINDOW_MILLIS) return false
        return merchantsCompatible(first.merchantName, second.merchantName)
    }

    /** Relaxed pair test; never use on its own, see [selectLoosePairs]. */
    fun looseMirrorDuplicates(first: Transaction, second: Transaction): Boolean {
        if (!sameEventShape(first, second)) return false
        if (!paymentCompatible(first, second)) return false
        if (abs(first.timestamp - second.timestamp) > MIRROR_WINDOW_MILLIS) return false
        if (!merchantsCompatible(first.merchantName, second.merchantName)) return false

        val firstBank = normalizedBank(first.bank)
        val secondBank = normalizedBank(second.bank)
        if (firstBank != null && secondBank != null && firstBank != secondBank) return false

        val firstLast4 = first.accountLastFour?.trim().orEmpty()
        val secondLast4 = second.accountLastFour?.trim().orEmpty()
        if (firstLast4.isNotEmpty() && secondLast4.isNotEmpty() && firstLast4 != secondLast4) return false

        val firstRef = TransactionReferenceNormalizer.normalize(first.refNumber)
        val secondRef = TransactionReferenceNormalizer.normalize(second.refNumber)
        // Two different references are two different events; equal ones are
        // already handled by the strict rule.
        if (firstRef != null && secondRef != null) return false

        val firstKind = kind(first)
        val secondKind = kind(second)

        // Notification (or e-mail row) vs. a real SMS row: neither carries a
        // reference (a card spend) or only one does; the shared card/account
        // last-4 is the identity. The bank may be unknown on the notification.
        if ((firstKind == Kind.REAL) != (secondKind == Kind.REAL)) {
            return firstLast4.isNotEmpty() && firstLast4 == secondLast4
        }

        // Two non-SMS rows for one event (e.g. an Axis credit, which has no
        // SMS and no last-4). Gmail posts a child and a group-summary
        // notification for the same e-mail, and the server e-mail later adds the
        // reference to only one of the rows.
        if (firstKind != Kind.REAL && secondKind != Kind.REAL && firstBank != null && firstBank == secondBank) {
            val gap = abs(first.timestamp - second.timestamp)
            // Reference on exactly one side: the e-mail enriched one row.
            if ((firstRef == null) != (secondRef == null)) return true
            // Neither has a reference: only the near-simultaneous twins
            // (child + summary notification), never two separate payments.
            if (firstRef == null && secondRef == null) return gap <= STRICT_WINDOW_MILLIS
        }

        return false
    }

    /**
     * Returns the loose pairs that are unambiguous: each row has exactly one
     * loose partner among [rows]. Rows in [alreadyHandled] are ignored.
     */
    fun selectLoosePairs(
        rows: List<Transaction>,
        alreadyHandled: Set<Long> = emptySet()
    ): List<Pair<Transaction, Transaction>> {
        val candidates = rows.filter { it.id !in alreadyHandled }
        val partners = HashMap<Long, MutableList<Transaction>>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                if (looseMirrorDuplicates(a, b)) {
                    partners.getOrPut(a.id) { mutableListOf() }.add(b)
                    partners.getOrPut(b.id) { mutableListOf() }.add(a)
                }
            }
        }
        return candidates.mapNotNull { a ->
            val only = partners[a.id]?.singleOrNull() ?: return@mapNotNull null
            if (a.id < only.id && partners[only.id]?.singleOrNull()?.id == a.id) a to only else null
        }
    }
}
