package com.example.financesmstracker

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.OracleIds
import com.example.financesmstracker.integration.ExpenseLink
import com.example.financesmstracker.integration.ExpenseOptions
import com.example.financesmstracker.parser.TransactionType
import java.util.Locale

private const val EXPENSE_PREFS = "expense_prefs"
private const val KEY_LAST_GROUP = "last_group"
private const val KEY_LAST_PAYER = "last_payer"

internal fun MainActivity.oracleIdOf(tx: Transaction): String =
    FinanceSyncBridge.oracleVoidId(tx.id, repository.getSmsHashById(tx.id), OracleIds.scheme(this))

private fun rupees(minor: Long): String = "₹" + String.format(Locale.US, "%.2f", minor / 100.0)

/** The server answers with {"detail": "..."}; show just the sentence. */
internal fun friendlyExpenseError(error: Throwable?): String {
    val message = error?.message ?: return "Unavailable"
    return Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(message)?.groupValues?.get(1) ?: message
}

/** Long-press rows in the history to select them; the bar sends them to Expense as one expense. */
internal fun MainActivity.setupExpenseSelection() {
    val bar = findViewById<View>(R.id.selectionBar)
    val count = findViewById<TextView>(R.id.selectionCount)
    styleSelectionBar(bar, count)
    // Back cancels a selection instead of leaving the app.
    val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            adapter.clearSelection()
        }
    }
    onBackPressedDispatcher.addCallback(this, backCallback)
    adapter.onSelectionChanged = { n ->
        backCallback.isEnabled = n > 0
        bar.visibility = if (n > 0) View.VISIBLE else View.GONE
        val total = adapter.selectedTransactions().sumOf { it.amountPaise.toLong() }
        count.text = "$n selected · ${rupees(total)}"
    }
    findViewById<Button>(R.id.selectionAddToExpense).setOnClickListener {
        val chosen = adapter.selectedTransactions()
        if (chosen.isNotEmpty()) showAddToExpenseDialog(chosen)
    }
    findViewById<Button>(R.id.selectionCancel).setOnClickListener { adapter.clearSelection() }
}

/** "Add to Expense" / "Skip" buttons on a single transaction. Hidden unless the server says it can be added. */
internal fun MainActivity.setupExpenseButtons(dialogView: View, tx: Transaction) {
    if (tx.transactionType == TransactionType.CREDIT) return
    val add = dialogView.findViewById<Button>(R.id.buttonAddToExpense)
    val skip = dialogView.findViewById<Button>(R.id.buttonExpenseHandled)
    val id = oracleIdOf(tx)

    fun render(link: ExpenseLink) {
        if (!link.configured || !link.eligible) return
        add.visibility = View.VISIBLE
        add.text = when {
            !link.linked -> "Add to Expense"
            link.together > 1 -> "In Expense with ${link.together - 1} other transaction(s) · edit"
            else -> "In Expense · edit"
        }
        skip.visibility = if (link.linked) View.GONE else View.VISIBLE
        skip.text = if (link.handled) "Skipped · tap to undo" else "Skip: already in Expense / not for Expense"
        skip.setOnClickListener {
            skip.isEnabled = false
            oracleExecutor.execute {
                val client = FinanceSyncClient(this@setupExpenseButtons)
                client.markExpenseHandled(id, !link.handled)
                val fresh = client.fetchExpenseLink(id).getOrNull()
                runOnUiThread {
                    skip.isEnabled = true
                    fresh?.let { render(it) }
                }
            }
        }
    }

    add.setOnClickListener { showAddToExpenseDialog(listOf(tx)) }
    oracleExecutor.execute {
        val link = FinanceSyncClient(this@setupExpenseButtons).fetchExpenseLink(id).getOrNull()
        runOnUiThread { link?.let { render(it) } }
    }
}

