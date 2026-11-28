package com.example.financesmstracker

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ProgressBar
import com.example.financesmstracker.ui.HomeFormat
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.integration.EmiNote
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

private fun MainActivity.dialogContent(build: LinearLayout.() -> Unit) = NestedScrollView(this).apply {
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
    sheet: FormSheet,
    rows: List<T>,
    successMessage: String,
    failureMessage: String,
    save: (FinanceSyncClient, T) -> Result<*>
) {
    sheet.positiveEnabled = false
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
            sheet.positiveEnabled = true
            if (failure == null) {
                sheet.dismiss()
                loadOracleSummary()
                showSnack(successMessage)
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
    /** Usage bar + due date; rebuilt whenever the card's limit, due day or sharing changes. */
    val summary: LinearLayout
)

private fun MainActivity.cardTitle(account: OracleAccount) = buildString {
    append(displayAccountName(account.name))
    account.last4?.let { append("  ••••").append(it) }
}

/**
 * Usage bar with the due date on the right. Cards that share one combined limit show the COMBINED
 * usage: the lighter bar is everything on the shared limit, the solid part is this card's own share.
 */
private fun MainActivity.renderCardSummary(
    container: LinearLayout,
    account: OracleAccount,
    accounts: List<OracleAccount>,
    meta: CardMeta
) {
    container.removeAllViews()
    val group = meta.sharedWith(account.id, accounts.map { it.id }).let { ids -> accounts.filter { it.id in ids } }
    val shared = group.size > 1
    val limitMinor = meta.limitMinor(account.id)
    val dueDay = meta.dueDay(account.id)
    val own = account.balanceMinor.coerceAtLeast(0L)
    val combined = group.sumOf { it.balanceMinor.coerceAtLeast(0L) }

    val combinedSigned = group.sumOf { it.balanceMinor }
    val available = meta.availableMinor(account.id, combinedSigned)

    var usage: String? = null
    if (limitMinor > 0 && available != null) {
        val usedPercent = HomeFormat.utilisationPercent(limitMinor - available, limitMinor)
        container.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = usedPercent
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        })
        usage = "${formatMinor(available)} available of ${formatMinor(limitMinor)}" + if (shared) " (shared)" else ""
    } else if (limitMinor > 0) {
        val ownPercent = HomeFormat.utilisationPercent(own, limitMinor)
        val groupPercent = HomeFormat.utilisationPercent(combined, limitMinor)
        container.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = ownPercent
            secondaryProgress = groupPercent
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        })
        usage = if (shared) {
            "${formatMinor(combined)} of ${formatMinor(limitMinor)} shared ($groupPercent%)"
        } else {
            "${formatMinor(own)} of ${formatMinor(limitMinor)} used ($ownPercent%)"
        }
    }

    var dueText: String? = null
    var dueUrgent = false
    if (dueDay in 1..31) {
        if (account.billBalanceMinor > 0) {
            val days = HomeFormat.daysUntilDue(dueDay, System.currentTimeMillis())
            dueText = when (days) {
                0 -> "Due today"
                1 -> "Due tomorrow"
                else -> "Due in $days days"
            }
            dueUrgent = days <= 3
        } else {
            dueText = "Due ${EmiNote.ordinal(dueDay)}"
        }
    }

    val usageLine = usage
    val dueLine = dueText
    if (usageLine != null || dueLine != null) {
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            addView(
                if (usageLine != null) caption(usageLine) else View(context),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (dueLine != null) {
                addView(TextView(context).apply {
                    text = dueLine
                    textSize = 12f
                    gravity = Gravity.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    if (dueUrgent) setTextColor(Color.parseColor("#D64545"))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            }
        })
    }
    if (shared) {
        val names = group.filter { it.id != account.id }.joinToString(", ") {
            displayAccountName(it.name) + (it.last4?.let { l4 -> " ••$l4" } ?: "")
        }
        container.addView(caption("One limit shared with $names"))
    }
}

