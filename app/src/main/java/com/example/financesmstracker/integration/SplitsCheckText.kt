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
        val done = "${check.matched} in Splits" + if (check.handled > 0) ", ${check.handled} skipped" else ""
        if (check.missing.isEmpty()) {
            lines += "Everything since ${check.since} is in Splits ($done)."
        } else {
            lines += "NOT IN SPLITS YET (${check.missing.size}), total ${money(check.missing.sumOf { it.amountMinor })}"
            check.missing.forEach { lines += "${date(it.timestamp)}  ${money(it.amountMinor)}  ${it.label}" }
            lines += done + "."
        }
        return lines.joinToString("\n")
    }

    private fun defaultDate(timestamp: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
}