/** Asks the user what was bought, then adds one Expense expense for all [txs] (their total). */
internal fun MainActivity.showAddToExpenseDialog(txs: List<Transaction>) {
    if (txs.any { it.transactionType == TransactionType.CREDIT }) {
        Toast.makeText(this, "Only expenses can be added to Expense", Toast.LENGTH_SHORT).show()
        return
    }
    if (!beginAction("add-to-expense")) return
    val ids = txs.map { oracleIdOf(it) }
    oracleExecutor.execute {
        val client = FinanceSyncClient(this@showAddToExpenseDialog)
        val options = client.fetchExpenseOptions()
        val link = if (ids.size == 1) client.fetchExpenseLink(ids[0]).getOrNull() else null
        runOnUiThread {
            endAction("add-to-expense")
            options.onSuccess { opts -> buildExpenseDialog(txs, ids, opts, link) }
                .onFailure {
                    Toast.makeText(
                        this@showAddToExpenseDialog, "Expense unavailable: " + friendlyExpenseError(it), Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
}

private fun MainActivity.buildExpenseDialog(
    txs: List<Transaction>,
    ids: List<String>,
    opts: ExpenseOptions,
    link: ExpenseLink?
) {
    val users = opts.people.filter { it.isUser }.ifEmpty { opts.people }
    if (users.isEmpty()) {
        Toast.makeText(this, "The Expense app has no people yet. Open it once.", Toast.LENGTH_LONG).show()
        return
    }
    val prefs = getSharedPreferences(EXPENSE_PREFS, android.content.Context.MODE_PRIVATE)
    val pad = (16 * resources.displayMetrics.density).toInt()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }

    val lines = txs.joinToString("\n") {
        (it.merchantName ?: it.bank ?: "Transaction") + "  " + rupees(it.amountPaise.toLong())
    }
    val total = txs.sumOf { it.amountPaise.toLong() }
    box.addView(TextView(this).apply {
        text = if (txs.size > 1) "$lines\nTotal ${rupees(total)}" else lines
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, pad / 2)
    })

    val items = EditText(this).apply {
        hint = "What was bought, one per line (e.g. 20 ka adrak)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 4
        gravity = Gravity.TOP or Gravity.START
        setText(link?.description.orEmpty())
    }
    box.addView(items)

    val groupSpinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@buildExpenseDialog, android.R.layout.simple_spinner_dropdown_item,
            listOf("No group") + opts.groups.map { it.name }
        )
    }
    val payerSpinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@buildExpenseDialog, android.R.layout.simple_spinner_dropdown_item, users.map { it.name.ifBlank { "Someone" } }
        )
    }
    val wantedGroup = link?.groupId ?: prefs.getString(KEY_LAST_GROUP, null)
    val groupIndex = opts.groups.indexOfFirst { it.id == wantedGroup }
    groupSpinner.setSelection(if (groupIndex >= 0) groupIndex + 1 else 0)
    val wantedPayer = link?.payerId ?: prefs.getString(KEY_LAST_PAYER, null)
    payerSpinner.setSelection(users.indexOfFirst { it.id == wantedPayer }.coerceAtLeast(0))

    fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, 0) }
    box.addView(label("Group")); box.addView(groupSpinner)
    box.addView(label("Paid by")); box.addView(payerSpinner)

    val linked = link?.linked == true
    formSheet(
        title = if (linked) "Update in Expense" else "Add to Expense",
        body = androidx.core.widget.NestedScrollView(this).apply { addView(box) },
        positiveText = if (linked) "Update" else "Add",
        neutralText = if (linked) "Remove" else null,
        onNeutral = {
            oracleExecutor.execute {
                val result = FinanceSyncClient(this@buildExpenseDialog).removeFromExpense(ids[0])
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this@buildExpenseDialog, "Removed from Expense", Toast.LENGTH_SHORT).show()
                        currentExpenseSheet?.dismiss()
                    }.onFailure {
                        Toast.makeText(
                            this@buildExpenseDialog, "Could not remove: " + friendlyExpenseError(it), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    ) { sheet ->
        currentExpenseSheet = sheet
        val group = opts.groups.getOrNull(groupSpinner.selectedItemPosition - 1)
        val payer = users[payerSpinner.selectedItemPosition]
        val text = items.text.toString()
        sheet.positiveEnabled = false
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@buildExpenseDialog).addToExpense(ids, text, group?.id, payer.id)
            runOnUiThread {
                sheet.positiveEnabled = true
                result.onSuccess {
                    prefs.edit().putString(KEY_LAST_GROUP, group?.id).putString(KEY_LAST_PAYER, payer.id).apply()
                    Toast.makeText(this@buildExpenseDialog, "Added to Expense", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                    adapter.clearSelection()
                }.onFailure {
                    Toast.makeText(
                        this@buildExpenseDialog, "Could not add: " + friendlyExpenseError(it), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }.also { currentExpenseSheet = it }
}

private var currentExpenseSheet: FormSheet? = null


/** A floating rounded bar in the current theme's surface colours instead of the old fixed purple strip. */
private fun MainActivity.styleSelectionBar(bar: View, count: TextView) {
    val surface = com.google.android.material.color.MaterialColors.getColor(bar, R.attr.cardSurface, android.graphics.Color.DKGRAY)
    val line = com.google.android.material.color.MaterialColors.getColor(bar, R.attr.dividerColor, android.graphics.Color.GRAY)
    val onSurface = com.google.android.material.color.MaterialColors.getColor(
        bar, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE
    )
    bar.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(surface)
        setStroke(dp(1), line)
    }
    bar.elevation = dp(8).toFloat()
    bar.setPadding(dp(18), dp(8), dp(8), dp(8))
    (bar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin = dp(12)
    count.setTextColor(onSurface)
    count.textSize = 14f
}
