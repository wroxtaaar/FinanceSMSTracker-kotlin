package com.example.financesmstracker.data

/** Category choices offered when editing or creating a transaction. */
object TransactionCategories {
    val debit = listOf(
        "FOOD", "GROCERIES", "FUEL", "TRAVEL",
        "SUBSCRIPTION", "BILLS", "MEDICAL", "TRANSFER",
        "OTHER"
    )

    /** Labels that only make sense for credits and must never stick to a debit. */
    val creditOnly = setOf("BILL_PAYMENT")

    val credit = listOf("SALARY", "TRANSFER", "BILL_PAYMENT", "REFUND", "OTHER")
}
