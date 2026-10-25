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
    val title = if (accountType == "BANK_ACCOUNT") {
        "Edit Bank Accounts"
    } else {
        "Credit Cards"
    }

    if (accounts.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("No matching accounts configured on Oracle.")
            .setPositiveButton("OK", null)
            .show()
        return
    }

    if (accountType == "CREDIT_CARD") {
        /*
         * Credit cards have two separate buckets:
         *   Bill         = statement bill still to be paid.
         *   Active Spend = transactions after that statement.
         *
         * The Oracle ledger still keeps one live outstanding balance,
         * which is Bill + Active Spend. Card debits increase Active Spend;
         * card credits/payments reduce Bill first.
         */
        val rows = accounts.map { account ->
            // A credit card can legitimately have a negative net balance
            // (for example, a refund/credit posted while the opening
            // balance is zero). The old coerceIn(0, balance) call throws
            // IllegalArgumentException when balance is negative because
            // the lower bound becomes greater than the upper bound.
            // Keep the editable Bill/Active buckets non-negative and show
            // the negative amount separately as a credit balance.
            val isCreditBalance = account.balanceMinor < 0L
            val billMinor = if (isCreditBalance) {
                0L
            } else {
                account.billBalanceMinor.coerceAtLeast(0L).coerceAtMost(account.balanceMinor)
            }
            val activeMinor = if (isCreditBalance) {
                0L
            } else {
                (account.balanceMinor - billMinor).coerceAtLeast(0L)
            }
            val creditBalanceMinor = if (isCreditBalance) -account.balanceMinor else 0L

            val label = TextView(this).apply {
                text = buildString {
                    append(displayAccountName(account.name))
                    account.last4?.let { append(" ••••").append(it) }
                    if (isCreditBalance) {
                        append("\nCredit balance: ")
                        append(formatDecimalMinor(creditBalanceMinor))
                    }
                }
                textSize = 14f
                setPadding(0, 10, 0, 4)
            }

            val billInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                hint = "Bill"
                setText(formatDecimalMinor(billMinor))
                selectAll()
            }

            val activeInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                hint = "Active spend"
                setText(formatDecimalMinor(activeMinor))
                selectAll()
            }

            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                billInput.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = 6
                }
                activeInput.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = 6
                }
                addView(billInput)
                addView(activeInput)
                layoutParams = lp
            }

            Triple(account, billInput, activeInput).also {
                it
            }.let { row ->
                arrayOf(label, columns, row)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)

            val header = LinearLayout(this@showManualAccountsDialog).apply {
                orientation = LinearLayout.HORIZONTAL

                val billHeader = TextView(this@showManualAccountsDialog).apply {
                    text = "Bill"
                    textSize = 12f
                }
                val activeHeader = TextView(this@showManualAccountsDialog).apply {
                    text = "Active Spend"
                    textSize = 12f
                }

                addView(
                    billHeader,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    activeHeader,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply { marginStart = 12 }
                )
            }
            addView(header)

            rows.forEach { row ->
                @Suppress("UNCHECKED_CAST")
                val label = row[0] as TextView
                val columns = row[1] as LinearLayout
                addView(label)
                addView(columns)
            }
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
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

                rows.forEach { row ->
                    @Suppress("UNCHECKED_CAST")
                    val account = row[2] as Triple<OracleAccount, EditText, EditText>
                    val bill = account.second.text.toString().trim().replace(",", "").toDoubleOrNull()
                    val active = account.third.text.toString().trim().replace(",", "").toDoubleOrNull()

                    if (bill == null || bill < 0) {
                        account.second.error = "Enter a valid bill amount"
                        account.second.requestFocus()
                        return@setOnClickListener
                    }
                    if (active == null || active < 0) {
                        account.third.error = "Enter a valid active amount"
                        account.third.requestFocus()
                        return@setOnClickListener
                    }

                    val billMinor = kotlin.math.round(bill * 100.0).toLong()
                    val activeMinor = kotlin.math.round(active * 100.0).toLong()
                    values += Triple(account.first, billMinor, activeMinor)
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                oracleExecutor.execute {
                    var failure: Throwable? = null

                    for ((account, billMinor, activeMinor) in values) {
                        val saveResult = FinanceSyncClient(this@showManualAccountsDialog)
                            .updateAccountBalance(
                                account = account,
                                balanceMinor = billMinor + activeMinor,
                                billBalanceMinor = billMinor
                            )

                        if (saveResult.isFailure) {
                            failure = saveResult.exceptionOrNull()
                            break
                        }
                    }

                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true

                        if (failure == null) {
                            dialog.dismiss()
                            loadOracleSummary()
                            Toast.makeText(
                                this@showManualAccountsDialog,
                                "Credit card amounts updated",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@showManualAccountsDialog,
                                "Could not update credit cards: " + (failure?.message ?: "Unavailable"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        dialog.show()
        return
    }

    // Bank accounts keep the original single current-balance editor.
    val accountInputs = accounts.map { account ->
        val label = TextView(this).apply {
            text = buildString {
                append(displayAccountName(account.name))
                account.last4?.let { append(" ••••").append(it) }
            }
            textSize = 14f
            setPadding(0, 8, 0, 2)
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "Current balance"
            setText(formatDecimalMinor(account.balanceMinor))
            selectAll()
        }

        Triple(account, label, input)
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 0, 48, 0)

        accountInputs.forEach { (_, label, input) ->
            addView(label)
            addView(input)
        }
    }

    val scrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
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

            for ((account, _, input) in accountInputs) {
                val amount = input.text.toString().trim().replace(",", "").toDoubleOrNull()
                if (amount == null || amount < 0) {
                    input.error = "Enter a valid amount"
                    input.requestFocus()
                    return@setOnClickListener
                }

                values += account to kotlin.math.round(amount * 100.0).toLong()
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

            oracleExecutor.execute {
                var failure: Throwable? = null

                for ((account, balanceMinor) in values) {
                    val saveResult = FinanceSyncClient(this@showManualAccountsDialog)
                        .updateAccountBalance(account, balanceMinor)

                    if (saveResult.isFailure) {
                        failure = saveResult.exceptionOrNull()
                        break
                    }
                }

                runOnUiThread {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true

                    if (failure == null) {
                        dialog.dismiss()
                        loadOracleSummary()
                        Toast.makeText(
                            this@showManualAccountsDialog,
                            "Account balances updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@showManualAccountsDialog,
                            "Could not update accounts: " + (failure?.message ?: "Unavailable"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    dialog.show()
}
