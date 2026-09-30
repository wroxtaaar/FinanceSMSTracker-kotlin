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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
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
import com.example.financesmstracker.truecaller.NotificationAccessHelper
import com.example.financesmstracker.ui.TransactionAdapter

import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.integration.OracleLedgerSummary
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

        buttonRefreshOracle.setOnClickListener {
            loadOracleSummary()
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
            textViewNotificationStatus.text = "Notification Access: Granted"
            textViewNotificationStatus.setTextColor(Color.parseColor("#2E7D32")) // Green
        } else {
            textViewNotificationStatus.text = "Notification Access: Disabled (Tap button above to enable)"
            textViewNotificationStatus.setTextColor(Color.parseColor("#C62828")) // Red
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
