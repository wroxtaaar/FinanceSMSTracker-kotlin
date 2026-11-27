package com.example.financesmstracker.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.example.financesmstracker.R
import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.integration.FinanceSyncClient
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.IndianMoney
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val onClick: (Transaction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** What the list shows: a [HomeFormat.DayHeader] before each day's transactions. */
    private var rows: List<Any> = build(transactions)

    private fun build(list: List<Transaction>): List<Any> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Any>(list.size + 8)
        var i = 0
        while (i < list.size) {
            val label = HomeFormat.dayLabel(list[i].timestamp, now)
            var j = i
            while (j < list.size && HomeFormat.dayLabel(list[j].timestamp, now) == label) j++
            val day = list.subList(i, j)
            out.add(HomeFormat.DayHeader(label, HomeFormat.daySpendMinor(day)))
            out.addAll(day)
            i = j
        }
        return out
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.textViewDayHeader)
        val total: TextView = view.findViewById(R.id.textViewDayTotal)
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
        val icon: TextView = view.findViewById(R.id.textViewCategoryIcon)
        val merchant: TextView = view.findViewById(R.id.textViewMerchant)
        val amount: TextView = view.findViewById(R.id.textViewAmount)
        val subtitle: TextView = view.findViewById(R.id.textViewPaymentMethod)
        val dateTime: TextView = view.findViewById(R.id.textViewDateTime)
        val reviewChip: TextView = view.findViewById(R.id.textViewReviewChip)
        val divider: View = view.findViewById(R.id.divider)
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is HomeFormat.DayHeader) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderViewHolder(inflater.inflate(R.layout.item_day_header, parent, false))
        } else {
            TransactionViewHolder(inflater.inflate(R.layout.item_transaction, parent, false))
        }
    }

    /** One rounded surface per day: only the first row has rounded top corners, only the last rounded bottom ones. */
    private fun rowBackground(view: View, first: Boolean, last: Boolean, isSelected: Boolean): GradientDrawable {
        val context = view.context
        val radius = context.resources.displayMetrics.density * 16f
        val base = MaterialColors.getColor(
            view,
            R.attr.cardSurface,
            ContextCompat.getColor(context, R.color.surface_container)
        )
        val top = if (first) radius else 0f
        val bottom = if (last) radius else 0f
        return GradientDrawable().apply {
            setColor(if (isSelected) ColorUtils.compositeColors(0x667E57C2, base) else base)
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (row is HomeFormat.DayHeader) {
            val header = viewHolder as HeaderViewHolder
            header.label.text = row.label
            header.total.text = if (row.spentMinor > 0) IndianMoney.signed(row.spentMinor, credit = false) else ""
            return
        }
        val holder = viewHolder as TransactionViewHolder
        val tx = row as Transaction

        val payee = HomeFormat.payeeOrNull(tx)
        holder.merchant.text = payee ?: "Unknown payee"
        holder.merchant.alpha = if (payee == null) 0.6f else 1f
        holder.reviewChip.visibility = if (payee == null) View.VISIBLE else View.GONE
        holder.icon.showCategoryBadge(tx.category)

        val credit = tx.transactionType == TransactionType.CREDIT
        val sign = if (credit) "+" else "-"
        val foreign = foreignAmounts[tx.id]
        if (foreign != null) {
            // Show what was actually charged in rupees, with the original
            // amount underneath. "≈" marks an estimate not yet made exact.
            val approx = if (foreign.exact) "" else "≈"
            val main = "${sign}${approx}₹" + IndianMoney.group(foreign.inrMinor)
            val original = String.format(
                Locale.US, "\n%s %.2f", foreign.origCurrency, foreign.origAmountMinor / 100.0
            )
            holder.amount.text = SpannableStringBuilder(main + original).apply {
                setSpan(RelativeSizeSpan(0.7f), main.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (tx.currency.equals("INR", ignoreCase = true)) {
            holder.amount.text = IndianMoney.signed(tx.amountPaise, credit)
        } else {
            holder.amount.text = String.format(
                Locale.US, "%s%s %,.2f", sign, tx.currency.uppercase(), tx.amountPaise / 100.0
            )
        }
        holder.amount.setTextColor(
            MaterialColors.getColor(
                holder.itemView,
                if (credit) R.attr.creditColor else R.attr.debitColor,
                Color.parseColor(if (credit) "#2E9E5B" else "#D64545")
            )
        )

        // "Groceries · Axis ••9206 · Card": the source (SMS / e-mail) lives in the details.
        val category = HomeFormat.sentenceCase(tx.category ?: "OTHER")
        val account = listOfNotNull(
            HomeFormat.bankLabel(tx.bank),
            tx.accountLastFour?.takeIf { it.isNotBlank() }?.let { "••$it" }
        ).joinToString(" ")
        val how = if (tx.accountType == AccountType.CREDIT_CARD) "Card" else HomeFormat.sentenceCase(tx.paymentMethod.name)
        holder.subtitle.text = listOf(category, account.takeIf { it.isNotBlank() }, how)
            .filterNotNull().joinToString(" · ")

        holder.dateTime.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(tx.timestamp))

        val first = position == 0 || rows[position - 1] !is Transaction
        val last = position == rows.size - 1 || rows[position + 1] !is Transaction
        holder.divider.visibility = if (last) View.GONE else View.VISIBLE
        holder.itemView.background = rowBackground(holder.itemView, first, last, tx.id in selected)

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
