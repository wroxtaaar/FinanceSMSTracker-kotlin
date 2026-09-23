package com.example.financesmstracker

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.ui.TransactionAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var repository: TransactionRepository
    private lateinit var adapter: TransactionAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var textViewEmpty: TextView

    private val categories = listOf(
        "FOOD", "GROCERIES", "SHOPPING", "FUEL", "TRAVEL",
        "SUBSCRIPTION", "BILLS", "TRANSFER", "ATM", "SALARY", "REFUND", "OTHER"
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "SMS Permission Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS Permission Denied. Cannot receive transaction SMS.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dbHelper = FinanceDatabaseHelper(this)
        repository = TransactionRepository(dbHelper)

        recyclerView = findViewById(R.id.recyclerViewTransactions)
        textViewEmpty = findViewById(R.id.textViewEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TransactionAdapter(emptyList()) { transaction ->
            showTransactionDetailDialog(transaction)
        }
        recyclerView.adapter = adapter

        checkAndRequestSmsPermission()
    }

    private fun showTransactionDetailDialog(tx: Transaction) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_transaction_detail, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val detailTextAmount = dialogView.findViewById<TextView>(R.id.detailTextAmount)
        val detailTextType = dialogView.findViewById<TextView>(R.id.detailTextType)
        val detailTextPaymentMethod = dialogView.findViewById<TextView>(R.id.detailTextPaymentMethod)
        val detailTextAccountType = dialogView.findViewById<TextView>(R.id.detailTextAccountType)
        val detailTextBank = dialogView.findViewById<TextView>(R.id.detailTextBank)
        val detailTextMerchant = dialogView.findViewById<TextView>(R.id.detailTextMerchant)
        val detailTextPayeeId = dialogView.findViewById<TextView>(R.id.detailTextPayeeId)
        val detailTextAccountLastFour = dialogView.findViewById<TextView>(R.id.detailTextAccountLastFour)
        val detailTextRefNumber = dialogView.findViewById<TextView>(R.id.detailTextRefNumber)
        val detailTextDateTime = dialogView.findViewById<TextView>(R.id.detailTextDateTime)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val checkboxRememberPayee = dialogView.findViewById<CheckBox>(R.id.checkboxRememberPayee)
        val buttonSaveCategory = dialogView.findViewById<Button>(R.id.buttonSaveCategory)

        val rupees = tx.amountPaise / 100.0
        detailTextAmount.text = String.format(Locale.getDefault(), "Amount: ₹%.2f", rupees)
        detailTextType.text = "Type: ${tx.transactionType.name}"
        detailTextPaymentMethod.text = "Payment Method: ${tx.paymentMethod.name}"
        detailTextAccountType.text = "Account Type: ${tx.accountType.name}"
        detailTextBank.text = "Bank: ${tx.bank ?: "-"}"
        detailTextMerchant.text = "Merchant: ${tx.merchantName ?: "-"}"
        detailTextPayeeId.text = if (!tx.payeeId.isNullOrBlank()) "UPI ID: ${tx.payeeId}" else "UPI ID: Not provided"
        detailTextAccountLastFour.text = "A/C Last 4: ${tx.accountLastFour ?: "-"}"
        detailTextRefNumber.text = "Reference: ${tx.refNumber ?: "-"}"

        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        detailTextDateTime.text = "Date/Time: ${sdf.format(Date(tx.timestamp))}"

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = spinnerAdapter

        val currentCategoryIndex = categories.indexOf(tx.category)
        if (currentCategoryIndex >= 0) {
            spinnerCategory.setSelection(currentCategoryIndex)
        }

        val hasStablePayee = !tx.payeeId.isNullOrBlank()
        if (hasStablePayee) {
            checkboxRememberPayee.visibility = View.VISIBLE
            checkboxRememberPayee.isChecked = false
        } else {
            checkboxRememberPayee.visibility = View.GONE
            checkboxRememberPayee.isChecked = false
        }

        buttonSaveCategory.setOnClickListener {
            val selectedCategory = spinnerCategory.selectedItem.toString()
            repository.updateTransactionCategory(tx.id, selectedCategory)

            if (hasStablePayee && checkboxRememberPayee.isChecked && !tx.payeeId.isNullOrBlank()) {
                repository.savePayeeCategoryMapping(tx.payeeId, selectedCategory)
                repository.updateCategoriesForPayee(tx.payeeId, selectedCategory)
                Toast.makeText(this, "Category updated and remembered for all transactions with this payee", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Category updated", Toast.LENGTH_SHORT).show()
            }

            dialog.dismiss()
            loadTransactions()
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        loadTransactions()
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
        super.onDestroy()
        dbHelper.close()
    }

    private fun checkAndRequestSmsPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
            }
        }
    }
}
