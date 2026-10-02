package com.example.financesmstracker

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.ManualTransactionRepository
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.ReviewStatus
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.data.UnrecognizedSms
import com.example.financesmstracker.data.TransactionConflict
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.evidence.CrossSourceMatcher
import com.example.financesmstracker.evidence.MatchOutcome
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.receiver.SmsReceiver
import com.example.financesmstracker.ui.ReviewAdapter
import com.example.financesmstracker.ui.UnrecognizedSmsAdapter
import com.example.financesmstracker.ui.TransactionConflictAdapter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewActivity : AppCompatActivity() {
    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var repository: TransactionRepository
    private lateinit var manualRepository: ManualTransactionRepository
    private lateinit var adapter: ReviewAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var unrecognizedAdapter: UnrecognizedSmsAdapter
    private lateinit var unrecognizedRecyclerView: RecyclerView
    private lateinit var emptyUnrecognizedText: TextView
    private lateinit var conflictAdapter: TransactionConflictAdapter
    private lateinit var conflictRecyclerView: RecyclerView
    private lateinit var emptyConflictText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        dbHelper = FinanceDatabaseHelper(this)
        repository = TransactionRepository(dbHelper)
        manualRepository = ManualTransactionRepository(dbHelper)

        recyclerView = findViewById(R.id.recyclerViewReviews)
        emptyText = findViewById(R.id.textViewNoReviews)
        unrecognizedRecyclerView = findViewById(R.id.recyclerViewUnrecognized)
        emptyUnrecognizedText = findViewById(R.id.textViewNoUnrecognized)
        conflictRecyclerView = findViewById(R.id.recyclerViewConflicts)
        emptyConflictText = findViewById(R.id.textViewNoConflicts)

        adapter = ReviewAdapter(emptyList()) { evidence -> showEvidenceReview(evidence) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        unrecognizedAdapter = UnrecognizedSmsAdapter(
            emptyList(),
            onCreateTransaction = { item -> showCreateTransactionDialog(item) },
            onDismiss = { item -> dismissUnrecognizedSms(item.id) }
        )
        unrecognizedRecyclerView.layoutManager = LinearLayoutManager(this)
        unrecognizedRecyclerView.adapter = unrecognizedAdapter

        conflictAdapter = TransactionConflictAdapter(emptyList()) { conflict -> showConflictReview(conflict) }
        conflictRecyclerView.layoutManager = LinearLayoutManager(this)
        conflictRecyclerView.adapter = conflictAdapter

        findViewById<Button>(R.id.buttonRefreshReviews).setOnClickListener { loadReviews() }
    }

    override fun onResume() {
        super.onResume()
        loadReviews()
    }

    private fun loadReviews() {
        val reviews = repository.getUnresolvedEvidence()
        adapter.updateData(reviews)
        recyclerView.visibility = if (reviews.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE

        val unrecognized = repository.getUnresolvedUnrecognizedSms()
        unrecognizedAdapter.updateData(unrecognized)
        unrecognizedRecyclerView.visibility = if (unrecognized.isEmpty()) View.GONE else View.VISIBLE
        emptyUnrecognizedText.visibility = if (unrecognized.isEmpty()) View.VISIBLE else View.GONE

        val conflicts = repository.getPotentialTransactionConflicts()
        conflictAdapter.updateData(conflicts)
        conflictRecyclerView.visibility = if (conflicts.isEmpty()) View.GONE else View.VISIBLE
        emptyConflictText.visibility = if (conflicts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showConflictReview(conflict: TransactionConflict) {
        val first = conflict.first
        val second = conflict.second
        val firstLabel = transactionLabel(first)
        val secondLabel = transactionLabel(second)

        AlertDialog.Builder(this)
            .setTitle("Transaction mismatch")
            .setMessage(
                "These two records look like the same payment but disagree on bank/account/reference.\n\n" +
                    "Record 1:\n" + firstLabel + "\n\nRecord 2:\n" + secondLabel +
                    "\n\nWhy flagged: " + conflict.reason +
                    "\n\nKeep the record supported by the bank SMS/email and void the other."
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Keep Record 1") { _, _ -> voidConflictTransaction(second.id) }
            .setPositiveButton("Keep Record 2") { _, _ -> voidConflictTransaction(first.id) }
            .show()
    }

    private fun voidConflictTransaction(id: Long) {
        val updated = repository.voidTransaction(id)
        if (updated > 0) {
            FinanceSyncBridge.enqueueVoidedTransaction(this, id)
            Toast.makeText(this, "Wrong transaction voided and queued for Oracle sync", Toast.LENGTH_LONG).show()
            loadReviews()
        } else {
            Toast.makeText(this, "Could not void transaction", Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissUnrecognizedSms(id: Long) {
        val updated = repository.updateUnrecognizedSmsStatus(id, ReviewStatus.DISMISSED)
        if (updated > 0) {
            Toast.makeText(this, "Unrecognized SMS dismissed", Toast.LENGTH_SHORT).show()
            loadReviews()
        } else {
            Toast.makeText(this, "Could not dismiss SMS", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCreateTransactionDialog(item: UnrecognizedSms) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }

        val amount = addField(container, "Amount (₹)", "")
        amount.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val direction = addSpinner(container, "Direction", listOf("DEBIT", "CREDIT"))
        val accountType = addSpinner(
            container,
            "Account Type",
            listOf(AccountType.BANK_ACCOUNT.name, AccountType.CREDIT_CARD.name)
        )
        val paymentMethod = addSpinner(
            container,
            "Payment Method",
            PaymentMethod.values().map { it.name }
        )
        val bank = addField(container, "Bank", inferBank(item.sender))
        val lastFour = addField(container, "Account / Card last 4 digits", "")
        lastFour.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val merchant = addField(container, "Merchant / Payee (optional)", "")
        val reference = addField(container, "Reference (optional)", "")
        val category = addField(container, "Category", "OTHER")

        container.addView(TextView(this).apply {
            text = "Date: " + SimpleDateFormat(
                "dd MMM yyyy, hh:mm a",
                Locale.getDefault()
            ).format(Date(item.receivedAt))
            setPadding(0, 12, 0, 4)
        })

        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Create Transaction")
            .setMessage("Enter the transaction details. The original SMS body is not stored by the app.")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amountPaise = parseAmountPaise(amount.text.toString())
                val selectedType = TransactionType.valueOf(direction.selectedItem.toString())
                val selectedAccountType = AccountType.valueOf(accountType.selectedItem.toString())
                val selectedPaymentMethod = PaymentMethod.valueOf(paymentMethod.selectedItem.toString())

                if (amountPaise == null || amountPaise <= 0L) {
                    amount.error = "Enter a valid amount"
                    return@setOnClickListener
                }
                if (bank.text.toString().trim().isEmpty()) {
                    bank.error = "Enter the bank"
                    return@setOnClickListener
                }

                val result = manualRepository.createFromUnrecognized(
                    unrecognized = item,
                    amountPaise = amountPaise,
                    transactionType = selectedType,
                    paymentMethod = selectedPaymentMethod,
                    accountType = selectedAccountType,
                    bank = bank.text.toString(),
                    merchantName = merchant.text.toString(),
                    accountLastFour = lastFour.text.toString(),
                    reference = reference.text.toString(),
                    category = category.text.toString()
                )

                if (result == null) {
                    Toast.makeText(
                        this,
                        "Could not create transaction. It may already exist.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val transaction = repository.getTransactionById(result.transactionId)
                val evidence = repository.getSourceEvidenceById(result.evidenceId)

                if (transaction != null) {
                    FinanceSyncBridge.enqueueCanonical(
                        context = this,
                        transaction = transaction,
                        evidence = evidence
                    )
                }

                sendBroadcast(android.content.Intent(SmsReceiver.ACTION_TRANSACTION_DATA_CHANGED).apply {
                    setPackage(packageName)
                })

                Toast.makeText(
                    this,
                    "Transaction created and queued for Oracle sync",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
                loadReviews()
            }
        }
        dialog.show()
    }

    private fun addField(container: LinearLayout, hint: String, value: String): EditText {
        val field = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
        }
        container.addView(field)
        return field
    }

    private fun addSpinner(container: LinearLayout, label: String, values: List<String>): Spinner {
        container.addView(TextView(this).apply {
            text = label
            setPadding(0, 10, 0, 2)
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values
        )
        container.addView(spinner)
        return spinner
    }

    private fun parseAmountPaise(value: String): Long? {
        return runCatching {
            BigDecimal(value.trim())
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull()
    }

    private fun inferBank(sender: String): String {
        val upper = sender.uppercase(Locale.ROOT)
        return when {
            upper.contains("HDFC") -> "HDFC"
            upper.contains("AXIS") -> "AXIS"
            upper.contains("ICICI") -> "ICICI"
            upper.contains("SBI") -> "SBI"
            upper.contains("KOTAK") -> "KOTAK"
            else -> ""
        }
    }

    private fun showEvidenceReview(evidence: SourceEvidence) {
        val candidates = repository.getCandidateTransactionsForEvidence(evidence)
        val matchResult = CrossSourceMatcher.match(evidence, candidates)

        // When the current evidence has exactly one safe candidate, reconcile it
        // immediately. This is still evidence-only: no new transaction is created.
        if (matchResult.outcome == MatchOutcome.MATCHED &&
            matchResult.matchedTransactionId != null
        ) {
            val matchedTransaction = repository.getTransactionById(matchResult.matchedTransactionId)
            val matchedEvidence = repository.resolveEvidenceToTransaction(
                evidence.id,
                matchResult.matchedTransactionId
            )

            if (matchedEvidence != null) {
                FinanceSyncBridge.enqueueEvidence(this, matchedEvidence)
                val label = matchedTransaction?.let { transactionLabel(it) } ?: "matching transaction"
                Toast.makeText(
                    this,
                    "Matched automatically\n" + label,
                    Toast.LENGTH_LONG
                ).show()
                loadReviews()
                return
            }
        }

        if (candidates.isEmpty() || matchResult.outcome == MatchOutcome.UNMATCHED) {
            AlertDialog.Builder(this)
                .setTitle("No safe local match")
                .setMessage(
                    buildEvidenceDetails(evidence) +
                        "\n\nNo local transaction matches this evidence by amount, bank/account, direction, and time."
                )
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val labels = candidates.map { transactionLabel(it) }.toTypedArray()
        var selected = -1

        val candidateSummary =
            "\n\nPossible matching transactions: " + candidates.size + "\n" +
                "Select the transaction that represents the same real-world payment."

        AlertDialog.Builder(this)
            .setTitle("Match " + evidence.sourceType.name + " evidence")
            .setMessage(buildEvidenceDetails(evidence) + candidateSummary)
            .setSingleChoiceItems(labels, -1) { _, which -> selected = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Match") { _, _ ->
                if (selected < 0) {
                    Toast.makeText(this, "Select a transaction first", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val matched = repository.resolveEvidenceToTransaction(
                    evidence.id,
                    candidates[selected].id
                )

                if (matched != null) {
                    FinanceSyncBridge.enqueueEvidence(this, matched)
                    Toast.makeText(this, "Evidence matched successfully", Toast.LENGTH_SHORT).show()
                    loadReviews()
                } else {
                    Toast.makeText(this, "Could not match this evidence", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun buildEvidenceDetails(evidence: SourceEvidence): String {
        val bank = evidence.bankProvider ?: "Unknown bank"
        val account = evidence.accountLastFour?.let { "••••" + it } ?: "Unknown account"
        val reference = evidence.reference ?: "Not available"
        val date = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(evidence.receivedAt))

        return "Amount: ₹%.2f\nDirection: %s\nBank: %s\nAccount: %s\nReference: %s\nReceived: %s"
            .format(
                Locale.getDefault(),
                evidence.amountPaise / 100.0,
                evidence.direction,
                bank,
                account,
                reference,
                date
            )
    }

    private fun transactionLabel(transaction: Transaction): String {
        val merchant = transaction.merchantName?.takeIf { it.isNotBlank() }
            ?: transaction.payeeId
            ?: "Transaction"
        val bank = transaction.bank ?: "Bank unknown"
        val account = transaction.accountLastFour?.let { "••••" + it } ?: "Account unknown"
        val date = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(transaction.timestamp))
        val sign = if (transaction.transactionType.name == "CREDIT") "+" else "-"
        return "%s  %s₹%.2f\n%s • %s\n%s".format(
            Locale.getDefault(),
            merchant,
            sign,
            transaction.amountPaise / 100.0,
            bank,
            account,
            date
        )
    }

    override fun onDestroy() {
        dbHelper.close()
        super.onDestroy()
    }
}
