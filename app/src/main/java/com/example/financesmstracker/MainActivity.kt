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
import com.example.financesmstracker.integration.OracleIds
import com.example.financesmstracker.integration.SyncSettings
import com.example.financesmstracker.integration.OracleLedgerSummary
import com.example.financesmstracker.integration.OracleAccount
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    internal lateinit var dbHelper: FinanceDatabaseHelper
    internal lateinit var repository: TransactionRepository
    internal lateinit var adapter: TransactionAdapter
    internal lateinit var recyclerView: RecyclerView
    internal lateinit var textViewEmpty: TextView
    internal lateinit var textViewNotificationStatus: TextView
    internal lateinit var buttonOpenNotificationSettings: Button
    internal lateinit var textViewOracleStatus: TextView
    internal lateinit var textViewTrueAvailable: TextView
    internal lateinit var textViewBankCash: TextView
    internal lateinit var textViewCardOutstanding: TextView
    internal lateinit var textViewSplitwiseReceivable: TextView
    internal lateinit var buttonOracleSettings: Button
    internal lateinit var buttonGmailSync: Button
    internal lateinit var buttonReviewReconcile: Button
    internal lateinit var buttonEditSplitwise: Button
    internal lateinit var buttonEditBankAccounts: Button
    internal lateinit var buttonEditCreditCards: Button
    internal lateinit var buttonClearLocalHistory: Button
    internal lateinit var buttonRestoreHistory: Button

    internal val oracleExecutor = Executors.newSingleThreadExecutor()

    internal val localHistoryPrefs by lazy {
        getSharedPreferences("local_history_state", Context.MODE_PRIVATE)
    }

    internal val categories = com.example.financesmstracker.data.TransactionCategories.debit

    internal val creditCategories = com.example.financesmstracker.data.TransactionCategories.credit

    internal val transactionDataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SmsReceiver.ACTION_TRANSACTION_DATA_CHANGED) {
                loadTransactions()
                loadOracleSummary()
            }
        }
    }

    internal val requestPermissionLauncher =
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
        repository = TransactionRepository(dbHelper, this)

        recyclerView = findViewById(R.id.recyclerViewTransactions)
        textViewEmpty = findViewById(R.id.textViewEmpty)
        textViewNotificationStatus = findViewById(R.id.textViewNotificationStatus)
        buttonOpenNotificationSettings = findViewById(R.id.buttonOpenNotificationSettings)
        textViewOracleStatus = findViewById(R.id.textViewOracleStatus)
        textViewTrueAvailable = findViewById(R.id.textViewTrueAvailable)
        textViewBankCash = findViewById(R.id.textViewBankCash)
        textViewCardOutstanding = findViewById(R.id.textViewCardOutstanding)
        textViewSplitwiseReceivable = findViewById(R.id.textViewSplitwiseReceivable)
        buttonOracleSettings = findViewById(R.id.buttonOracleSettings)
        buttonGmailSync = findViewById(R.id.buttonGmailSync)
        buttonReviewReconcile = findViewById(R.id.buttonReviewReconcile)
        findViewById<Button>(R.id.buttonSmsLog).setOnClickListener { showSmsHandlingLog() }
        buttonEditSplitwise = findViewById(R.id.buttonEditSplitwise)
        buttonEditBankAccounts = findViewById(R.id.buttonEditBankAccounts)
        buttonEditCreditCards = findViewById(R.id.buttonEditCreditCards)
        buttonClearLocalHistory = findViewById(R.id.buttonClearLocalHistory)
        buttonRestoreHistory = findViewById(R.id.buttonRestoreHistory)
        buttonRestoreHistory.setOnClickListener { restoreHistoryFromServer() }

        buttonEditSplitwise.setOnClickListener {
            showManualSplitwiseDialog()
        }

        buttonEditBankAccounts.setOnClickListener {
            loadOracleAccountsForManualEdit("BANK_ACCOUNT")
        }

        buttonEditCreditCards.setOnClickListener {
            loadOracleAccountsForManualEdit("CREDIT_CARD")
        }

        buttonOracleSettings.setOnClickListener {
            showOracleSettingsDialog()
        }

        buttonGmailSync.isEnabled = true
        buttonGmailSync.text = "Sync Gmail"
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

        // onResume is also the recovery point for duplicate rows that were
        // already stored before the current reference-aware matcher shipped.
        // repairCanonicalDuplicates() is idempotent: after the first pass it
        // has nothing left to change, while any repaired IDs are propagated
        // to Oracle as voids.
        loadTransactions()
        updateNotificationAccessStatus()
        loadOracleSummary()
    }

    internal fun updateReviewCount() {
        val count =
            repository.getUnresolvedEvidenceCount() +
            repository.getUnresolvedUnrecognizedSmsCount()

        buttonReviewReconcile.text = if (count > 0) {
            "Review ($count)"
        } else {
            "Review"
        }
    }

    internal fun loadOracleSummary() {
        textViewOracleStatus.text = "Oracle ledger: Loading..."

        oracleExecutor.execute {
            val result = FinanceSyncClient(this@MainActivity).fetchSummary()

            runOnUiThread {
                result.onSuccess { summary ->
                    renderOracleSummary(summary)
                    refreshForeignAmounts()
                }.onFailure { error ->
                    textViewOracleStatus.text = "Oracle ledger: " + (error.message ?: "Unavailable")
                }
            }
        }
    }

    internal fun renderOracleSummary(summary: OracleLedgerSummary) {
        textViewOracleStatus.text = "Oracle ledger: Connected"
        textViewTrueAvailable.text = "True available: " + formatMinor(summary.trueAvailableMinor)
        textViewBankCash.text = "Bank cash: " + formatMinor(summary.bankCashMinor)
        textViewCardOutstanding.text = "Card outstanding: " + formatMinor(summary.creditCardOutstandingMinor)
        textViewSplitwiseReceivable.text = "Splitwise receivable: " + formatMinor(summary.splitwiseReceivableMinor)
    }

    internal fun updateNotificationAccessStatus() {
        val granted = NotificationAccessHelper.isNotificationAccessGranted(this)
        val connected = NotificationAccessHelper.isListenerConnected(this)
        if (granted && connected) {
            val lastTitle = NotificationAccessHelper.lastGmailNotificationTitle(this)
            val detail = if (NotificationAccessHelper.lastGmailNotificationAt(this) > 0L) {
                " • last Gmail: " + lastTitle.ifBlank { "received" }
            } else {
                " • no Gmail event yet"
            }
            textViewNotificationStatus.text = "Bank/card notification trigger: Enabled" + detail
            textViewNotificationStatus.setTextColor(Color.parseColor("#2E7D32"))
            buttonOpenNotificationSettings.visibility = View.GONE
        } else if (granted) {
            textViewNotificationStatus.text = "Bank/card notification trigger: Enabled (reconnecting...)"
            textViewNotificationStatus.setTextColor(Color.parseColor("#EF6C00"))
            buttonOpenNotificationSettings.visibility = View.GONE
        } else {
            textViewNotificationStatus.text = "Bank/card notification trigger: Disabled"
            textViewNotificationStatus.setTextColor(Color.parseColor("#C62828"))
            buttonOpenNotificationSettings.visibility = View.VISIBLE
        }
    }

    internal var foreignAmountsByOracleId: Map<String, FinanceSyncClient.ForeignAmountInfo> = emptyMap()

    internal fun loadTransactions() {
        // Repair same-side duplicates that were already stored before the
        // stronger repository matcher was deployed. The repair only changes
        // rows proven to be duplicates (same amount/direction/account/time,
        // with reference-aware identity), and every voided row is sent to
        // Oracle so the remote ledger is repaired too.
        val repairedIds = repository.repairCanonicalDuplicates()
        repairedIds.forEach { transactionId ->
            FinanceSyncBridge.enqueueVoidedTransaction(this, transactionId)
        }

        val transactions = repository.getAllTransactions()

        adapter.updateData(transactions)
        applyForeignAmounts(transactions)
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

    companion object {
        internal const val KEY_LOCAL_HISTORY_CLEARED_AT = "cleared_at"
        internal const val KEY_LOCAL_HISTORY_CLEARED_ORACLE_IDS = "cleared_oracle_ids"
        internal const val KEY_LOCAL_HISTORY_CLEARED_SMS_HASHES = "cleared_sms_hashes"
    }

    internal fun checkAndRequestSmsPermission() {
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
