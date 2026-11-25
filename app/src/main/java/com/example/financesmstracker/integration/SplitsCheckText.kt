package com.example.financesmstracker.integration

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the ledger / Splits comparison into the "which ones did I forget to add" list. */
object SplitsCheckText {
    fun format(check: SplitsCheck, money: (Long) -> String, date: (Long) -> String = ::defaultDate): String {
        if (!check.configured) return "Splits app is not connected to the server yet."
        if (check.error != null) return "Could not read the Splits app: ${check.error}"
        val lines = mutableListOf<String>()
        if (check.missing.isEmpty()) {
            lines += "Everything since ${check.since} is in Splits (${check.matched} matched)."
        } else {
            lines += "NOT IN SPLITS YET (${check.missing.size}), total ${money(check.missing.sumOf { it.amountMinor })}"
            check.missing.forEach { lines += "${date(it.timestamp)}  ${money(it.amountMinor)}  ${it.label}" }
            lines += "${check.matched} others are matched."
        }
        if (check.extra.isNotEmpty()) {
            lines += ""
            lines += "IN SPLITS, NOT IN THE APP (${check.extra.size}) — cash, or the app missed it"
            check.extra.forEach { lines += "${money(it.amountMinor)}  ${it.label}" }
        }
        return lines.joinToString("\n")
    }

    private fun defaultDate(timestamp: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
}
