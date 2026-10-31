package com.example.financesmstracker.data

/** Category choices offered when editing or creating a transaction. */
object TransactionCategories {
    val debit = listOf(
        "FOOD", "GROCERIES", "FUEL", "TRAVEL",
        "SUBSCRIPTION", "BILLS", "MEDICAL", "TRANSFER",
        "OTHER"
    )

    val credit = listOf("SALARY", "TRANSFER", "BILL_PAYMENT", "REFUND", "OTHER")
}
