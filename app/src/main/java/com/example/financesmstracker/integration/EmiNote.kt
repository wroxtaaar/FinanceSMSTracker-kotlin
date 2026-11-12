package com.example.financesmstracker.integration

/** Builds the small "EMI reserved" line, adding a warning when the account cannot cover it. */
object EmiNote {
    fun format(reservations: List<EmiReservation>, money: (Long) -> String): String =
        reservations.joinToString("\n") { item ->
            val base = "Reserved for ${item.label} EMI ${money(item.amountMinor)} (due ${ordinal(item.dueDay)})"
            if (item.shortfall) {
                base + " • ${item.bank} ••${item.accountLast4} is below the EMI, add funds before the due date"
            } else {
                base
            }
        }

    internal fun ordinal(day: Int): String {
        val suffix = if (day in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }
}
