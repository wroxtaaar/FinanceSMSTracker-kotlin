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

                val dialog = MaterialAlertDialogBuilder(this@showManualSplitwiseDialog)
                    .setTitle("Splitwise Owed")
                    .setMessage(
                        "Set it once; after that it moves with every transaction. Spending adds to it, " +
                            "money coming in subtracts, and transfers between your own accounts cancel out. " +
                            "Category OTHER keeps a transaction out."
                    )
                    .setView(container)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Changes since set", null)
                    .setPositiveButton("Save", null)
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        showSplitwiseChangesDialog()
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val amount = input.text.toString().trim().replace(",", "").toDoubleOrNull()

                        if (amount == null || amount < 0) {
                            input.error = "Enter a valid amount"
                            input.requestFocus()
                            return@setOnClickListener
                        }

                        val amountMinor = kotlin.math.round(amount * 100.0).toLong()
                        buttonEditSplitwise.isEnabled = false

                        oracleExecutor.execute {
                            val saveResult = FinanceSyncClient(this@showManualSplitwiseDialog)
                                .updateManualSplitwiseTotal(amountMinor)

                            runOnUiThread {
                                buttonEditSplitwise.isEnabled = true
                                saveResult.onSuccess {
                                    dialog.dismiss()
                                    loadOracleSummary()
                                    Toast.makeText(
                                        this@showManualSplitwiseDialog,
                                        "Splitwise owed updated",
                                        Toast.LENGTH_SHORT
                                    ).show()
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
                }

                dialog.show()
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
            val position = holder.bindingAdapterPosition
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
