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
import com.example.financesmstracker.integration.FinanceSyncService
import com.example.financesmstracker.integration.OracleIds
import com.example.financesmstracker.integration.SyncSettings
import com.example.financesmstracker.receiver.SmsHandlingLog

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
    val allEntries = SmsHandlingLog.read(this)
    val gmailText = SmsHandlingLog.readGmailScan(this) ?: "No Gmail scan yet. Tap Sync Gmail."
    var source = SmsHandlingLog.Source.ALL
    var current = 0
    val sectionsInOrder = listOf(
        "Accepted" to SmsHandlingLog.Section.ACCEPTED,
        "Review" to SmsHandlingLog.Section.REVIEW,
        "Other" to SmsHandlingLog.Section.OTHER
    )
    val view = android.widget.TextView(this).apply {
        setTextIsSelectable(true)
        textSize = 13f
    }
    fun entries() = SmsHandlingLog.fromSource(allEntries, source)
    fun currentText(): String =
        if (current >= sectionsInOrder.size) gmailText
        else SmsHandlingLog.formatSection(entries(), sectionsInOrder[current].second)

    /** A segmented control: one button per choice, the chosen one filled. */
    fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Pair<android.view.View, List<com.google.android.material.button.MaterialButton>> {
        val group = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val buttons = labels.mapIndexed { i, label ->
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = android.view.View.generateViewId()
                text = label
                textSize = 12f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                group.addView(this)
            }
        }
        group.check(buttons[selected].id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) onPick(buttons.indexOfFirst { it.id == checkedId })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        } to buttons
    }

    lateinit var tabButtons: List<com.google.android.material.button.MaterialButton>
    fun refresh() {
        val list = entries()
        sectionsInOrder.forEachIndexed { i, (label, section) ->
            tabButtons[i].text = "$label (${SmsHandlingLog.inSection(list, section).size})"
        }
        view.text = currentText()
    }
    val (tabs, tb) = segmented(sectionsInOrder.map { it.first } + "E-mail scan", 0) { current = it; refresh() }
    tabButtons = tb
    val sources = listOf(
        "All" to SmsHandlingLog.Source.ALL,
        "SMS" to SmsHandlingLog.Source.SMS,
        "Notifications" to SmsHandlingLog.Source.NOTIFICATION
    )
    val (sourceRow, _) = segmented(sources.map { it.first }, 0) { source = sources[it].second; refresh() }
    refresh()

    val body = androidx.core.widget.NestedScrollView(this).apply {
        addView(android.widget.LinearLayout(this@showSmsHandlingLog).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(tabs)
            addView(sourceRow, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(10) })
            addView(tile { addView(view) })
        })
    }
    formSheet(
        title = "Logs",
        subtitle = "SMS, notifications and e-mail, as the app handled them",
        body = body,
        positiveText = "Ledger audit",
        neutralText = "Copy",
        onNeutral = {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SMS log", currentText()))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    ) { sheet ->
        sheet.dismiss()
        showLedgerAudit()
    }
}

