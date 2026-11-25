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
import com.example.financesmstracker.integration.SplitsLink
import com.example.financesmstracker.integration.SplitsOptions
import com.example.financesmstracker.parser.TransactionType
import java.util.Locale

private const val SPLITS_PREFS = "splits_prefs"
private const val KEY_LAST_GROUP = "last_group"
private const val KEY_LAST_PAYER = "last_payer"

private fun MainActivity.oracleIdOf(tx: Transaction): String =
    FinanceSyncBridge.oracleVoidId(tx.id, repository.getSmsHashById(tx.id), OracleIds.scheme(this))

private fun rupees(minor: Long): String = "₹" + String.format(Locale.US, "%.2f", minor / 100.0)

/** The server answers with {"detail": "..."}; show just the sentence. */
internal fun friendlySplitsError(error: Throwable?): String {
    val message = error?.message ?: return "Unavailable"
    return Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(message)?.groupValues?.get(1) ?: message
}

/** Long-press rows in the history to select them; the bar sends them to Splits as one expense. */
internal fun MainActivity.setupSplitsSelection() {
    val bar = findViewById<View>(R.id.selectionBar)
    val count = findViewById<TextView>(R.id.selectionCount)
    adapter.onSelectionChanged = { n ->
        bar.visibility = if (n > 0) View.VISIBLE else View.GONE
        val total = adapter.selectedTransactions().sumOf { it.amountPaise.toLong() }
        count.text = "$n selected · ${rupees(total)}"
    }
    findViewById<Button>(R.id.selectionAddToSplits).setOnClickListener {
        val chosen = adapter.selectedTransactions()
        if (chosen.isNotEmpty()) showAddToSplitsDialog(chosen)
    }
    findViewById<Button>(R.id.selectionCancel).setOnClickListener { adapter.clearSelection() }
}

/** "Add to Splits" / "Skip" buttons on a single transaction. Hidden unless the server says it can be added. */
internal fun MainActivity.setupSplitsButtons(dialogView: View, tx: Transaction) {
    if (tx.transactionType == TransactionType.CREDIT) return
    val add = dialogView.findViewById<Button>(R.id.buttonAddToSplits)
    val skip = dialogView.findViewById<Button>(R.id.buttonSplitsHandled)
    val id = oracleIdOf(tx)

    fun render(link: SplitsLink) {
        if (!link.configured || !link.eligible) return
        add.visibility = View.VISIBLE
        add.text = when {
            !link.linked -> "Add to Splits"
            link.together > 1 -> "In Splits with ${link.together - 1} other transaction(s) · edit"
            else -> "In Splits · edit"
        }
        skip.visibility = if (link.linked) View.GONE else View.VISIBLE
        skip.text = if (link.handled) "Skipped · tap to undo" else "Skip: already in Splits / not for Splits"
        skip.setOnClickListener {
            skip.isEnabled = false
            oracleExecutor.execute {
                val client = FinanceSyncClient(this@setupSplitsButtons)
                client.markSplitsHandled(id, !link.handled)
                val fresh = client.fetchSplitsLink(id).getOrNull()
                runOnUiThread {
                    skip.isEnabled = true
                    fresh?.let { render(it) }
                }
            }
        }
    }

    add.setOnClickListener { showAddToSplitsDialog(listOf(tx)) }
    oracleExecutor.execute {
        val link = FinanceSyncClient(this@setupSplitsButtons).fetchSplitsLink(id).getOrNull()
        runOnUiThread { link?.let { render(it) } }
    }
}

/** Asks the user what was bought, then adds one Splits expense for all [txs] (their total). */
internal fun MainActivity.showAddToSplitsDialog(txs: List<Transaction>) {
    if (txs.any { it.transactionType == TransactionType.CREDIT }) {
        Toast.makeText(this, "Only expenses can be added to Splits", Toast.LENGTH_SHORT).show()
        return
    }
    val ids = txs.map { oracleIdOf(it) }
    oracleExecutor.execute {
        val client = FinanceSyncClient(this@showAddToSplitsDialog)
        val options = client.fetchSplitsOptions()
        val link = if (ids.size == 1) client.fetchSplitsLink(ids[0]).getOrNull() else null
        runOnUiThread {
            options.onSuccess { opts -> buildSplitsDialog(txs, ids, opts, link) }
                .onFailure {
                    Toast.makeText(
                        this@showAddToSplitsDialog, "Splits unavailable: " + friendlySplitsError(it), Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
}

private fun MainActivity.buildSplitsDialog(
    txs: List<Transaction>,
    ids: List<String>,
    opts: SplitsOptions,
    link: SplitsLink?
) {
    val users = opts.people.filter { it.isUser }.ifEmpty { opts.people }
    if (users.isEmpty()) {
        Toast.makeText(this, "Splits has no people yet. Open the Splits app once.", Toast.LENGTH_LONG).show()
        return
    }
    val prefs = getSharedPreferences(SPLITS_PREFS, android.content.Context.MODE_PRIVATE)
    val pad = (16 * resources.displayMetrics.density).toInt()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
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
            this@buildSplitsDialog, android.R.layout.simple_spinner_dropdown_item,
            listOf("No group") + opts.groups.map { it.name }
        )
    }
    val payerSpinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@buildSplitsDialog, android.R.layout.simple_spinner_dropdown_item, users.map { it.name.ifBlank { "Someone" } }
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
    val builder = AlertDialog.Builder(this)
        .setTitle(if (linked) "Update in Splits" else "Add to Splits")
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton("Cancel", null)
        .setPositiveButton(if (linked) "Update" else "Add", null)
    if (linked) builder.setNeutralButton("Remove from Splits", null)
    val dialog = builder.create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val group = opts.groups.getOrNull(groupSpinner.selectedItemPosition - 1)
            val payer = users[payerSpinner.selectedItemPosition]
            val text = items.text.toString()
            button.isEnabled = false
            oracleExecutor.execute {
                val result = FinanceSyncClient(this@buildSplitsDialog).addToSplits(ids, text, group?.id, payer.id)
                runOnUiThread {
                    button.isEnabled = true
                    result.onSuccess {
                        prefs.edit().putString(KEY_LAST_GROUP, group?.id).putString(KEY_LAST_PAYER, payer.id).apply()
                        Toast.makeText(this@buildSplitsDialog, "Added to Splits", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        adapter.clearSelection()
                    }.onFailure {
                        Toast.makeText(
                            this@buildSplitsDialog, "Could not add: " + friendlySplitsError(it), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        if (linked) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                oracleExecutor.execute {
                    val result = FinanceSyncClient(this@buildSplitsDialog).removeFromSplits(ids[0])
                    runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(this@buildSplitsDialog, "Removed from Splits", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }.onFailure {
                            Toast.makeText(
                                this@buildSplitsDialog, "Could not remove: " + friendlySplitsError(it), Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }
    dialog.show()
}
