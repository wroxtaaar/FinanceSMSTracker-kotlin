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
        val date: TextView = view.findViewById(R.id.textViewUnrecognizedDate)
        val createButton: Button = view.findViewById(R.id.buttonCreateTransactionUnrecognized)
        val dismissButton: Button = view.findViewById(R.id.buttonDismissUnrecognized)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_unrecognized_sms, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.sender.text = item.sender
        holder.reason.text = item.reason.replace('_', ' ')
        holder.date.text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(item.receivedAt))
        holder.createButton.setOnClickListener { onCreateTransaction(item) }
        holder.dismissButton.setOnClickListener { onDismiss(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<UnrecognizedSms>) {
        items = newItems
        notifyDataSetChanged()
    }
}