/** Opens when the card name is tapped, so limit, due day and sharing take no space until needed. */
private fun MainActivity.showCardSettingsDialog(
    account: OracleAccount,
    accounts: List<OracleAccount>,
    meta: CardMeta,
    onSaved: () -> Unit
) {
    val currentLimit = meta.limitMinor(account.id)
    val limit = amountInput("Credit limit (optional)", currentLimit).apply {
        if (currentLimit == 0L) setText("")
    }
    val groupNow = accounts.filter { it.id in meta.sharedWith(account.id, accounts.map { a -> a.id }) }
    val currentAvailable = meta.availableMinor(account.id, groupNow.sumOf { it.balanceMinor })
    val available = amountInput("Available limit now (optional)", currentAvailable ?: 0L).apply {
        if (currentAvailable == null) setText("")
    }
    val due = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        hint = "1-31"
        meta.dueDay(account.id).takeIf { it > 0 }?.let { setText(it.toString()) }
    }
    val others = accounts.filter { it.id != account.id }
    val sharedNow = meta.sharedWith(account.id, accounts.map { it.id })
    val checks = others.map { other ->
        other to CheckBox(this).apply {
            text = displayAccountName(other.name) + (other.last4?.let { "  ••••$it" } ?: "")
            isChecked = other.id in sharedNow
        }
    }

    val content = dialogContent {
        addView(caption("Credit limit"))
        addView(limit)
        addView(caption("Available limit now").apply { setPadding(0, dp(12), 0, 0) })
        addView(available)
        addView(caption("Type it once from your bank app. It then drops with spends and rises with bill payments; EMI amounts stay blocked.").apply { textSize = 11f })
        addView(caption("Bill due day").apply { setPadding(0, dp(12), 0, 0) })
        addView(due)
        if (checks.isNotEmpty()) {
            addView(caption("Shares one combined limit with").apply { setPadding(0, dp(16), 0, 0) })
            checks.forEach { (_, box) -> addView(box) }
            addView(caption("Tick cards that have a single limit between them. Enter the combined limit above."))
        }
    }

    formSheet(title = cardTitle(account), subtitle = "Limit, due date and shared limit", body = content) { sheet ->
        val limitText = limit.text.toString().trim()
        val limitMinor = if (limitText.isEmpty()) 0L else MoneyInput.parseMinor(limitText)
        if (limitMinor == null) {
            limit.error = "Enter a valid limit"
            limit.requestFocus()
            return@formSheet
        }
        val availText = available.text.toString().trim()
        val availMinor = if (availText.isEmpty()) null else MoneyInput.parseMinor(availText)
        if (availText.isNotEmpty() && availMinor == null) {
            available.error = "Enter a valid amount"
            available.requestFocus()
            return@formSheet
        }
        val dueText = due.text.toString().trim()
        val dueDay = if (dueText.isEmpty()) 0 else dueText.toIntOrNull()
        if (dueDay == null || (dueDay != 0 && dueDay !in 1..31)) {
            due.error = "Use a day from 1 to 31"
            due.requestFocus()
            return@formSheet
        }
        // Limit, due day and sharing are kept on this phone only; the server never needs them.
        meta.save(
            account.id, limitMinor, dueDay,
            checks.filter { it.second.isChecked }.map { it.first.id }.toSet(),
            accounts.map { it.id }
        )
        val newIds = meta.sharedWith(account.id, accounts.map { it.id })
        meta.setAvailable(account.id, availMinor, accounts.filter { it.id in newIds }.sumOf { it.balanceMinor })
        sheet.dismiss()
        onSaved()
    }
}

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
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        )
    }

    fun renderSummaries() = rows.forEach { renderCardSummary(it.summary, it.account, accounts, meta) }

    val scrollView = dialogContent {
        rows.forEach { row ->
            addView(tile {
                // Tap the name to set the limit, due day and shared limit.
                addView(accountLabel(row.account, dp(2), dp(6)).apply {
                    text = "$text  ▾"
                    setOnClickListener { showCardSettingsDialog(row.account, accounts, meta) { renderSummaries() } }
                })
                addView(row.summary)

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
            })
        }
    }
    renderSummaries()

    formSheet(title = title, subtitle = "Tap a card name to set its limit and due date", body = scrollView) { sheet ->
        val values = mutableListOf<Triple<OracleAccount, Long, Long>>()
        for (row in rows) {
            val bill = MoneyInput.parseMinor(row.bill.text.toString())
            val active = MoneyInput.parseMinor(row.active.text.toString(), allowNegative = true)
            if (bill == null) {
                row.bill.error = "Enter a valid bill amount"
                row.bill.requestFocus()
                return@formSheet
            }
            if (active == null) {
                row.active.error = "Enter a valid active amount"
                row.active.requestFocus()
                return@formSheet
            }
            values += Triple(row.account, bill, active)
        }
        saveRows(sheet, values, "Credit card amounts updated", "Could not update credit cards: ") { client, (account, bill, active) ->
            client.updateAccountBalance(
                account = account,
                balanceMinor = bill + active,
                billBalanceMinor = bill
            )
        }
    }
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

    formSheet(title = title, subtitle = "Manually set the current balance for each bank account.", body = scrollView) { sheet ->
        val values = mutableListOf<Pair<OracleAccount, Long>>()
        for ((account, input) in inputs) {
            val amount = MoneyInput.parseMinor(input.text.toString())
            if (amount == null) {
                input.error = "Enter a valid amount"
                input.requestFocus()
                return@formSheet
            }
            values += account to amount
        }
        saveRows(sheet, values, "Account balances updated", "Could not update accounts: ") { client, (account, balance) ->
            client.updateAccountBalance(account, balance)
        }
    }
}
