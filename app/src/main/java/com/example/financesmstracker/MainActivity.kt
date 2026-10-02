package com.example.financesmstracker

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.categorizer.CategoryMemoryKey
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.parser.ParserResult
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.receiver.SmsReceiver
import com.example.financesmstracker.gmail.NotificationAccessHelper
import com.example.financesmstracker.ui.TransactionAdapter

import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.integration.SyncSettings
import com.example.financesmstracker.integration.OracleLedgerSummary
import com.example.financesmstracker.integration.OracleAccount
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var repository: TransactionRepository
    private lateinit var adapter: TransactionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var textViewEmpty: TextView
    private lateinit var textViewNotificationStatus: TextView
    private lateinit var buttonOpenNotificationSettings: Button
    private lateinit var textViewOracleStatus: TextView
    private lateinit var textViewTrueAvailable: TextView
    private lateinit var textViewBankCash: TextView
    private lateinit var textViewCardOutstanding: TextView
    private lateinit var textViewSplitwiseReceivable: TextView
    private lateinit var buttonRefreshOracle: Button
    private lateinit var buttonViewAccounts: Button
    private lateinit var buttonOracleSettings: Button
    private lateinit var buttonGmailSync: Button
    private lateinit var buttonReviewReconcile: Button
    private lateinit var buttonEditSplitwise: Button
    private lateinit var buttonEditBankAccounts: Button
    private lateinit var buttonEditCreditCards: Button
    private lateinit var buttonClearLocalHistory: Button

    private val oracleExecutor = Executors.newSingleThreadExecutor()

    private val categories = listOf(
        "FOOD", "GROCERIES", "SHOPPING", "FUEL", "TRAVEL",
        "SUBSCRIPTION", "BILLS", "TRANSFER", "ATM",
        "SALARY", "REFUND", "OTHER"
    )

    private val creditCategories = listOf(
        "SALARY",
        "TRANSFER",
        "REFUND"
    )

    private val transactionDataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SmsReceiver.ACTION_TRANSACTION_DATA_CHANGED) {
                loadTransactions()
                loadOracleSummary()
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(
                    this,
                    "SMS Permission Granted",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "SMS Permission Denied. Cannot receive transaction SMS.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }

        dbHelper = FinanceDatabaseHelper(this)
        repository = TransactionRepository(dbHelper)

        recyclerView = findViewById(R.id.recyclerViewTransactions)
        textViewEmpty = findViewById(R.id.textViewEmpty)
        textViewNotificationStatus = findViewById(R.id.textViewNotificationStatus)
        buttonOpenNotificationSettings = findViewById(R.id.buttonOpenNotificationSettings)
        textViewOracleStatus = findViewById(R.id.textViewOracleStatus)
        textViewTrueAvailable = findViewById(R.id.textViewTrueAvailable)
        textViewBankCash = findViewById(R.id.textViewBankCash)
        textViewCardOutstanding = findViewById(R.id.textViewCardOutstanding)
        textViewSplitwiseReceivable = findViewById(R.id.textViewSplitwiseReceivable)
        buttonRefreshOracle = findViewById(R.id.buttonRefreshOracle)
        buttonViewAccounts = findViewById(R.id.buttonViewAccounts)
        buttonOracleSettings = findViewById(R.id.buttonOracleSettings)
        buttonGmailSync = findViewById(R.id.buttonGmailSync)
        buttonReviewReconcile = findViewById(R.id.buttonReviewReconcile)
        buttonEditSplitwise = findViewById(R.id.buttonEditSplitwise)
        buttonEditBankAccounts = findViewById(R.id.buttonEditBankAccounts)
        buttonEditCreditCards = findViewById(R.id.buttonEditCreditCards)
        buttonClearLocalHistory = findViewById(R.id.buttonClearLocalHistory)

        buttonEditSplitwise.setOnClickListener {
            showManualSplitwiseDialog()
        }

        buttonEditBankAccounts.setOnClickListener {
            loadOracleAccountsForManualEdit("BANK_ACCOUNT")
        }

        buttonEditCreditCards.setOnClickListener {
            loadOracleAccountsForManualEdit("CREDIT_CARD")
        }

        buttonRefreshOracle.setOnClickListener {
            loadOracleSummary()
        }

        buttonViewAccounts.setOnClickListener {
            loadOracleAccounts()
        }

        buttonOracleSettings.setOnClickListener {
            showOracleSettingsDialog()
        }

        buttonGmailSync.setOnClickListener {
            triggerManualGmailSync()
        }

        buttonReviewReconcile.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }

        buttonClearLocalHistory.setOnClickListener {
            showClearLocalHistoryDialog()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(emptyList()) { transaction ->
            showTransactionDetailDialog(transaction)
        }

        recyclerView.adapter = adapter

        buttonOpenNotificationSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        checkAndRequestSmsPermission()
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            transactionDataChangedReceiver,
            IntentFilter(SmsReceiver.ACTION_TRANSACTION_DATA_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(transactionDataChangedReceiver)
    }

    override fun onResume() {
        super.onResume()
        loadTransactions()
        updateNotificationAccessStatus()
        loadOracleSummary()
        syncOracleGmailTransactions()
    }

    private fun showClearLocalHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Local History?")
            .setMessage(
                "This removes transactions, local evidence, and unrecognized SMS records from this phone. " +
                    "Pending local sync payloads will also be discarded. Oracle data, account settings, " +
                    "Gmail processing history, and category memory are not changed."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                clearLocalHistory()
            }
            .show()
    }

    private fun clearLocalHistory() {
        buttonClearLocalHistory.isEnabled = false
        oracleExecutor.execute {
            val cleared = repository.clearLocalHistory()
            val queue = com.example.financesmstracker.integration.FinanceSyncQueue(this@MainActivity)
            val pending = queue.size()
            queue.clear()

            runOnUiThread {
                loadTransactions()
                updateReviewCount()
                buttonClearLocalHistory.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Cleared " + cleared.transactions +
                        " transactions and " + pending + " pending sync payloads",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadOracleAccounts() {
        buttonViewAccounts.isEnabled = false
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchAccounts()

            runOnUiThread {
                buttonViewAccounts.isEnabled = true
                result.onSuccess { accounts ->
                    showAccountsDialog(accounts)
                }.onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        "Could not load accounts: " + (error.message ?: "Unavailable"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun loadOracleAccountsForManualEdit(accountType: String) {
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchAccounts()

            runOnUiThread {
                result.onSuccess { accounts ->
                    val filtered = accounts.filter { it.accountType == accountType }
                    showManualAccountsDialog(filtered, accountType)
                }.onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        "Could not load accounts: " + (error.message ?: "Unavailable"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showManualAccountsDialog(
        accounts: List<OracleAccount>,
        accountType: String
    ) {
        val title = if (accountType == "BANK_ACCOUNT") {
            "Edit Bank Accounts"
        } else {
            "Edit Credit Cards"
        }

        if (accounts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("No matching accounts configured on Oracle.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val accountInputs = accounts.map { account ->
            val label = TextView(this).apply {
                text = buildString {
                    append(account.name)
                    account.last4?.let { append(" ••••").append(it) }
                }
                textSize = 14f
                setPadding(0, 8, 0, 2)
            }

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
                hint = if (accountType == "BANK_ACCOUNT") {
                    "Current balance"
                } else {
                    "Current outstanding"
                }
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
            .setMessage(
                if (accountType == "BANK_ACCOUNT") {
                    "Manually set the current balance for each bank account."
                } else {
                    "Manually set the current outstanding amount for each credit card."
                }
            )
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
                        val saveResult = FinanceSyncClient(this@MainActivity)
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
                                this@MainActivity,
                                "Account balances updated",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
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

    private fun formatDecimalMinor(minor: Long): String =
        String.format(Locale.getDefault(), "%.2f", minor / 100.0)

    private fun showAccountsDialog(accounts: List<OracleAccount>) {
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Accounts")
                .setMessage("No accounts configured on Oracle.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val banks = accounts.filter { it.accountType == "BANK_ACCOUNT" }
        val cards = accounts.filter { it.accountType == "CREDIT_CARD" }
        val other = accounts.filter { it.accountType != "BANK_ACCOUNT" && it.accountType != "CREDIT_CARD" }

        val message = buildString {
            appendAccountSection("Banks", banks)
            appendAccountSection("Credit Cards", cards)
            appendAccountSection("Other", other)
        }

        AlertDialog.Builder(this)
            .setTitle("Oracle Accounts")
            .setMessage(message.trim())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun StringBuilder.appendAccountSection(
        title: String,
        accounts: List<OracleAccount>
    ) {
        if (accounts.isEmpty()) return
        append(title).append("\n")
        accounts.forEach { account ->
            val suffix = account.last4?.let { " ••••" + it }.orEmpty()
            val amountLabel = if (account.accountType == "CREDIT_CARD") {
                "Outstanding"
            } else {
                "Balance"
            }
            append(account.name)
                .append(suffix)
                .append("\n  ")
                .append(amountLabel)
                .append(": ")
                .append(formatMinor(account.balanceMinor))
                .append("\n")
        }
        append("\n")
    }

    private fun showOracleSettingsDialog() {
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
                    val result = FinanceSyncClient(this@MainActivity).fetchSummary()
                    runOnUiThread {
                        result.onSuccess {
                            renderOracleSummary(it)
                            Toast.makeText(
                                this@MainActivity,
                                "Oracle settings saved and connection verified",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { error ->
                            textViewOracleStatus.text =
                                "Oracle ledger: " + (error.message ?: "Connection failed")
                            Toast.makeText(
                                this@MainActivity,
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

    private fun triggerManualGmailSync() {
        buttonGmailSync.isEnabled = false
        buttonGmailSync.text = "Checking Gmail..."

        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).triggerGmailSync()

            runOnUiThread {
                buttonGmailSync.isEnabled = true
                buttonGmailSync.text = "Check Gmail"

                result.onSuccess { sync ->
                    loadOracleSummary()
                    syncOracleGmailTransactions()

                    val message = buildString {
                        append("Gmail checked\n\n")
                        append("Messages scanned: ${sync.messagesScanned}\n")
                        append("New transaction emails: ${sync.parsedTransactions}\n")
                        append("Axis credits found: ${sync.axisCredits}\n")
                        append("Already processed: ${sync.alreadyProcessed}\n")
                        append("Corrected old Gmail transactions: ${sync.repairedTransactions}\n")
                        append("Duplicates skipped: ${sync.duplicateTransactions}\n")
                        append("Needs review: ${sync.reviewCount}\n")
                        append("Ignored: ${sync.ignoredCount}")
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Gmail Sync")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        "Gmail check failed: " + (error.message ?: "Unavailable"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateReviewCount() {
        val count =
            repository.getUnresolvedEvidenceCount() +
            repository.getUnresolvedUnrecognizedSmsCount()

        buttonReviewReconcile.text = if (count > 0) {
            "Review & Reconcile ($count)"
        } else {
            "Review & Reconcile"
        }
    }

    private fun loadOracleSummary() {
        textViewOracleStatus.text = "Oracle ledger: Loading..."

        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchSummary()

            runOnUiThread {
                result.onSuccess { summary ->
                    renderOracleSummary(summary)
                }.onFailure { error ->
                    textViewOracleStatus.text = "Oracle ledger: " + (error.message ?: "Unavailable")
                }
            }
        }
    }

    private fun syncOracleGmailTransactions() {
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchGmailTransactions()

            runOnUiThread {
                result.onSuccess { transactions ->
                    var changed = 0
                    transactions.forEach { transaction ->
                        val rowId = repository.upsertOracleGmailTransaction(transaction)
                        if (rowId != 0L) {
                            changed++

                            // Gmail is the clarification pass. If it repaired or
                            // enriched a notification/SMS transaction, push the
                            // clarified canonical row back to Oracle.
                            repository.getTransactionById(rowId)?.let { local ->
                                FinanceSyncBridge.enqueueCanonical(
                                    this@MainActivity,
                                    local
                                )
                            }
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

    private fun showManualSplitwiseDialog() {
        buttonEditSplitwise.isEnabled = false
        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchManualSplitwiseTotal()
            runOnUiThread {
                buttonEditSplitwise.isEnabled = true
                result.onSuccess { currentMinor ->
                    val settings = SyncSettings(this@MainActivity)
                    val savedGroup1 = settings.splitwiseGroup1Minor()
                    val savedGroup2 = settings.splitwiseGroup2Minor()
                    val savedGroup3 = settings.splitwiseGroup3Minor()
                    val hasSavedGroups = settings.hasSplitwiseGroups()

                    val group1Input = EditText(this@MainActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true)
                        hint = "Splitwise Group 1"
                        setText(String.format(Locale.getDefault(), "%.2f",
                            if (hasSavedGroups) savedGroup1 / 100.0 else currentMinor / 100.0))
                    }

                    val group2Input = EditText(this@MainActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true)
                        hint = "Splitwise Group 2"
                        setText(String.format(Locale.getDefault(), "%.2f",
                            if (hasSavedGroups) savedGroup2 / 100.0 else 0.0))
                    }

                    val group3Input = EditText(this@MainActivity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true)
                        hint = "Splitwise Group 3"
                        setText(String.format(Locale.getDefault(), "%.2f",
                            if (hasSavedGroups) savedGroup3 / 100.0 else 0.0))
                    }

                    val container = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 0, 48, 0)
                        addView(group1Input)
                        addView(group2Input)
                        addView(group3Input)
                    }

                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle("Splitwise Owed")
                        .setMessage("Enter what you are owed in each Splitwise group. The three amounts are added together.")
                        .setView(container)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create()

                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val group1 = group1Input.text.toString().trim().replace(",", "").toDoubleOrNull()
                            val group2 = group2Input.text.toString().trim().replace(",", "").toDoubleOrNull()
                            val group3 = group3Input.text.toString().trim().replace(",", "").toDoubleOrNull()

                            if (group1 == null || group1 < 0) {
                                group1Input.error = "Enter a valid amount"
                                return@setOnClickListener
                            }
                            if (group2 == null || group2 < 0) {
                                group2Input.error = "Enter a valid amount"
                                return@setOnClickListener
                            }
                            if (group3 == null || group3 < 0) {
                                group3Input.error = "Enter a valid amount"
                                return@setOnClickListener
                            }

                            val group1Minor = kotlin.math.round(group1 * 100.0).toLong()
                            val group2Minor = kotlin.math.round(group2 * 100.0).toLong()
                            val group3Minor = kotlin.math.round(group3 * 100.0).toLong()
                            val totalMinor = group1Minor + group2Minor + group3Minor

                            settings.saveSplitwiseGroups(group1Minor, group2Minor, group3Minor)
                            buttonEditSplitwise.isEnabled = false

                            oracleExecutor.execute {
                                val saveResult = FinanceSyncClient(this@MainActivity)
                                    .updateManualSplitwiseTotal(totalMinor)

                                runOnUiThread {
                                    buttonEditSplitwise.isEnabled = true
                                    saveResult.onSuccess {
                                        dialog.dismiss()
                                        loadOracleSummary()
                                        Toast.makeText(this@MainActivity, "Splitwise owed updated", Toast.LENGTH_SHORT).show()
                                    }.onFailure { error ->
                                        Toast.makeText(this@MainActivity, "Could not update Splitwise: " + (error.message ?: "Unavailable"), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }

                    dialog.show()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, "Could not load Splitwise amount: " + (error.message ?: "Unavailable"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderOracleSummary(summary: OracleLedgerSummary) {
        textViewOracleStatus.text = "Oracle ledger: Connected"
        textViewTrueAvailable.text = "True available: " + formatMinor(summary.trueAvailableMinor)
        textViewBankCash.text = "Bank cash: " + formatMinor(summary.bankCashMinor)
        textViewCardOutstanding.text = "Card outstanding: " + formatMinor(summary.creditCardOutstandingMinor)
        textViewSplitwiseReceivable.text = "Splitwise receivable: " + formatMinor(summary.splitwiseReceivableMinor)
    }

    private fun formatMinor(minor: Long): String {
        return String.format(Locale.getDefault(), "₹%,.2f", minor / 100.0)
    }

    private fun updateNotificationAccessStatus() {
        val granted = NotificationAccessHelper.isNotificationAccessGranted(this)
        if (granted) {
            textViewNotificationStatus.text = "Gmail notification trigger: Enabled"
            textViewNotificationStatus.setTextColor(Color.parseColor("#2E7D32"))
            buttonOpenNotificationSettings.visibility = View.GONE
        } else {
            textViewNotificationStatus.text = "Gmail notification trigger: Disabled"
            textViewNotificationStatus.setTextColor(Color.parseColor("#C62828"))
            buttonOpenNotificationSettings.visibility = View.VISIBLE
        }
    }

    private fun showTransactionDetailDialog(tx: Transaction) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_transaction_detail, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val detailTextAmount =
            dialogView.findViewById<TextView>(R.id.detailTextAmount)

        val detailTextType =
            dialogView.findViewById<TextView>(R.id.detailTextType)

        val detailTextPaymentMethod =
            dialogView.findViewById<TextView>(R.id.detailTextPaymentMethod)

        val detailTextAccountType =
            dialogView.findViewById<TextView>(R.id.detailTextAccountType)

        val detailTextBank =
            dialogView.findViewById<TextView>(R.id.detailTextBank)

        val detailTextMerchant =
            dialogView.findViewById<TextView>(R.id.detailTextMerchant)

        val detailTextPayeeId =
            dialogView.findViewById<TextView>(R.id.detailTextPayeeId)

        val detailTextAccountLastFour =
            dialogView.findViewById<TextView>(R.id.detailTextAccountLastFour)

        val detailTextRefNumber =
            dialogView.findViewById<TextView>(R.id.detailTextRefNumber)

        val detailTextDateTime =
            dialogView.findViewById<TextView>(R.id.detailTextDateTime)

        val spinnerCategory =
            dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        val checkboxRememberPayee =
            dialogView.findViewById<CheckBox>(R.id.checkboxRememberPayee)

        val buttonSaveCategory =
            dialogView.findViewById<Button>(R.id.buttonSaveCategory)

        val buttonVoidTransaction =
            dialogView.findViewById<Button>(R.id.buttonVoidTransaction)

        val rupees = tx.amountPaise / 100.0

        detailTextAmount.text =
            String.format(
                Locale.getDefault(),
                "Amount: ₹%.2f",
                rupees
            )

        detailTextType.text =
            "Type: ${tx.transactionType.name}"

        detailTextPaymentMethod.text =
            "Payment Method: ${tx.paymentMethod.name}"

        detailTextAccountType.text =
            "Account Type: ${tx.accountType.name}"

        detailTextBank.text =
            "Bank: ${tx.bank ?: "-"}"

        detailTextMerchant.text =
            "Merchant: ${tx.merchantName ?: "-"}"

        detailTextPayeeId.text =
            if (!tx.payeeId.isNullOrBlank()) {
                "UPI ID: ${tx.payeeId}"
            } else {
                "UPI ID: Not provided"
            }

        detailTextAccountLastFour.text =
            "A/C Last 4: ${tx.accountLastFour ?: "-"}"

        detailTextRefNumber.text =
            "Reference: ${tx.refNumber ?: "-"}"

        val sdf = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        )

        detailTextDateTime.text =
            "Date/Time: ${sdf.format(Date(tx.timestamp))}"

        /*
         * Credits can only use:
         * SALARY, TRANSFER, REFUND.
         *
         * Debits can use the complete category list.
         */
        val availableCategories =
            if (tx.transactionType == TransactionType.CREDIT) {
                creditCategories
            } else {
                categories
            }

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            availableCategories
        )

        spinnerAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinnerCategory.adapter = spinnerAdapter

        val currentCategoryIndex =
            availableCategories.indexOf(tx.category)

        if (currentCategoryIndex >= 0) {
            spinnerCategory.setSelection(currentCategoryIndex)
        }

        val memoryKey = CategoryMemoryKey.from(
            ParserResult(
                isTransaction = true,
                amountPaise = tx.amountPaise,
                transactionType = tx.transactionType,
                paymentMethod = tx.paymentMethod,
                accountType = tx.accountType,
                bank = tx.bank,
                merchantName = tx.merchantName,
                payeeId = tx.payeeId,
                accountLastFour = tx.accountLastFour,
                refNumber = tx.refNumber,
                confidence = tx.parserConfidence
            )
        )

        val hasStableMemoryKey = !memoryKey.isNullOrBlank()

        if (hasStableMemoryKey) {
            checkboxRememberPayee.visibility = View.VISIBLE
            checkboxRememberPayee.isChecked = false
        } else {
            checkboxRememberPayee.visibility = View.GONE
            checkboxRememberPayee.isChecked = false
        }

        buttonVoidTransaction.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Void Transaction?")
                .setMessage("This removes the transaction from this phone's history and queues the void for Oracle. It does not delete the original SMS.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Void") { _, _ ->
                    val updated = repository.voidTransaction(tx.id)
                    if (updated > 0) {
                        dialog.dismiss()
                        loadTransactions()
                        FinanceSyncBridge.enqueueVoidedTransaction(this, tx.id)
                        Toast.makeText(this, "Transaction voided and queued for Oracle sync", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Could not void transaction", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }

        buttonSaveCategory.setOnClickListener {
            val selectedCategory =
                spinnerCategory.selectedItem.toString()

            repository.updateTransactionCategory(
                tx.id,
                selectedCategory
            )

            if (
                hasStableMemoryKey &&
                checkboxRememberPayee.isChecked &&
                !memoryKey.isNullOrBlank()
            ) {
                repository.saveCategoryMemory(
                    memoryKey,
                    selectedCategory
                )

                repository.updateCategoriesForMemoryKey(
                    memoryKey,
                    selectedCategory
                )

                Toast.makeText(
                    this,
                    "Category updated and remembered for matching transactions",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Category updated",
                    Toast.LENGTH_SHORT
                ).show()
            }

            dialog.dismiss()
            loadTransactions()
        }

        dialog.show()
    }

    private fun loadTransactions() {
        val transactions = repository.getAllTransactions()

        adapter.updateData(transactions)
        updateReviewCount()

        if (transactions.isEmpty()) {
            recyclerView.visibility = View.GONE
            textViewEmpty.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            textViewEmpty.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        oracleExecutor.shutdownNow()
        super.onDestroy()
        dbHelper.close()
    }

    private fun checkAndRequestSmsPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted.
            }

            else -> {
                requestPermissionLauncher.launch(
                    Manifest.permission.RECEIVE_SMS
                )
            }
        }
    }
}
