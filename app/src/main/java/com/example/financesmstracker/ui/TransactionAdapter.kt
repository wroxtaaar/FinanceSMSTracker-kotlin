package com.example.financesmstracker.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.TransactionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val onClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    class TransactionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewMerchant: TextView = view.findViewById(R.id.textViewMerchant)
        val textViewAmount: TextView = view.findViewById(R.id.textViewAmount)
        val textViewCategory: TextView = view.findViewById(R.id.textViewCategory)
        val textViewType: TextView = view.findViewById(R.id.textViewType)
        val textViewPaymentMethod: TextView = view.findViewById(R.id.textViewPaymentMethod)
        val textViewBank: TextView = view.findViewById(R.id.textViewBank)
        val textViewDateTime: TextView = view.findViewById(R.id.textViewDateTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val tx = transactions[position]

        val merchantDisplay = listOfNotNull(tx.merchantName, tx.payeeId, tx.bank, "Transaction").firstOrNull { !it.isBlank() } ?: "Transaction"
        holder.textViewMerchant.text = merchantDisplay

        val rupees = tx.amountPaise / 100.0
        val sign = if (tx.transactionType == TransactionType.CREDIT) "+" else "-"
        holder.textViewAmount.text = String.format(Locale.getDefault(), "%s₹%.2f", sign, rupees)
        if (tx.transactionType == TransactionType.CREDIT) {
            holder.textViewAmount.setTextColor(Color.parseColor("#2E7D32")) // Green
        } else {
            holder.textViewAmount.setTextColor(Color.parseColor("#C62828")) // Red
        }

        holder.textViewCategory.text = tx.category ?: "OTHER"
        holder.textViewType.text = tx.transactionType.name
        holder.textViewPaymentMethod.text = tx.paymentMethod.name
        holder.textViewBank.text = tx.bank ?: ""

        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        holder.textViewDateTime.text = sdf.format(Date(tx.timestamp))

        holder.itemView.setOnClickListener { onClick(tx) }
    }

    override fun getItemCount(): Int = transactions.size

    fun updateData(newTransactions: List<Transaction>) {
        transactions = newTransactions
        notifyDataSetChanged()
    }
}
