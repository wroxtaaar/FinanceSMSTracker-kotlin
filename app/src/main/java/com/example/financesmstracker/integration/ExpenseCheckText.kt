package com.example.financesmstracker.integration

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the ledger / Expense comparison into the "which ones did I forget to add" list. */
object ExpenseCheckText {
    fun format(check: ExpenseCheck, money: (Long) -> String, date: (Long) -> String = ::defaultDate): String {
        if (!check.configured) return "Expense app is not connected to the server yet."
        if (check.error != null) return "Could not read the Expense app: ${check.error}"
        val lines = mutableListOf<String>()
        val done = "${check.matched} in Expense" + if (check.handled > 0) ", ${check.handled} skipped" else ""
        if (check.missing.isEmpty()) {
            lines += "Everything since ${check.since} is in Expense ($done)."
        } else {
            lines += "NOT IN EXPENSE YET (${check.missing.size}), total ${money(check.missing.sumOf { it.amountMinor })}"
            check.missing.forEach { lines += "${date(it.timestamp)}  ${money(it.amountMinor)}  ${it.label}" }
            lines += done + "."
        }
        return lines.joinToString("\n")
    }

    private fun defaultDate(timestamp: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
}
