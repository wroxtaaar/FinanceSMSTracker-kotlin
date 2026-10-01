package com.example.financesmstracker

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.data.FinanceDatabaseHelper
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.data.ReviewStatus
import com.example.financesmstracker.data.TransactionRepository
import com.example.financesmstracker.evidence.SourceEvidence
import com.example.financesmstracker.integration.FinanceSyncBridge
import com.example.financesmstracker.ui.ReviewAdapter
import com.example.financesmstracker.ui.UnrecognizedSmsAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewActivity : AppCompatActivity() {

    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var repository: TransactionRepository
    private lateinit var adapter: ReviewAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var unrecognizedAdapter: UnrecognizedSmsAdapter
    private lateinit var unrecognizedRecyclerView: RecyclerView
    private lateinit var emptyUnrecognizedText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        dbHelper = FinanceDatabaseHelper(this)
        repository = TransactionRepository(dbHelper)

        recyclerView = findViewById(R.id.recyclerViewReviews)
        emptyText = findViewById(R.id.textViewNoReviews)
        unrecognizedRecyclerView = findViewById(R.id.recyclerViewUnrecognized)
        emptyUnrecognizedText = findViewById(R.id.textViewNoUnrecognized)

        adapter = ReviewAdapter(emptyList()) { evidence ->
            showEvidenceReview(evidence)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        unrecognizedAdapter = UnrecognizedSmsAdapter(emptyList()) { item -> dismissUnrecognizedSms(item.id) }
        unrecognizedRecyclerView.layoutManager = LinearLayoutManager(this)
        unrecognizedRecyclerView.adapter = unrecognizedAdapter

        findViewById<Button>(R.id.buttonRefreshReviews).setOnClickListener {
            loadReviews()
        }
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

    private fun showEvidenceReview(evidence: SourceEvidence) {
        val candidates = repository.getCandidateTransactionsForEvidence(evidence)

        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No matching transaction")
                .setMessage(buildEvidenceDetails(evidence))
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val labels = candidates.map { transactionLabel(it) }.toTypedArray()
        var selected = -1

        AlertDialog.Builder(this)
            .setTitle("Match ${evidence.sourceType.name} evidence")
            .setMessage(buildEvidenceDetails(evidence))
            .setSingleChoiceItems(labels, -1) { _, which ->
                selected = which
            }
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
        val account = evidence.accountLastFour?.let { "••••$it" } ?: "Unknown account"
        val reference = evidence.reference ?: "Not available"
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(evidence.receivedAt))

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
        val merchant = transaction.merchantName
            ?.takeIf { it.isNotBlank() }
            ?: transaction.payeeId
            ?: "Transaction"

        val bank = transaction.bank ?: "Bank unknown"
        val account = transaction.accountLastFour?.let { "••••$it" } ?: "Account unknown"
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(transaction.timestamp))

        val sign = if (transaction.transactionType.name == "CREDIT") "+" else "-"
        return "%s  %s₹%.2f\n%s • %s\n%s"
            .format(
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
