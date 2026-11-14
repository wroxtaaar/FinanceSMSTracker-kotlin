package com.example.financesmstracker.integration

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the server's "changes since the total was set" report into text the user can compare with Splitwise. */
object SplitwiseChangesText {
    fun format(changes: SplitwiseChanges, money: (Long) -> String, date: (Long) -> String = ::defaultDate): String {
        val base = changes.baselineMinor
        if (changes.setAt == null || base == null) {
            return "No starting point yet. Save a Splitwise amount once and the changes after it will be listed here."
        }
        val lines = mutableListOf<String>()
        lines += "Set to ${money(base)}, now ${money(base + changes.changeMinor)} (${signed(changes.changeMinor, money)})"
        lines += ""
        if (changes.items.isEmpty()) lines += "No new transactions since then."
        changes.items.forEach { item ->
            val name = item.description?.takeIf { it.isNotBlank() } ?: item.bank
            lines += "${date(item.timestamp)}  ${signed(item.deltaMinor, money)}  $name"
        }
        if (changes.otherChangeMinor != 0L) {
            lines += ""
            lines += "${signed(changes.otherChangeMinor, money)} from edits to older rows (a category changed or a duplicate merged)"
        }
        return lines.joinToString("\n")
    }

    internal fun signed(minor: Long, money: (Long) -> String): String =
        (if (minor >= 0) "+" else "−") + money(kotlin.math.abs(minor))

    private fun defaultDate(timestamp: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
}