internal fun formatLedgerAudit(body: String): String {
    fun money(minor: Long) = String.format(java.util.Locale.US, "%.2f", minor / 100.0)
    val json = org.json.JSONObject(body)
    val out = StringBuilder("ACCOUNTS (drift = stored - opening - applied rows)\n")
    val accounts = json.getJSONArray("accounts")
    for (i in 0 until accounts.length()) {
        val a = accounts.getJSONObject(i)
        out.append("\n${a.optString("type")} ${a.optString("bank")} ${a.optString("last4")}\n")
        out.append("  balance ${money(a.getLong("balanceMinor"))}  bill ${money(a.getLong("billBalanceMinor"))}\n")
        out.append("  opening ${money(a.getLong("openingMinor"))} + applied ${money(a.getLong("appliedDeltaMinor"))}")
        out.append(" (${a.getInt("appliedRows")} rows) = ${money(a.getLong("expectedMinor"))}\n")
        out.append("  DRIFT ${money(a.getLong("driftMinor"))}  manualEditAt ${a.getLong("balanceReconciledAt")}  billLockedAt ${a.optLong("billLockedAt")}\n")
    }
    val sw = json.getJSONObject("splitwise")
    out.append("\nSPLITWISE shown ${money(sw.getLong("shownMinor"))}, ledger sum ${money(sw.getLong("ledgerSumMinor"))}\n")
    val m = sw.optJSONObject("manual")
    if (m != null) {
        out.append("  baseline ${money(m.optLong("baseline_amount_minor"))} at ledger ${money(m.optLong("baseline_ledger_minor"))}, set at ${m.optLong("baseline_set_at")}, last recalculated ${m.optLong("updated_at")}\n")
        val since = sw.optJSONObject("sinceSet")
        if (since != null) {
            out.append("  since set: ${money(since.optLong("changeMinor"))} total, ${money(since.optLong("otherChangeMinor"))} from edits to older rows\n")
            val items = since.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                out.append("    #${it.optString("id")} ${it.optString("type")} ${it.optString("bank")} ${it.optString("last4")} ${it.optString("category")} ${it.optString("description")} => ${money(it.optLong("deltaMinor"))}\n")
            }
        }
    }
    json.optJSONObject("expenseCheck")?.let { sc ->
        out.append("\nEXPENSE CHECK\n")
        out.append(com.example.financesmstracker.integration.ExpenseCheckText.format(
            com.example.financesmstracker.integration.ExpenseCheck.parse(sc), { v -> money(v) }
        ).lines().joinToString("\n") { "  $it" } + "\n")
    }
    fun rowsSection(title: String, key: String) {
        out.append("\n$title\n")
        val rows = json.optJSONArray(key) ?: return
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            out.append("\n${r.optLong("timestamp")} (added ${r.optLong("created_at")}) ${r.optString("type")} ${money(r.optLong("amount_minor"))} ${r.optString("category")}\n")
            out.append("  ${r.optString("bank")} ${r.optString("account_last4")} ${r.optString("merchant_or_payee")} [${r.optString("status")}]")
            if (!r.isNull("duplicate_of")) out.append(" dup_of ${r.optString("duplicate_of")}")
            out.append("\n  bank delta ${if (r.isNull("bank_delta")) "none" else money(r.optLong("bank_delta"))}")
            out.append(", splitwise ${money(r.optLong("sw_delta"))}\n  id ${r.optString("id")}\n")
        }
    }
    rowsSection("RECENT CREDITS (by when added)", "recentCredits")
    rowsSection("LARGEST ACTIVE ROWS", "largestRows")
    rowsSection("RECENTLY ADDED ROWS", "recentlyCreated")
    rowsSection("RECENT ROWS BY DATE", "recentTransactions")
    out.append("\nOWN-ACCOUNT TRANSFERS\n")
    json.optJSONArray("transfers")?.let { a ->
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i)
            out.append("${money(t.optLong("amount_minor"))} ${t.optString("status")} ${t.optString("reason")} added ${t.optLong("created_at")}\n  ${t.optString("debit_transaction_id")} -> ${t.optString("credit_transaction_id")}\n")
        }
    }
    out.append("\nCARD BILL EVIDENCE (newest first)\n")
    json.optJSONArray("billEvidence")?.let { a ->
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            out.append("${e.optString("source_type")} ${e.optString("bank")} ${e.optString("account_last4")}/${e.optString("account_last2")} ")
            out.append("${if (e.isNull("amount_minor")) "none" else money(e.optLong("amount_minor"))} applied=${e.optInt("applied")} observed ${e.optLong("observed_at")} added ${e.optLong("created_at")}\n")
        }
    }
    out.append("\nSTATEMENT PDFs (server)\n")
    json.optJSONArray("attachments")?.let { a ->
        for (i in 0 until a.length()) {
            val g = a.getJSONObject(i)
            out.append("${g.optLong("created_at")} ${g.optString("bank")} [${g.optString("status")}] ${g.optString("filename")} rows=${g.optInt("transaction_count")}\n")
            if (!g.isNull("error")) out.append("  error: ${g.optString("error")}\n")
        }
    }
    out.append("\nRECENT GMAIL MESSAGES (server)\n")
    json.optJSONArray("recentMails")?.let { a ->
        for (i in 0 until a.length()) {
            val g = a.getJSONObject(i)
            out.append("${g.optLong("internal_date")} [${g.optString("status")}] ${g.optString("sender")} | ${g.optString("subject")}\n")
        }
    }
    return out.toString()
}

