package com.example.financesmstracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.financesmstracker.integration.EmiNote
import com.example.financesmstracker.integration.OracleAccount
import com.example.financesmstracker.ui.HomeFormat
import com.google.android.material.color.MaterialColors

internal fun MainActivity.cardMeta() = CardMeta(getSharedPreferences("card_meta", Context.MODE_PRIVATE))

/**
 * A swipeable strip of limit / due-date tiles above the transaction list, so you do not have to open the
 * Cards sheet to see them. Cards that share one limit appear as ONE tile. Cards with neither a limit nor a due
 * day set stay out of it.
 */
internal fun MainActivity.renderCardCarousel() {
    val meta = cardMeta()
    val groups = cardAccounts
        .groupBy { meta.groupOf(it.id) ?: it.id }
        .values
        .filter { group -> meta.limitMinor(group.first().id) > 0 || group.any { meta.dueDay(it.id) > 0 } }

    cardsRow.removeAllViews()
    if (groups.isEmpty()) {
        cardsScroll.visibility = View.GONE
        return
    }
    cardsScroll.visibility = View.VISIBLE
    val now = System.currentTimeMillis()
    groups.forEach { group ->
        cardsRow.addView(
            homeCardTile(group, meta, now),
            LinearLayout.LayoutParams(dp(264), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        )
    }
}

private fun MainActivity.homeCardTile(group: List<OracleAccount>, meta: CardMeta, now: Long): View {
    val first = group.first()
    val shared = group.size > 1
    val limit = meta.limitMinor(first.id)
    val outstanding = group.sumOf { it.balanceMinor.coerceAtLeast(0L) }
    val available = meta.availableMinor(first.id, group.sumOf { it.balanceMinor })

    val usedPercent: Int
    val usage: String
    if (limit > 0 && available != null) {
        usedPercent = HomeFormat.utilisationPercent(limit - available, limit)
        usage = "${formatMinor(available)} available of ${formatMinor(limit)}"
    } else if (limit > 0) {
        usedPercent = HomeFormat.utilisationPercent(outstanding, limit)
        usage = "${formatMinor(outstanding)} of ${formatMinor(limit)} used ($usedPercent%)"
    } else {
        usedPercent = 0
        usage = "${formatMinor(outstanding)} outstanding"
    }

    val title = if (shared) {
        "Shared · " + group.joinToString(" + ") { it.last4?.let { l -> "••$l" } ?: displayAccountName(it.name) }
    } else {
        displayAccountName(first.name) + (first.last4?.let { "  ••$it" } ?: "")
    }

    // The nearest due date: the earliest among cards that still owe a bill, else just the first due day set.
    val withDue = group.filter { meta.dueDay(it.id) in 1..31 }
    val pending = withDue
        .filter { it.billBalanceMinor > 0 }
        .map { it to HomeFormat.daysUntilDue(meta.dueDay(it.id), now) }
        .minByOrNull { it.second }
    val dueText: String? = when {
        pending != null -> HomeFormat.dueLabel(pending.second)
        withDue.isNotEmpty() -> "Due " + EmiNote.ordinal(meta.dueDay(withDue.first().id))
        else -> null
    }
    val urgent = pending != null && HomeFormat.dueIsUrgent(pending.second)

    val barColor = when {
        usedPercent >= 80 -> Color.parseColor("#D64545")
        usedPercent >= 50 -> Color.parseColor("#E08A1E")
        else -> MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.CYAN)
    }

    val built = tile {
        isClickable = true
        setOnClickListener { loadOracleAccountsForManualEdit("CREDIT_CARD") }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (dueText != null) {
                addView(TextView(context).apply {
                    text = dueText
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    tag = "due"
                    setTextColor(if (urgent) Color.parseColor("#D64545") else Color.parseColor("#E08A1E"))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            }
        })
        if (limit > 0) {
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = usedPercent
                progressTintList = ColorStateList.valueOf(barColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                ).apply { topMargin = dp(8) }
            })
        }
        addView(caption(usage).apply { setPadding(0, dp(4), 0, 0) })
    }
    AppTheme.styleCardTile(built)
    return built
}
