package com.example.financesmstracker

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.categorizer.CategoryMemoryKey
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.integration.OracleIds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreign-currency spends are converted to an INR estimate on the server.
 * For those rows (and only those) show the estimate and let the user type
 * the exact rupee amount from the statement.
 */
internal fun MainActivity.setupForeignAmountSection(dialogView: View, tx: Transaction) {
    val section = dialogView.findViewById<View>(R.id.foreignAmountSection)
    val summary = dialogView.findViewById<TextView>(R.id.foreignAmountSummary)
    val input = dialogView.findViewById<EditText>(R.id.foreignAmountInput)
    val save = dialogView.findViewById<Button>(R.id.foreignAmountSave)
    val oracleId = FinanceSyncBridge.oracleVoidId(
        tx.id, repository.getSmsHashById(tx.id), OracleIds.scheme(this)
    )

    fun render(info: FinanceSyncClient.ForeignAmountInfo) {
        section.visibility = View.VISIBLE
        val inr = String.format(Locale.US, "%.2f", info.inrMinor / 100.0)
        val original = String.format(
            Locale.US, "%s %.2f", info.origCurrency, info.origAmountMinor / 100.0
        )
        summary.text = if (info.exact) {
            "Foreign spend $original → ₹$inr (exact)"
        } else {
            "Foreign spend $original → ₹$inr (estimate: day rate + card markup)"
        }
        input.setText(inr)
    }

    oracleExecutor.execute {
        val result = FinanceSyncClient(this@setupForeignAmountSection).fetchForeignAmountInfo(oracleId)
        runOnUiThread {
            result.onSuccess { info -> if (info.foreign) render(info) }
        }
    }

    save.setOnClickListener {
        val rupees = input.text.toString().trim().toBigDecimalOrNull()
        if (rupees == null || rupees.signum() <= 0) {
            Toast.makeText(this, "Enter the exact rupee amount charged", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val minor = rupees.movePointRight(2)
            .setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        save.isEnabled = false
        oracleExecutor.execute {
            val client = FinanceSyncClient(this@setupForeignAmountSection)
            val result = client.setForeignInrAmount(oracleId, minor)
            val refreshed = if (result.isSuccess) client.fetchForeignAmountInfo(oracleId).getOrNull() else null
            runOnUiThread {
                save.isEnabled = true
                result.onSuccess {
                    refreshed?.takeIf { it.foreign }?.let { render(it) }
                    Toast.makeText(this, "Exact amount saved", Toast.LENGTH_SHORT).show()
                    loadOracleSummary()
                    refreshForeignAmounts()
                }.onFailure { error ->
                    Toast.makeText(
                        this, "Could not save: " + (error.message ?: "Unavailable"), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

/**
 * A salary credit stays out of Splitwise by default. This lets the user choose
 * how much of it counts (0 up to the full amount); Oracle derives the total.
 */
internal fun MainActivity.setupSalaryShareSection(dialogView: View, tx: Transaction) {
    if (tx.transactionType != TransactionType.CREDIT || !tx.category.equals("SALARY", ignoreCase = true)) return
    val section = dialogView.findViewById<View>(R.id.salaryShareSection)
    val summary = dialogView.findViewById<TextView>(R.id.salaryShareSummary)
    val input = dialogView.findViewById<EditText>(R.id.salaryShareInput)
    val save = dialogView.findViewById<Button>(R.id.salaryShareSave)
    val oracleId = FinanceSyncBridge.oracleVoidId(
        tx.id, repository.getSmsHashById(tx.id), OracleIds.scheme(this)
    )

    fun rupees(minor: Long) = String.format(Locale.US, "%.2f", minor / 100.0)

    fun render(info: FinanceSyncClient.SalaryShare) {
        section.visibility = View.VISIBLE
        summary.text = "₹${rupees(info.shareMinor)} of this ₹${rupees(info.amountMinor)} salary counts in Splitwise"
        input.setText(rupees(info.shareMinor))
    }

    oracleExecutor.execute {
        val result = FinanceSyncClient(this@setupSalaryShareSection).fetchSalaryShare(oracleId)
        runOnUiThread { result.onSuccess { render(it) } }
    }

    save.setOnClickListener {
        val value = input.text.toString().trim().toBigDecimalOrNull()
        if (value == null || value.signum() < 0) {
            Toast.makeText(this, "Enter an amount (0 keeps the salary out of Splitwise)", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val minor = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        save.isEnabled = false
        oracleExecutor.execute {
            val client = FinanceSyncClient(this@setupSalaryShareSection)
            val result = client.setSalaryShare(oracleId, minor)
            val refreshed = if (result.isSuccess) client.fetchSalaryShare(oracleId).getOrNull() else null
            runOnUiThread {
                save.isEnabled = true
                result.onSuccess {
                    refreshed?.let { render(it) }
                    Toast.makeText(this, "Splitwise amount saved", Toast.LENGTH_SHORT).show()
                    loadOracleSummary()
                }.onFailure { error ->
                    Toast.makeText(
                        this, "Could not save: " + (error.message ?: "Unavailable"), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

/**
 * A bank debit that paid one of the user's credit cards, when the card never
 * sent its own confirmation. Marking it records the matching card credit (same
 * amount and time, a "Credit Card Bill Payment"), which lowers the card's
 * Bill, locks the bill against older statements, and pairs with this debit as
 * a transfer between own accounts so Splitwise does not rise. Deleting the
 * card credit undoes it.
 */
internal fun MainActivity.setupMarkCardPaymentButton(dialogView: View, tx: Transaction, dialog: AlertDialog) {
    val button = dialogView.findViewById<Button>(R.id.buttonMarkCardPayment)
    val eligible = tx.transactionType == TransactionType.DEBIT &&
        tx.accountType == com.example.financesmstracker.parser.AccountType.BANK_ACCOUNT &&
        tx.currency.equals("INR", ignoreCase = true)
    if (!eligible) return
    // Already marked (its card credit exists): there is nothing left to do, and a
    // second tap must not be possible. Deleting that card credit brings the button back.
    val markKey = com.example.financesmstracker.transfer.CardPaymentMatch.MANUAL_PREFIX + tx.id
    if (repository.getAllTransactions().any { it.smsHash == markKey }) return
    button.visibility = View.VISIBLE
    button.setOnClickListener {
        button.isEnabled = false
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@setupMarkCardPaymentButton).fetchAccounts()
            runOnUiThread {
                button.isEnabled = true
                val cards = result.getOrNull().orEmpty().filter { it.accountType == "CREDIT_CARD" }
                if (cards.isEmpty()) {
                    Toast.makeText(this, "No credit cards found. Check the Oracle connection.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = cards.map { "${it.name} ••${it.last4 ?: "?"}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Which card did this pay?")
                    .setItems(labels) { _, which ->
                        val card = cards[which]
                        val existing = com.example.financesmstracker.transfer.CardPaymentMatch.findAll(
                            candidates = repository.getTransactionsByAmount(tx.amountPaise),
                            amountPaise = tx.amountPaise,
                            last4 = card.last4,
                            aroundMs = tx.timestamp
                        )
                        // Equal part payments are normal (five payments of 5,000), so a
                        // credit of the same amount is a reason to ask, not to refuse.
                        if (existing.isNotEmpty()) {
                            val debits = repository.getTransactionsByAmount(tx.amountPaise).count {
                                it.transactionType == TransactionType.DEBIT &&
                                    it.accountType == com.example.financesmstracker.parser.AccountType.BANK_ACCOUNT
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Add another payment?")
                                .setMessage(
                                    "${card.name} ••${card.last4} already shows ${existing.size} payment(s) of this " +
                                        "amount, and your bank accounts show $debits debit(s) of it. Add this one as " +
                                        "an additional payment?"
                                )
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Add payment") { _, _ -> markCardPayment(tx, card, button, dialog) }
                                .show()
                            return@setItems
                        }
                        markCardPayment(tx, card, button, dialog)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}

private fun MainActivity.markCardPayment(
    tx: Transaction,
    card: com.example.financesmstracker.integration.OracleAccount,
    button: Button,
    dialog: AlertDialog
) {
    val credit = Transaction(
        amountPaise = tx.amountPaise,
        currency = "INR",
        transactionType = TransactionType.CREDIT,
        paymentMethod = tx.paymentMethod,
        accountType = com.example.financesmstracker.parser.AccountType.CREDIT_CARD,
        bank = card.bank,
        merchantName = "Credit Card Bill Payment",
        payeeId = null,
        accountLastFour = card.last4,
        refNumber = tx.refNumber,
        timestamp = tx.timestamp,
        smsHash = com.example.financesmstracker.transfer.CardPaymentMatch.MANUAL_PREFIX + tx.id,
        category = "TRANSFER",
        parserConfidence = 1.0f
    )
    when (val rowId = repository.insertTransaction(credit)) {
        -1L -> Toast.makeText(this, "Already marked as a card payment", Toast.LENGTH_LONG).show()
        0L -> Toast.makeText(this, "Not saved: older than your cleared history", Toast.LENGTH_LONG).show()
        else -> {
            button.visibility = View.GONE
            repository.getTransactionById(rowId)?.let { FinanceSyncBridge.enqueueCanonical(this, it, null) }
            dialog.dismiss()
            loadTransactions()
            Toast.makeText(
                this,
                "Recorded as a payment to ${card.name} ••${card.last4}. Delete that card credit to undo.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

internal fun MainActivity.showTransactionDetailDialog(tx: Transaction) {
    val dialogView = LayoutInflater.from(this)
        .inflate(R.layout.dialog_transaction_detail, null)

    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .create()

    val detailTextAmount =
        dialogView.findViewById<TextView>(R.id.detailTextAmount)

    val detailTextType =
        dialogView.findViewById<TextView>(R.id.detailTextType)

    val detailTextPaymentMethod =
        dialogView.findViewById<TextView>(R.id.detailTextPaymentMethod)

    val detailTextAccountType =
        dialogView.findViewById<TextView>(R.id.detailTextAccountType)

    val detailTextBank =
        dialogView.findViewById<TextView>(R.id.detailTextBank)

    val detailTextMerchant =
        dialogView.findViewById<TextView>(R.id.detailTextMerchant)

    val detailTextPayeeId =
        dialogView.findViewById<TextView>(R.id.detailTextPayeeId)

    val detailTextAccountLastFour =
        dialogView.findViewById<TextView>(R.id.detailTextAccountLastFour)

    val detailTextRefNumber =
        dialogView.findViewById<TextView>(R.id.detailTextRefNumber)

    val detailTextDateTime =
        dialogView.findViewById<TextView>(R.id.detailTextDateTime)

    val spinnerCategory =
        dialogView.findViewById<Spinner>(R.id.spinnerCategory)

    val checkboxRememberPayee =
        dialogView.findViewById<CheckBox>(R.id.checkboxRememberPayee)

    val buttonSaveCategory =
        dialogView.findViewById<Button>(R.id.buttonSaveCategory)

    val buttonVoidTransaction =
        dialogView.findViewById<Button>(R.id.buttonVoidTransaction)

    val rupees = tx.amountPaise / 100.0

    detailTextAmount.text =
        if (tx.currency.equals("INR", ignoreCase = true)) {
            String.format(Locale.getDefault(), "Amount: ₹%.2f", rupees)
        } else {
            String.format(Locale.getDefault(), "Amount: %s %.2f", tx.currency.uppercase(), rupees)
        }

    setupForeignAmountSection(dialogView, tx)
    setupSalaryShareSection(dialogView, tx)

    detailTextType.text =
        "Type: ${tx.transactionType.name}"

    detailTextPaymentMethod.text =
        "Payment Method: ${tx.paymentMethod.name}"

    detailTextAccountType.text =
        "Account Type: ${tx.accountType.name}"

    detailTextBank.text =
        "Bank: ${tx.bank ?: "-"}"

    detailTextMerchant.text =
        "Merchant: ${tx.merchantName ?: "-"}"

    detailTextPayeeId.text =
        if (!tx.payeeId.isNullOrBlank()) {
            "UPI ID: ${tx.payeeId}"
        } else {
            "UPI ID: Not provided"
        }

    detailTextAccountLastFour.text =
        "A/C Last 4: ${tx.accountLastFour ?: "-"}"

    detailTextRefNumber.text =
        "Reference: ${tx.refNumber ?: "-"}"

    val sdf = SimpleDateFormat(
        "dd MMM yyyy, hh:mm a",
        Locale.getDefault()
    )

    detailTextDateTime.text =
        "Date/Time: ${sdf.format(Date(tx.timestamp))}"

    /*
     * Credits can only use:
     * SALARY, TRANSFER, REFUND, OTHER (keeps it out of Splitwise).
     *
     * Debits can use the complete category list.
     */
    val baseCategories =
        if (tx.transactionType == TransactionType.CREDIT) {
            creditCategories
        } else {
            categories
        }
    // Show the stored category honestly even when it is no longer offered,
    // instead of silently displaying the first entry of the list.
    val availableCategories =
        if (!tx.category.isNullOrBlank() && tx.category !in baseCategories) {
            listOf(tx.category) + baseCategories
        } else {
            baseCategories
        }

    val spinnerAdapter = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        availableCategories
    )

    spinnerAdapter.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item
    )

    spinnerCategory.adapter = spinnerAdapter

    val currentCategoryIndex =
        availableCategories.indexOf(tx.category)

    if (currentCategoryIndex >= 0) {
        spinnerCategory.setSelection(currentCategoryIndex)
    }

    val memoryKey = CategoryMemoryKey.from(
        ParserResult(
            isTransaction = true,
            amountPaise = tx.amountPaise,
            transactionType = tx.transactionType,
            paymentMethod = tx.paymentMethod,
            accountType = tx.accountType,
            bank = tx.bank,
            merchantName = tx.merchantName,
            payeeId = tx.payeeId,
            accountLastFour = tx.accountLastFour,
            refNumber = tx.refNumber,
            confidence = tx.parserConfidence
        )
    )

    val hasStableMemoryKey = !memoryKey.isNullOrBlank()

    if (hasStableMemoryKey) {
        checkboxRememberPayee.visibility = View.VISIBLE
        checkboxRememberPayee.isChecked = false
    } else {
        checkboxRememberPayee.visibility = View.GONE
        checkboxRememberPayee.isChecked = false
    }

    setupMarkCardPaymentButton(dialogView, tx, dialog)

    buttonVoidTransaction.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle("Delete Transaction?")
            .setMessage("This removes the transaction from the app history and queues the removal for Oracle. It does not delete the original SMS.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val updated = repository.voidTransaction(tx.id)
                if (updated > 0) {
                    dialog.dismiss()
                    loadTransactions()
                    FinanceSyncBridge.enqueueVoidedTransaction(this, tx.id)
                    Toast.makeText(this, "Transaction deleted and queued for Oracle sync", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not void transaction", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    buttonSaveCategory.setOnClickListener {
        val selectedCategory =
            spinnerCategory.selectedItem.toString()

        repository.updateTransactionCategory(
            tx.id,
            selectedCategory
        )

        val transactionsToSync =
            if (
                hasStableMemoryKey &&
                checkboxRememberPayee.isChecked &&
                !memoryKey.isNullOrBlank()
            ) {
                repository.saveCategoryMemory(
                    memoryKey,
                    selectedCategory
                )

                repository.updateCategoriesForMemoryKey(
                    memoryKey,
                    selectedCategory
                )

                repository.getTransactionsByMemoryKey(memoryKey)
            } else {
                listOfNotNull(repository.getTransactionById(tx.id))
            }

        // Category changes are ledger-affecting because Splitwise uses
        // OTHER as the explicit opt-out. Push the updated existing rows to
        // Oracle so it can add/remove each row's Splitwise share without
        // applying any bank/card balance a second time.
        transactionsToSync.forEach { updatedTransaction ->
            FinanceSyncBridge.enqueueCategoryUpdate(this, updatedTransaction)
        }

        if (
            hasStableMemoryKey &&
            checkboxRememberPayee.isChecked &&
            !memoryKey.isNullOrBlank()
        ) {
            Toast.makeText(
                this,
                "Category updated and remembered for matching transactions",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "Category updated",
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.dismiss()
        loadTransactions()
        loadOracleSummary()
    }

    dialog.show()
}

internal fun MainActivity.applyForeignAmounts(transactions: List<Transaction>) {
    if (foreignAmountsByOracleId.isEmpty()) return
    val byLocalId = HashMap<Long, FinanceSyncClient.ForeignAmountInfo>()
    transactions.forEach { tx ->
        foreignAmountsByOracleId[FinanceSyncBridge.oracleVoidId(tx.id, tx.smsHash, OracleIds.scheme(this))]
            ?.let { byLocalId[tx.id] = it }
    }
    adapter.setForeignAmounts(byLocalId)
}

/** Fetch the rupee amounts of foreign spends so the list can show them. */
internal fun MainActivity.refreshForeignAmounts() {
    oracleExecutor.execute {
        val result = FinanceSyncClient(this@refreshForeignAmounts).fetchForeignAmounts()
        runOnUiThread {
            result.onSuccess { amounts ->
                foreignAmountsByOracleId = amounts
                applyForeignAmounts(repository.getAllTransactions())
            }
        }
    }
}
