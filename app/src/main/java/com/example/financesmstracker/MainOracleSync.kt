package com.example.financesmstracker

import android.app.AlertDialog
import android.content.Context
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.OracleIds
import com.example.financesmstracker.integration.SyncSettings

/**
 * Account names from the server may carry the last four digits ("... 1175")
 * and inconsistent bank casing. The last four are shown separately, so strip
 * them here and keep well-known bank acronyms in capitals.
 */
/**
 * Pull back transactions the server holds that this phone lacks (after a
 * reinstall, say). Respects Clear Local History and never re-sends anything.
 */
internal fun MainActivity.restoreHistoryFromServer() {
    buttonRestoreHistory.isEnabled = false
    buttonRestoreHistory.text = "Restoring..."
    oracleExecutor.execute {
        val result = FinanceSyncClient(this@restoreHistoryFromServer).fetchAllOracleTransactions()
        runOnUiThread {
            result.onSuccess { remote ->
                val clearedAt = localHistoryPrefs.getLong(MainActivity.KEY_LOCAL_HISTORY_CLEARED_AT, 0L)
                val clearedIds = localHistoryPrefs.getStringSet(
                    MainActivity.KEY_LOCAL_HISTORY_CLEARED_ORACLE_IDS, emptySet()
                ) ?: emptySet()
                val restored = repository.restoreMissingFromOracle(
                    remote, OracleIds.scheme(this@restoreHistoryFromServer)
                ) { tx -> tx.id in clearedIds || (clearedAt > 0L && tx.timestamp <= clearedAt) }
                Toast.makeText(
                    this@restoreHistoryFromServer,
                    "Server has ${remote.size} transactions; restored $restored missing",
                    Toast.LENGTH_LONG
                ).show()
                syncOracleGmailTransactions()
                loadOracleSummary()
                loadTransactions()
            }.onFailure { error ->
                Toast.makeText(
                    this@restoreHistoryFromServer,
                    "Restore failed: " + (error.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
            buttonRestoreHistory.isEnabled = true
            buttonRestoreHistory.text = "Restore history"
        }
    }
}

internal fun MainActivity.showSmsHandlingLog() {
    val smsText = com.example.financesmstracker.receiver.SmsHandlingLog.format(
        com.example.financesmstracker.receiver.SmsHandlingLog.read(this)
    )
    val gmailText = com.example.financesmstracker.receiver.SmsHandlingLog.readGmailScan(this)
        ?: "No Gmail scan yet. Tap Sync Gmail."
    val text = "SMS handling (newest first)\n\n" + smsText +
        "\n\n────────────\n\n" + gmailText
    val view = android.widget.TextView(this).apply {
        this.text = text
        setTextIsSelectable(true)
        setPadding(48, 24, 48, 24)
        textSize = 13f
    }
    AlertDialog.Builder(this)
        .setTitle("Logs: SMS and Gmail scan")
        .setView(android.widget.ScrollView(this).apply { addView(view) })
        .setPositiveButton("Close", null)
        .setNeutralButton("Copy") { _, _ ->
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SMS log", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        .show()
}

internal fun MainActivity.showOracleSettingsDialog() {
    val settings = SyncSettings(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 8, 48, 0)
    }

    val urlInput = EditText(this).apply {
        hint = "Oracle URL"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setText(settings.baseUrl())
    }

    val tokenInput = EditText(this).apply {
        hint = "Oracle sync token"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        if (settings.token().isNotBlank()) {
            setText(settings.token())
        }
    }

    container.addView(urlInput)
    container.addView(tokenInput)

    val dialog = AlertDialog.Builder(this)
        .setTitle("Oracle Sync Settings")
        .setMessage("Use your private Tailscale/Serve URL. The token is stored only in this app's private preferences.")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save & Test", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val token = tokenInput.text.toString()

            if (baseUrl.isBlank()) {
                urlInput.error = "Enter the Oracle URL"
                return@setOnClickListener
            }

            if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                urlInput.error = "Use an http:// or https:// URL"
                return@setOnClickListener
            }

            if (token.isBlank()) {
                tokenInput.error = "Enter the Oracle sync token"
                return@setOnClickListener
            }

            settings.save(baseUrl, token)
            dialog.dismiss()
            loadOracleSummary()

            oracleExecutor.execute {
                val result = FinanceSyncClient(this@showOracleSettingsDialog).fetchSummary()
                runOnUiThread {
                    result.onSuccess {
                        renderOracleSummary(it)
                        Toast.makeText(
                            this@showOracleSettingsDialog,
                            "Oracle settings saved and connection verified",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { error ->
                        textViewOracleStatus.text =
                            "Oracle ledger: " + (error.message ?: "Connection failed")
                        Toast.makeText(
                            this@showOracleSettingsDialog,
                            "Settings saved, but Oracle connection failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    dialog.show()
}

internal fun MainActivity.triggerManualGmailSync() {
    buttonGmailSync.isEnabled = false
    buttonGmailSync.text = "Syncing..."

    oracleExecutor.execute {
        val result = FinanceSyncClient(this@triggerManualGmailSync).triggerGmailSync()

        runOnUiThread {
            buttonGmailSync.isEnabled = true
            buttonGmailSync.text = "Sync Gmail"

            result.onSuccess { summary ->
                com.example.financesmstracker.receiver.SmsHandlingLog.saveGmailScan(
                    this@triggerManualGmailSync,
                    com.example.financesmstracker.receiver.SmsHandlingLog.formatGmailScan(
                        summary.searchSummary, summary.messageLog
                    )
                )
                Toast.makeText(
                    this@triggerManualGmailSync,
                    "Gmail: scanned ${summary.messagesScanned}, new transactions " +
                        "${summary.parsedTransactions}, duplicates ${summary.duplicateTransactions}",
                    Toast.LENGTH_LONG
                ).show()
                syncOracleGmailTransactions()
                loadOracleSummary()
                loadTransactions()
            }.onFailure { error ->
                // A 403 here means Gmail is switched off on the Oracle server
                // (GMAIL_ENABLED in its .env).
                Toast.makeText(
                    this@triggerManualGmailSync,
                    "Gmail sync failed: " + (error.message ?: "Unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

internal fun MainActivity.syncOracleGmailTransactions() {
    oracleExecutor.execute {
        val result = FinanceSyncClient(this@syncOracleGmailTransactions).fetchGmailTransactions()

        runOnUiThread {
            result.onSuccess { transactions ->
                val clearedAt = localHistoryPrefs.getLong(MainActivity.KEY_LOCAL_HISTORY_CLEARED_AT, 0L)
                val clearedOracleIds =
                    localHistoryPrefs.getStringSet(
                        MainActivity.KEY_LOCAL_HISTORY_CLEARED_ORACLE_IDS,
                        emptySet()
                    ) ?: emptySet()
                var changed = 0

                transactions.forEach { transaction ->
                    // Clear Local History is intentionally local-only. Oracle
                    // keeps Gmail processing history, but rows that existed
                    // before the clear must never repopulate the phone.
                    if (
                        transaction.id.toString() in clearedOracleIds ||
                        (clearedAt > 0L && transaction.timestamp <= clearedAt)
                    ) {
                        return@forEach
                    }

                    val rowId = repository.upsertOracleGmailTransaction(transaction)
                    if (rowId != 0L) {
                        changed++

                        // Pulling an Oracle Gmail row is a read/rehydration
                        // operation. Do not enqueue the resolved local row back
                        // to Oracle merely because the app was reopened.
                        // Re-sending here can replay metadata/category changes
                        // and make the mutable Splitwise aggregate drift on
                        // every startup. User edits and new local evidence have
                        // their own explicit sync paths.
                        Unit
                    }
                }

                if (changed > 0) {
                    loadTransactions()
                }
            }.onFailure {
                // The local transaction list remains usable when Oracle is
                // temporarily unavailable. The next resume or Gmail sync
                // retries the pull.
            }
        }
    }
}
