package com.example.financesmstracker

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.OracleAccount
import com.example.financesmstracker.util.MoneyInput

internal fun MainActivity.loadOracleAccountsForManualEdit(accountType: String) {
    oracleExecutor.execute {
        val result = FinanceSyncClient(this@loadOracleAccountsForManualEdit).fetchAccounts()

        runOnUiThread {
            result.onSuccess { accounts ->
                val filtered = accounts.filter { it.accountType == accountType }
                showManualAccountsDialog(filtered, accountType)
            }.onFailure { error ->
                Toast.makeText(
                    this@loadOracleAccountsForManualEdit,
                    "Could not load accounts: " + (error.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

internal fun MainActivity.showManualAccountsDialog(
    accounts: List<OracleAccount>,
    accountType: String
) {
    val title = if (accountType == "BANK_ACCOUNT") "Edit Bank Accounts" else "Credit Cards"

    if (accounts.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("No matching accounts configured on Oracle.")
            .setPositiveButton("OK", null)
            .show()
        return
    }

    if (accountType == "CREDIT_CARD") showCreditCardsDialog(accounts, title) else showBankAccountsDialog(accounts, title)
}

private fun MainActivity.accountLabel(account: OracleAccount, topPadding: Int, bottomPadding: Int) =
    TextView(this).apply {
        text = buildString {
            append(displayAccountName(account.name))
            account.last4?.let { append(" ••••").append(it) }
        }
        textSize = 14f
        setPadding(0, topPadding, 0, bottomPadding)
    }

private fun MainActivity.amountInput(hint: String, valueMinor: Long, signed: Boolean = false) =
    EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        setSingleLine(true)
        this.hint = hint
        setText(formatDecimalMinor(valueMinor))
        selectAll()
    }

private fun MainActivity.dialogContent(build: LinearLayout.() -> Unit) = ScrollView(this).apply {
    isFillViewport = true
    addView(LinearLayout(this@dialogContent).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 0, 48, 0)
        build()
    })
}

/**
 * Save every row on the Oracle executor, stop at the first failure, then report.
 * [save] is called once per row with a client; the dialog closes only on success.
 */
private fun <T> MainActivity.saveRows(
    dialog: AlertDialog,
    rows: List<T>,
    successMessage: String,
    failureMessage: String,
    save: (FinanceSyncClient, T) -> Result<*>
) {
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    oracleExecutor.execute {
        var failure: Throwable? = null
        val client = FinanceSyncClient(this@saveRows)
        for (row in rows) {
            val result = save(client, row)
            if (result.isFailure) {
                failure = result.exceptionOrNull()
                break
            }
        }
        runOnUiThread {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            if (failure == null) {
                dialog.dismiss()
                loadOracleSummary()
                Toast.makeText(this@saveRows, successMessage, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@saveRows,
                    failureMessage + (failure?.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/**
 * Credit cards have two separate buckets:
 *   Bill         = statement bill still to be paid.
 *   Active Spend = transactions after that statement.
 *
 * The Oracle ledger still keeps one live outstanding balance, which is
 * Bill + Active Spend. Card debits increase Active Spend; card credits and
 * payments reduce Bill first.
 */
private class CardRow(val account: OracleAccount, val bill: EditText, val active: EditText)

private fun MainActivity.showCreditCardsDialog(accounts: List<OracleAccount>, title: String) {
    val rows = accounts.map { account ->
        // Bill is never negative. Active Spend = outstanding - Bill and may be
        // negative: paying more than the bill, or a refund, leaves a credit.
        val billMinor = account.billBalanceMinor.coerceAtLeast(0L)
        CardRow(
            account,
            amountInput("Bill", billMinor),
            amountInput("Active spend", account.balanceMinor - billMinor, signed = true)
        )
    }

    val scrollView = dialogContent {
        addView(LinearLayout(this@showCreditCardsDialog).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                TextView(this@showCreditCardsDialog).apply { text = "Bill"; textSize = 12f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(this@showCreditCardsDialog).apply { text = "Active Spend"; textSize = 12f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12 }
            )
        })
        rows.forEach { row ->
            addView(accountLabel(row.account, 10, 4))
            addView(LinearLayout(this@showCreditCardsDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                row.bill.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 6 }
                row.active.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 6 }
                addView(row.bill)
                addView(row.active)
            })
        }
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(scrollView)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = mutableListOf<Triple<OracleAccount, Long, Long>>()
            for (row in rows) {
                val bill = MoneyInput.parseMinor(row.bill.text.toString())
                val active = MoneyInput.parseMinor(row.active.text.toString(), allowNegative = true)
                if (bill == null) {
                    row.bill.error = "Enter a valid bill amount"
                    row.bill.requestFocus()
                    return@setOnClickListener
                }
                if (active == null) {
                    row.active.error = "Enter a valid active amount"
                    row.active.requestFocus()
                    return@setOnClickListener
                }
                values += Triple(row.account, bill, active)
            }
            saveRows(dialog, values, "Credit card amounts updated", "Could not update credit cards: ") { client, (account, bill, active) ->
                client.updateAccountBalance(
                    account = account,
                    balanceMinor = bill + active,
                    billBalanceMinor = bill
                )
            }
        }
    }
    dialog.show()
}

/** Bank accounts keep the single current-balance editor. */
private fun MainActivity.showBankAccountsDialog(accounts: List<OracleAccount>, title: String) {
    val inputs = accounts.map { it to amountInput("Current balance", it.balanceMinor) }

    val scrollView = dialogContent {
        inputs.forEach { (account, input) ->
            addView(accountLabel(account, 8, 2))
            addView(input)
        }
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage("Manually set the current balance for each bank account.")
        .setView(scrollView)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = mutableListOf<Pair<OracleAccount, Long>>()
            for ((account, input) in inputs) {
                val amount = MoneyInput.parseMinor(input.text.toString())
                if (amount == null) {
                    input.error = "Enter a valid amount"
                    input.requestFocus()
                    return@setOnClickListener
                }
                values += account to amount
            }
            saveRows(dialog, values, "Account balances updated", "Could not update accounts: ") { client, (account, balance) ->
                client.updateAccountBalance(account, balance)
            }
        }
    }
    dialog.show()
}
