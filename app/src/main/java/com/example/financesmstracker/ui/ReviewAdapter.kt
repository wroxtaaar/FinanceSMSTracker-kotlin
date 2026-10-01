package com.example.financesmstracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.evidence.SourceEvidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewAdapter(
    private var items: List<SourceEvidence>,
    private val onReview: (SourceEvidence) -> Unit
) : RecyclerView.Adapter<ReviewAdapter.ReviewViewHolder>() {

    class ReviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val source: TextView = view.findViewById(R.id.textViewReviewSource)
        val amount: TextView = view.findViewById(R.id.textViewReviewAmount)
        val details: TextView = view.findViewById(R.id.textViewReviewDetails)
        val status: TextView = view.findViewById(R.id.textViewReviewStatus)
        val button: Button = view.findViewById(R.id.buttonReviewItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_review, parent, false)
        return ReviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) {
        val evidence = items[position]

        holder.source.text = evidence.sourceType.name
        holder.amount.text = String.format(
            Locale.getDefault(),
            "%s₹%.2f",
            if (evidence.direction.equals("CREDIT", true)) "+" else "-",
            evidence.amountPaise / 100.0
        )

        val bank = evidence.bankProvider ?: "Bank unknown"
        val account = evidence.accountLastFour?.let { "••••$it" } ?: "Account unknown"
        val reference = evidence.reference?.let { " • Ref $it" }.orEmpty()
        val date = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(evidence.receivedAt))

        holder.details.text = "$bank • $account$reference\n$date"
        holder.status.text = "Needs review: ${evidence.status.name}"

        holder.button.setOnClickListener { onReview(evidence) }
        holder.itemView.setOnClickListener { onReview(evidence) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<SourceEvidence>) {
        items = newItems
        notifyDataSetChanged()
    }
}
