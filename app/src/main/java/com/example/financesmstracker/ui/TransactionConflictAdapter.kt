package com.example.financesmstracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.data.TransactionConflict

class TransactionConflictAdapter(
    private var conflicts: List<TransactionConflict>,
    private val onClick: (TransactionConflict) -> Unit
) : RecyclerView.Adapter<TransactionConflictAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val summary: TextView = view.findViewById(R.id.textViewConflictSummary)
        val button: Button = view.findViewById(R.id.buttonReviewConflict)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_transaction_conflict, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conflict = conflicts[position]
        val first = conflict.first
        val second = conflict.second
        holder.summary.text = "₹%.2f • %s\n%s vs %s\n%s".format(
            first.amountPaise / 100.0,
            first.transactionType.name,
            first.bank ?: "Unknown bank",
            second.bank ?: "Unknown bank",
            conflict.reason
        )
        holder.button.setOnClickListener { onClick(conflict) }
    }

    override fun getItemCount(): Int = conflicts.size

    fun updateData(newConflicts: List<TransactionConflict>) {
        conflicts = newConflicts
        notifyDataSetChanged()
    }
}
