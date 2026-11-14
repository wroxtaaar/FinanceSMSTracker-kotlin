package com.example.financesmstracker

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.example.financesmstracker.integration.FinanceSyncClient
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
                    setText(String.format(Locale.getDefault(), "%.2f", currentMinor / 100.0))
                    selectAll()
                }

                val container = LinearLayout(this@showManualSplitwiseDialog).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 0, 48, 0)
                    addView(input)
                }

                val dialog = AlertDialog.Builder(this@showManualSplitwiseDialog)
                    .setTitle("Splitwise Owed")
                    .setMessage(
                        "This is the amount you set. It then changes automatically with every " +
                            "transaction: money leaving a bank account or a card spend adds to it, " +
                            "money coming in subtracts from it, and transfers between your own " +
                            "accounts cancel out. Categories do not change it, except OTHER, which keeps a transaction out of Splitwise."
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
        val result = FinanceSyncClient(this@showSplitwiseChangesDialog).fetchSplitwiseChanges()
        runOnUiThread {
            result.onSuccess { changes ->
                val text = SplitwiseChangesText.format(changes, { formatMinor(it) })
                val body = android.widget.TextView(this@showSplitwiseChangesDialog).apply {
                    this.text = text
                    setTextIsSelectable(true)
                    setPadding(48, 24, 48, 0)
                }
                val scroll = android.widget.ScrollView(this@showSplitwiseChangesDialog).apply { addView(body) }
                AlertDialog.Builder(this@showSplitwiseChangesDialog)
                    .setTitle("Changes since you set it")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show()
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
