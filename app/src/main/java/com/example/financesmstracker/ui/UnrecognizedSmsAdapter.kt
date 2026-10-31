package com.example.financesmstracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.data.UnrecognizedSms
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UnrecognizedSmsAdapter(
    private var items: List<UnrecognizedSms>,
    private val onCreateTransaction: (UnrecognizedSms) -> Unit,
    private val onDismiss: (UnrecognizedSms) -> Unit
) : RecyclerView.Adapter<UnrecognizedSmsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val sender: TextView = view.findViewById(R.id.textViewUnrecognizedSender)
        val reason: TextView = view.findViewById(R.id.textViewUnrecognizedReason)
        val preview: TextView = view.findViewById(R.id.textViewUnrecognizedPreview)
        val date: TextView = view.findViewById(R.id.textViewUnrecognizedDate)
        val createButton: Button = view.findViewById(R.id.buttonCreateTransactionUnrecognized)
        val dismissButton: Button = view.findViewById(R.id.buttonDismissUnrecognized)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_unrecognized_sms, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.sender.text = item.sender
        holder.reason.text = explainReason(item.reason)
        holder.preview.text = item.preview?.takeIf { it.isNotBlank() }
            ?: "Message text was not saved for this older item. Use the sender and time to find it in your Messages app."
        holder.date.text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(item.receivedAt))
        holder.createButton.setOnClickListener { onCreateTransaction(item) }
        holder.dismissButton.setOnClickListener { onDismiss(item) }
    }

    private fun explainReason(reason: String): String = when (reason) {
        "UNRECOGNIZED_FINANCIAL_SMS" ->
            "Looks like a money message, but the app could not understand its format."
        "UNTRUSTED_FINANCIAL_SMS" ->
            "Looks like a money message, but the sender is not a known bank."
        "LOW_CONFIDENCE_FINANCIAL_SMS" ->
            "The app was not sure it read this message correctly, so it did not add it."
        "INCOMPLETE_FINANCIAL_SMS" ->
            "Some details (amount, account or direction) were missing."
        else -> reason.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<UnrecognizedSms>) {
        items = newItems
        notifyDataSetChanged()
    }
}