internal fun MainActivity.showLedgerAudit() {
    Toast.makeText(this, "Loading audit...", Toast.LENGTH_SHORT).show()
    oracleExecutor.execute {
        val phoneStatus = run {
            val queued = runCatching { FinanceSyncService(this@showLedgerAudit).pendingCount() }.getOrDefault(-1)
            val last = getSharedPreferences("finance_sync_status", android.content.Context.MODE_PRIVATE)
                .getString("last_category_edit", "none yet")
            val lastError = getSharedPreferences("finance_sync_status", android.content.Context.MODE_PRIVATE)
                .getString("last_flush_error", "none")
            "PHONE SYNC\n  waiting to send: $queued\n  last send error: $lastError\n  last category edit: $last\n\n"
        }
        val result = FinanceSyncClient(this@showLedgerAudit).fetchAudit().mapCatching { phoneStatus + formatLedgerAudit(it) }
        runOnUiThread {
            result.onSuccess { text ->
                val view = android.widget.TextView(this).apply {
                    this.text = text
                    setTextIsSelectable(true)
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                formSheet(
                    title = "Ledger audit",
                    subtitle = "Read-only",
                    body = androidx.core.widget.NestedScrollView(this).apply {
                        addView(android.widget.LinearLayout(this@showLedgerAudit).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(dp(20), dp(4), dp(20), dp(8))
                            addView(tile { addView(view) })
                        })
                    },
                    positiveText = "Copy",
                    neutralText = null
                ) { _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger audit", text))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(this, "Audit failed: " + (error.message ?: "Unavailable"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.showOracleSettingsDialog() {
    val settings = SyncSettings(this)
    val primary = com.google.android.material.color.MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary, android.graphics.Color.CYAN
    )

    val urlInput = EditText(this).apply {
        hint = "https://your-server"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setText(settings.baseUrl())
    }
    val tokenInput = EditText(this).apply {
        hint = "Sync token"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        if (settings.token().isNotBlank()) setText(settings.token())
    }

    fun linkRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(primary)
        setPadding(0, dp(4), 0, dp(4))
        minHeight = dp(44)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    val body = androidx.core.widget.NestedScrollView(this).apply {
        addView(LinearLayout(this@showOracleSettingsDialog).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(tile {
                addView(caption("Server address"))
                addView(urlInput)
                addView(caption("Sync token").apply { setPadding(0, dp(12), 0, 0) })
                addView(tokenInput)
                addView(caption("Your private Tailscale/Serve address. The token stays only in this app's private storage.").apply {
                    textSize = 11f
                    setPadding(0, dp(8), 0, 0)
                })
            })
            addView(tile {
                addView(linkRow("Theme: ${AppTheme.current(this@showOracleSettingsDialog).label}  ›") {
                    AppTheme.showPicker(this@showOracleSettingsDialog)
                })
                addView(linkRow("Check app update  ›") { AppUpdateChecker.checkNow(this@showOracleSettingsDialog) })
            })
        })
    }

    formSheet("Settings", body, positiveText = "Save & test") { form ->
        val baseUrl = urlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString()
        if (baseUrl.isBlank()) { urlInput.error = "Enter the server address"; return@formSheet }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            urlInput.error = "Use an http:// or https:// address"
            return@formSheet
        }
        if (token.isBlank()) { tokenInput.error = "Enter the sync token"; return@formSheet }

        settings.save(baseUrl, token)
        form.dismiss()
        loadOracleSummary()

        oracleExecutor.execute {
            val result = FinanceSyncClient(this@showOracleSettingsDialog).fetchSummary()
            runOnUiThread {
                result.onSuccess {
                    renderOracleSummary(it)
                    Toast.makeText(this@showOracleSettingsDialog, "Settings saved and connection verified", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    textViewOracleStatus.text = "Oracle ledger: " + (error.message ?: "Connection failed")
                    Toast.makeText(this@showOracleSettingsDialog, "Settings saved, but the connection failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
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
