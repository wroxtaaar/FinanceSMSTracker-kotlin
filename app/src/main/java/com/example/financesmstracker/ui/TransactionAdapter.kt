package com.example.financesmstracker.ui

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.parser.TransactionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val onClick: (Transaction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** What the list shows: day headings (String) between transactions. */
    private var rows: List<Any> = build(transactions)

    private fun build(list: List<Transaction>): List<Any> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Any>(list.size + 8)
        var last: String? = null
        for (tx in list) {
            val label = HomeFormat.dayLabel(tx.timestamp, now)
            if (label != last) { out.add(label); last = label }
            out.add(tx)
        }
        return out
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    /** Long-press selects rows (to send several to Expense as one expense); taps then toggle. */
    private val selected = linkedSetOf<Long>()
    var onSelectionChanged: (Int) -> Unit = {}

    fun selectedTransactions(): List<Transaction> = transactions.filter { it.id in selected }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun toggle(tx: Transaction) {
        if (!selected.remove(tx.id)) selected.add(tx.id)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    /** Rupee amounts Oracle holds for foreign spends, keyed by local row id. */
    private var foreignAmounts: Map<Long, FinanceSyncClient.ForeignAmountInfo> = emptyMap()

    fun setForeignAmounts(amounts: Map<Long, FinanceSyncClient.ForeignAmountInfo>) {
        foreignAmounts = amounts
        notifyDataSetChanged()
    }

    class TransactionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val defaultCardColor = (view as? androidx.cardview.widget.CardView)?.cardBackgroundColor
        val textViewCategoryIcon: TextView = view.findViewById(R.id.textViewCategoryIcon)
        val textViewMerchant: TextView = view.findViewById(R.id.textViewMerchant)
        val textViewAmount: TextView = view.findViewById(R.id.textViewAmount)
        val textViewCategory: TextView = view.findViewById(R.id.textViewCategory)
        val textViewType: TextView = view.findViewById(R.id.textViewType)
        val textViewPaymentMethod: TextView = view.findViewById(R.id.textViewPaymentMethod)
        val textViewBank: TextView = view.findViewById(R.id.textViewBank)
        val textViewDateTime: TextView = view.findViewById(R.id.textViewDateTime)
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is String) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderViewHolder(inflater.inflate(R.layout.item_day_header, parent, false))
        } else {
            TransactionViewHolder(inflater.inflate(R.layout.item_transaction, parent, false))
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is String) {
            (viewHolder as HeaderViewHolder).text.text = row
            return
        }
        val holder = viewHolder as TransactionViewHolder
        val tx = row as Transaction

        val payee = HomeFormat.payeeOrNull(tx)
        holder.textViewMerchant.text = payee ?: "Unknown payee"
        holder.textViewMerchant.alpha = if (payee == null) 0.55f else 1f
        val style = HomeFormat.categoryStyle(tx.category)
        holder.textViewCategoryIcon.text = style.symbol
        holder.textViewCategoryIcon.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(style.color)
        }

        val rupees = tx.amountPaise / 100.0
        val sign = if (tx.transactionType == TransactionType.CREDIT) "+" else "-"
        val symbol = if (tx.currency.equals("INR", ignoreCase = true)) "₹" else tx.currency.uppercase() + " "
        val foreign = foreignAmounts[tx.id]
        if (foreign != null) {
            // Show what was actually charged in rupees, with the original
            // amount underneath. "≈" marks an estimate not yet made exact.
            val approx = if (foreign.exact) "" else "≈"
            val main = String.format(
                Locale.US, "%s%s₹%.2f", sign, approx, foreign.inrMinor / 100.0
            )
            val original = String.format(
                Locale.US, "\n%s %.2f", foreign.origCurrency, foreign.origAmountMinor / 100.0
            )
            holder.textViewAmount.text = SpannableStringBuilder(main + original).apply {
                setSpan(
                    RelativeSizeSpan(0.7f), main.length, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            holder.textViewAmount.text =
                String.format(Locale.getDefault(), "%s%s%.2f", sign, symbol, rupees)
        }
        if (tx.transactionType == TransactionType.CREDIT) {
            holder.textViewAmount.setTextColor(Color.parseColor("#2E9E5B")) // Green
        } else {
            holder.textViewAmount.setTextColor(Color.parseColor("#D64545")) // Red
        }

        holder.textViewCategory.text = (tx.category ?: "OTHER").replace('_', ' ')
        holder.textViewType.text = tx.transactionType.name
        // Shown on the right, under the bank name: "Credit card" for card
        // accounts, otherwise how it was paid (UPI, NEFT, ...).
        val how = if (tx.accountType == com.example.financesmstracker.parser.AccountType.CREDIT_CARD) {
            "Credit card"
        } else {
            tx.paymentMethod.name
        }
        val account = listOfNotNull(
            tx.bank?.takeIf { it.isNotBlank() },
            tx.accountLastFour?.takeIf { it.isNotBlank() }?.let { "·$it" }
        ).joinToString(" ")
        holder.textViewPaymentMethod.text = listOf(
            account.takeIf { it.isNotBlank() },
            how,
            com.example.financesmstracker.data.TransactionSource.label(tx.smsHash)
        ).filterNotNull().joinToString(" · ")
        holder.textViewBank.text = tx.bank ?: ""

        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        holder.textViewDateTime.text = sdf.format(Date(tx.timestamp))

        (holder.itemView as? androidx.cardview.widget.CardView)?.let { card ->
            if (tx.id in selected) card.setCardBackgroundColor(Color.parseColor("#667E57C2"))
            else holder.defaultCardColor?.let { card.setCardBackgroundColor(it) }
        }
        holder.itemView.setOnClickListener { if (selected.isEmpty()) onClick(tx) else toggle(tx) }
        holder.itemView.setOnLongClickListener { toggle(tx); true }
    }

    override fun getItemCount(): Int = rows.size

    fun updateData(newTransactions: List<Transaction>) {
        transactions = newTransactions
        rows = build(newTransactions)
        val ids = newTransactions.map { it.id }.toSet()
        if (selected.retainAll(ids)) onSelectionChanged(selected.size)
        notifyDataSetChanged()
    }
}
