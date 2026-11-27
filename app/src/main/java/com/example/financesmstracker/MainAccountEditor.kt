package com.example.financesmstracker

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ProgressBar
import com.example.financesmstracker.ui.HomeFormat
import android.graphics.Color
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
        MaterialAlertDialogBuilder(this)
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
            account.last4?.let { append("  ••••").append(it) }
        }
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
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
        setPadding(dp(20), dp(8), dp(20), 0)
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
private class CardRow(
    val account: OracleAccount,
    val bill: EditText,
    val active: EditText,
    val limit: EditText,
    val dueDay: EditText
)

private const val CARD_META_PREFS = "card_meta"

private fun MainActivity.cardMeta() = getSharedPreferences(CARD_META_PREFS, android.content.Context.MODE_PRIVATE)

private fun MainActivity.showCreditCardsDialog(accounts: List<OracleAccount>, title: String) {
    val meta = cardMeta()
    val rows = accounts.map { account ->
        // Bill is never negative. Active Spend = outstanding - Bill and may be
        // negative: paying more than the bill, or a refund, leaves a credit.
        val billMinor = account.billBalanceMinor.coerceAtLeast(0L)
        CardRow(
            account,
            amountInput("Bill", billMinor),
            amountInput("Active spend", account.balanceMinor - billMinor, signed = true),
            amountInput("Credit limit (optional)", meta.getLong("limit_" + account.id, 0L)).apply {
                if (meta.getLong("limit_" + account.id, 0L) == 0L) setText("")
            },
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                hint = "1-31"
                val d = meta.getInt("due_" + account.id, 0)
                if (d in 1..31) setText(d.toString())
            }
        )
    }

    val scrollView = dialogContent {
        rows.forEach { row ->
            addView(tile {
                addView(accountLabel(row.account, 0, 4))

                val outstanding = row.account.balanceMinor
                val limitMinor = meta.getLong("limit_" + row.account.id, 0L)
                if (limitMinor > 0) {
                    val percent = HomeFormat.utilisationPercent(outstanding, limitMinor)
                    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = percent
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
                    })
                    addView(caption("${formatMinor(maxOf(outstanding, 0))} of ${formatMinor(limitMinor)} used ($percent%)"))
                }
                val dueDay = meta.getInt("due_" + row.account.id, 0)
                if (dueDay in 1..31 && row.account.billBalanceMinor > 0) {
                    val days = HomeFormat.daysUntilDue(dueDay, System.currentTimeMillis())
                    addView(TextView(context).apply {
                        text = when (days) {
                            0 -> "Bill due today"
                            1 -> "Bill due tomorrow"
                            else -> "Bill due in $days days"
                        }
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        if (days <= 3) setTextColor(Color.parseColor("#D64545"))
                    })
                }

                fun pair(leftLabel: String, left: EditText, rightLabel: String, right: EditText) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(8), 0, 0)
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(caption(leftLabel)); addView(left)
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(caption(rightLabel)); addView(right)
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
                    })
                }
                pair("Bill", row.bill, "Active spend", row.active)
                pair("Credit limit", row.limit, "Bill due day", row.dueDay)
            })
        }
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(scrollView)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = mutableListOf<Triple<OracleAccount, Long, Long>>()
            val editor = meta.edit()
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
                val limitText = row.limit.text.toString().trim()
                val limit = if (limitText.isEmpty()) 0L else MoneyInput.parseMinor(limitText)
                if (limit == null) {
                    row.limit.error = "Enter a valid limit"
                    row.limit.requestFocus()
                    return@setOnClickListener
                }
                val dueText = row.dueDay.text.toString().trim()
                val due = if (dueText.isEmpty()) 0 else dueText.toIntOrNull()
                if (due == null || (due != 0 && due !in 1..31)) {
                    row.dueDay.error = "Use a day from 1 to 31"
                    row.dueDay.requestFocus()
                    return@setOnClickListener
                }
                // Limit and due day are kept on this phone only; the server never needs them.
                editor.putLong("limit_" + row.account.id, limit).putInt("due_" + row.account.id, due)
                values += Triple(row.account, bill, active)
            }
            editor.apply()
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
            addView(tile {
                addView(accountLabel(account, 0, 2))
                addView(caption("Current balance"))
                addView(input)
            })
        }
    }

    val dialog = MaterialAlertDialogBuilder(this)
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
