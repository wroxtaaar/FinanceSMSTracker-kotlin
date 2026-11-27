package com.example.financesmstracker.ui

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.TransactionType
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Pure helpers for the home screen (no Android classes, so they are unit tested on the JVM). */
object HomeFormat {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun dayStart(millis: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "Today", "Yesterday", otherwise "26 Nov 2026". */
    fun dayLabel(timestamp: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val today = dayStart(now, zone)
        val day = dayStart(timestamp, zone)
        val c = Calendar.getInstance(zone)
        c.timeInMillis = today
        c.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = c.timeInMillis
        return when (day) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> {
                c.timeInMillis = timestamp
                "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
            }
        }
    }

    /** Symbol and colour (ARGB) for the round icon in front of a row. */
    data class CategoryStyle(val symbol: String, val color: Int)

    fun categoryStyle(category: String?): CategoryStyle = when (category?.uppercase(Locale.ROOT)) {
        "GROCERIES" -> CategoryStyle("🛒", 0xFF2E9E5B.toInt())
        "FOOD" -> CategoryStyle("🍽", 0xFFE08A1E.toInt())
        "FUEL" -> CategoryStyle("⛽", 0xFFC2477A.toInt())
        "TRAVEL" -> CategoryStyle("✈", 0xFF3B82C4.toInt())
        "SUBSCRIPTION" -> CategoryStyle("🔁", 0xFF7B5EA7.toInt())
        "BILLS" -> CategoryStyle("🧾", 0xFF0E7C86.toInt())
        "MEDICAL" -> CategoryStyle("💊", 0xFFD64545.toInt())
        "SHOPPING" -> CategoryStyle("🛍", 0xFF8E6BBF.toInt())
        "TRANSFER", "BILL_PAYMENT" -> CategoryStyle("⇄", 0xFF607D8B.toInt())
        "SALARY" -> CategoryStyle("💼", 0xFF2E9E5B.toInt())
        "REFUND" -> CategoryStyle("↩", 0xFF2E9E5B.toInt())
        else -> CategoryStyle("•", 0xFF8A8F98.toInt())
    }

    /** A real payee, or null when only the bank is known. */
    fun payeeOrNull(tx: Transaction): String? =
        listOf(tx.merchantName, tx.payeeId).firstOrNull { !it.isNullOrBlank() }?.trim()

    data class CategoryTotal(val category: String, val minor: Long)

    /**
     * Money spent per category between [from] (inclusive) and [to] (exclusive). Credits and own-account
     * transfers are not spending.
     */
    fun spendingByCategory(transactions: List<Transaction>, from: Long, to: Long): List<CategoryTotal> =
        transactions
            .filter {
                it.transactionType == TransactionType.DEBIT &&
                    it.timestamp in from until to &&
                    !it.category.equals("TRANSFER", ignoreCase = true) &&
                    !it.category.equals("BILL_PAYMENT", ignoreCase = true)
            }
            .groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: "OTHER" }
            .map { (category, rows) -> CategoryTotal(category, rows.sumOf { it.amountPaise }) }
            .sortedByDescending { it.minor }

    /** [start, end) of the month that is [offset] months from the one containing [now]. */
    fun monthRange(now: Long, offset: Int, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long> {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = now
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MONTH, offset)
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to c.timeInMillis
    }

    fun monthTitle(start: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = start
        return "${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** Whole days from [now] until the next [dueDay] of a month (0 = today). A day past month end falls on its last day. */
    fun daysUntilDue(dueDay: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val today = dayStart(now, zone)
        val c = Calendar.getInstance(zone)
        c.timeInMillis = today
        fun dueIn(monthOffset: Int): Long {
            val d = Calendar.getInstance(zone)
            d.timeInMillis = today
            d.set(Calendar.DAY_OF_MONTH, 1)
            d.add(Calendar.MONTH, monthOffset)
            d.set(Calendar.DAY_OF_MONTH, minOf(dueDay, d.getActualMaximum(Calendar.DAY_OF_MONTH)))
            return d.timeInMillis
        }
        var due = dueIn(0)
        if (due < today) due = dueIn(1)
        val dst = Calendar.getInstance(zone)
        dst.timeInMillis = due
        // Count calendar days, not 24-hour blocks, so a DST change cannot shift the answer.
        var days = 0
        val walk = Calendar.getInstance(zone)
        walk.timeInMillis = today
        while (walk.timeInMillis < due && days < 400) { walk.add(Calendar.DAY_OF_YEAR, 1); days++ }
        return days
    }

    /** Share of the limit in use, 0..100 (rounded); 0 when no limit is set. A credit balance counts as 0. */
    fun utilisationPercent(outstandingMinor: Long, limitMinor: Long): Int =
        if (limitMinor <= 0) 0 else (outstandingMinor.coerceAtLeast(0) * 100 / limitMinor).coerceIn(0, 100).toInt()
}
