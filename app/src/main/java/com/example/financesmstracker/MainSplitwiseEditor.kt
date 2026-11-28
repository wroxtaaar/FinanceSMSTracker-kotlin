package com.example.financesmstracker

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.integration.ExpenseCheckItem
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.ExpenseCheckText
import com.example.financesmstracker.integration.SplitwiseChangesText
import java.util.Locale

/** Editing the single Splitwise total that the home screen starts from. */
internal fun MainActivity.showManualSplitwiseDialog() {
    buttonEditSplitwise.isEnabled = false
    oracleExecutor.execute {
        val result = FinanceSyncClient(this@showManualSplitwiseDialog).fetchManualSplitwiseTotal()
        runOnUiThread {
            buttonEditSplitwise.isEnabled = true
            result.onSuccess { currentMinor ->
                val input = EditText(this@showManualSplitwiseDialog).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(true)
                    hint = "Splitwise amount"
                    textSize = 26f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setText(String.format(Locale.getDefault(), "%.2f", currentMinor / 100.0))
                    selectAll()
                }

                val container = LinearLayout(this@showManualSplitwiseDialog).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(4), dp(24), 0)
                    addView(caption("Amount owed to you"))
                    addView(input)
                }

                // The long explanation stays one tap away instead of filling the sheet.
                val explanation = caption(
                    "Set it once; after that it moves with every transaction. Spending adds to it, " +
                        "money coming in subtracts, and transfers between your own accounts cancel out. " +
                        "Category OTHER keeps a transaction out."
                ).apply {
                    visibility = android.view.View.GONE
                    setPadding(0, dp(6), 0, 0)
                }
                container.addView(caption("How this works ›").apply {
                    textSize = 13f
                    setPadding(0, dp(14), 0, 0)
                    setOnClickListener {
                        explanation.visibility =
                            if (explanation.visibility == android.view.View.VISIBLE) android.view.View.GONE
                            else android.view.View.VISIBLE
                    }
                })
                container.addView(explanation)

                formSheet(
                    title = "Splitwise owed",
                    subtitle = "Set it once. It then moves with every transaction.",
                    body = container,
                    neutralText = "Changes since set",
                    onNeutral = { showSplitwiseChangesDialog() }
                ) { sheet ->
                    val amount = input.text.toString().trim().replace(",", "").toDoubleOrNull()

                    if (amount == null || amount < 0) {
                        input.error = "Enter a valid amount"
                        input.requestFocus()
                        return@formSheet
                    }

                    val amountMinor = kotlin.math.round(amount * 100.0).toLong()
                    buttonEditSplitwise.isEnabled = false

                    oracleExecutor.execute {
                        val saveResult = FinanceSyncClient(this@showManualSplitwiseDialog)
                            .updateManualSplitwiseTotal(amountMinor)

                        runOnUiThread {
                            buttonEditSplitwise.isEnabled = true
                            saveResult.onSuccess {
                                sheet.dismiss()
                                loadOracleSummary()
                                showSnack("Splitwise owed updated")
                            }.onFailure { error ->
                                Toast.makeText(
                                    this@showManualSplitwiseDialog,
                                    "Could not update Splitwise: " +
                                        (error.message ?: "Unavailable"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@showManualSplitwiseDialog,
                    "Could not load Splitwise amount: " +
                        (error.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/** The Splitwise tile: what the Expense app says you are owed (or owe), group by group. */
internal fun MainActivity.showSplitwiseGroupsSheet() {
    val client = FinanceSyncClient(this)
    // Open at once from the copy kept on this phone; the server's own copy then refreshes it in a moment.
    var people: List<com.example.financesmstracker.integration.ExpenseBalancePerson> = client.cachedExpenseBalances().orEmpty()
    val prefs = getSharedPreferences("expense_balance_view", android.content.Context.MODE_PRIVATE)
    var shownId: String? = prefs.getString("person", null)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val owedColor = com.google.android.material.color.MaterialColors.getColor(
        list, R.attr.creditColor, android.graphics.Color.parseColor("#2E9E5B")
    )
    val oweColor = com.google.android.material.color.MaterialColors.getColor(
        list, R.attr.debitColor, android.graphics.Color.parseColor("#D64545")
    )
    var loading = people.isEmpty()
    var sheetRef: FormSheet? = null
    lateinit var render: () -> Unit

    fun amountRow(name: String, net: Long, bold: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(android.widget.TextView(context).apply {
            text = name
            textSize = 15f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(android.widget.TextView(context).apply {
            text = when {
                net > 0 -> "you are owed " + formatMinor(net)
                net < 0 -> "you owe " + formatMinor(-net)
                else -> "settled up"
            }
            textSize = if (bold) 15f else 14f
            setTextColor(if (net > 0) owedColor else if (net < 0) oweColor else currentTextColor)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            alpha = if (net == 0L) 0.6f else 1f
        })
    }

    render = {
        list.removeAllViews()
        val shown = people.firstOrNull { it.id == shownId } ?: people.maxByOrNull { it.totalMinor }
        if (shown == null) {
            list.addView(caption(if (loading) "Loading…" else "No data from the Expense app yet.").apply {
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            })
        } else {
            if (people.size > 1) {
                list.addView(caption("Showing for ${shown.name.ifBlank { "this account" }}  ›").apply {
                    textSize = 13f
                    setPadding(0, 0, 0, dp(6))
                    setOnClickListener {
                        val next = people[(people.indexOf(shown) + 1) % people.size]
                        shownId = next.id
                        prefs.edit().putString("person", next.id).apply()
                        render()
                    }
                })
            }
            list.addView(tile {
                if (shown.groups.isEmpty()) addView(caption("No expenses in the Expense app yet."))
                shown.groups.forEach { addView(amountRow(it.name, it.netMinor, false)) }
            })
            list.addView(tile { addView(amountRow("Total", shown.totalMinor, true)) })
        }
        if (shown != null) {
            list.addView(caption("Sync the home Splitwise total from the Expense app  ›").apply {
                textSize = 13f
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { syncSplitwiseFromExpense(shown.id) { sheetRef?.dismiss() } }
            })
        }
        list.addView(caption("Edit the Splitwise total used on the home screen  ›").apply {
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { showManualSplitwiseDialog() }
        })
        list.addView(caption("Changes since the total was set  ›").apply {
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { showSplitwiseChangesDialog() }
        })
    }
    render()

    val sheet = formSheet(
        title = "Splitwise",
        subtitle = "As recorded in the Expense app",
        body = androidx.core.widget.NestedScrollView(this).apply {
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(list)
        },
        positiveText = "Done"
    ) { it.dismiss() }
    sheetRef = sheet

    oracleExecutor.execute {
        val result = FinanceSyncClient(this@showSplitwiseGroupsSheet).fetchExpenseBalances()
        runOnUiThread {
            loading = false
            result.onSuccess { fresh ->
                people = fresh
                render()
            }.onFailure {
                if (people.isEmpty()) {
                    sheet.dismiss()
                    showManualSplitwiseDialog() // Expense app not reachable and nothing saved: the single total
                }
            }
        }
    }
}

/**
 * One-way: copies what the Expense app says you are owed into this app's Splitwise total. Cash spends typed
 * only in the Expense app are picked up this way. Nothing is ever written to the Expense app.
 */
internal fun MainActivity.syncSplitwiseFromExpense(personId: String, onDone: () -> Unit = {}) {
    showSnack("Reading the Expense app…")
    oracleExecutor.execute {
        val client = FinanceSyncClient(this@syncSplitwiseFromExpense)
        val fresh = client.refreshExpenseBalances()
        val current = client.fetchManualSplitwiseTotal()
        runOnUiThread {
            val person = fresh.getOrNull()?.firstOrNull { it.id == personId }
            if (person == null || current.isFailure) {
                Toast.makeText(
                    this@syncSplitwiseFromExpense,
                    "Could not read the Expense app: " + (fresh.exceptionOrNull()?.message ?: current.exceptionOrNull()?.message ?: "unavailable"),
                    Toast.LENGTH_LONG
                ).show()
                return@runOnUiThread
            }
            val old = current.getOrDefault(0L)
            val new = person.totalMinor
            if (old == new) {
                showSnack("Already in sync: " + formatMinor(new))
                return@runOnUiThread
            }
            MaterialAlertDialogBuilder(this@syncSplitwiseFromExpense)
                .setTitle("Sync Splitwise total")
                .setMessage(
                    "Home Splitwise total: ${formatMinor(old)}\nExpense app (${person.name.ifBlank { "this account" }}): ${formatMinor(new)}\n\n" +
                        "Only this app changes. Nothing is written to the Expense app."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set to ${formatMinor(new)}") { _, _ ->
                    oracleExecutor.execute {
                        val saved = FinanceSyncClient(this@syncSplitwiseFromExpense).updateManualSplitwiseTotal(new)
                        runOnUiThread {
                            saved.onSuccess {
                                onDone()
                                loadOracleSummary()
                                showSnack("Splitwise total synced from Expense: " + formatMinor(new))
                            }.onFailure { error ->
                                Toast.makeText(
                                    this@syncSplitwiseFromExpense, "Could not update: " + (error.message ?: "unavailable"), Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }
    }
}

/** Lists what the app counted since the Splitwise total was last set, to compare with the Splitwise app. */
internal fun MainActivity.showSplitwiseChangesDialog() {
    oracleExecutor.execute {
        val client = FinanceSyncClient(this@showSplitwiseChangesDialog)
        val result = client.fetchSplitwiseChanges()
        val expense = client.fetchExpenseCheck()
        runOnUiThread {
            result.onSuccess { changes ->
                val expenseText = expense.getOrNull()?.let { ExpenseCheckText.format(it, { m -> formatMinor(m) }) }
                    ?: ("Expense check unavailable: " + (expense.exceptionOrNull()?.message ?: "unknown"))
                val details = expenseText + "\n\n— — —\n\n" + SplitwiseChangesText.format(changes, { formatMinor(it) })
                val check = expense.getOrNull()
                if (check != null && check.configured && check.error == null && check.missing.isNotEmpty()) {
                    showMissingSwipeDialog(check.missing, check.matched, check.handled, details)
                } else {
                    showDetailsDialog(details)
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@showSplitwiseChangesDialog,
                    "Could not load changes: " + (error.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

private fun MainActivity.showDetailsDialog(text: String) {
    val body = android.widget.TextView(this).apply {
        this.text = text
        setTextIsSelectable(true)
        setPadding(dp(24), dp(12), dp(24), 0)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Splitwise check")
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setPositiveButton("Close", null)
        .show()
}

/**
 * The "not in Expense yet" list: swipe a row right to add it, left to skip it
 * (the same as the Add to Expense / Handled buttons on the transaction itself).
 */
private fun MainActivity.showMissingSwipeDialog(
    initial: List<ExpenseCheckItem>,
    matched: Int,
    handled: Int,
    details: String
) {
    val items = initial.toMutableList()
    val date = java.text.SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault())
    lateinit var dialog: AlertDialog
    val header = android.widget.TextView(this).apply {
        textSize = 13f
        alpha = 0.75f
        setPadding(dp(24), dp(4), dp(24), dp(8))
    }
    fun refreshHeader() {
        header.text = "${items.size} not in Expense yet (${formatMinor(items.sumOf { it.amountMinor })}) · " +
            "$matched added" + (if (handled > 0) ", $handled skipped" else "") +
            "\nSwipe right to add · swipe left to skip"
    }
    refreshHeader()

    val list = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context)
        setPadding(dp(16), 0, dp(16), 0)
        clipToPadding = false
    }
    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = tile {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                addView(android.widget.TextView(context).apply { id = android.R.id.text1; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                addView(android.widget.TextView(context).apply { id = android.R.id.text2; textSize = 12f; alpha = 0.7f })
            }
            row.layoutParams = RecyclerView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.text1).text =
                formatMinor(item.amountMinor) + "  " + item.label
            holder.itemView.findViewById<android.widget.TextView>(android.R.id.text2).text = date.format(java.util.Date(item.timestamp))
        }
    }
    list.adapter = adapter
    val help = android.widget.LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header)
        addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.5).toInt()))
    }

    val swipe = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val position = holder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val item = items[position]
            if (direction == ItemTouchHelper.RIGHT) {
                adapter.notifyItemChanged(position) // snap back; the add dialog does the work
                val tx = repository.getAllTransactions().firstOrNull { oracleIdOf(it) == item.id }
                if (tx == null) {
                    Toast.makeText(this@showMissingSwipeDialog, "That row is not on this phone", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    showAddToExpenseDialog(listOf(tx))
                }
            } else {
                oracleExecutor.execute {
                    val r = FinanceSyncClient(this@showMissingSwipeDialog).markExpenseHandled(item.id, true)
                    runOnUiThread {
                        r.onSuccess {
                            val index = items.indexOf(item)
                            if (index >= 0) {
                                items.removeAt(index)
                                adapter.notifyItemRemoved(index)
                            }
                            refreshHeader()
                            Toast.makeText(this@showMissingSwipeDialog, "Skipped", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            adapter.notifyItemChanged(position)
                            Toast.makeText(this@showMissingSwipeDialog, friendlyExpenseError(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    })
    swipe.attachToRecyclerView(list)

    dialog = MaterialAlertDialogBuilder(this)
        .setTitle("Not in Expense yet")
        .setView(help)
        .setNeutralButton("Details") { _, _ -> showDetailsDialog(details) }
        .setPositiveButton("Close", null)
        .create()
    dialog.show()
}
