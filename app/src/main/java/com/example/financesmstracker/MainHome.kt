package com.example.financesmstracker

import android.app.AlertDialog
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.financesmstracker.ui.HomeFormat
import java.util.Locale

/** A small grey label above a bold value, in one TextView. */
internal fun twoLine(label: String, value: String, labelScale: Float): CharSequence {
    val text = SpannableStringBuilder(label).append('\n').append(value)
    text.setSpan(RelativeSizeSpan(labelScale), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), label.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text
}

/** Splitwise / Banks / Cards stay on screen; everything else lives behind "More". */
internal fun MainActivity.setupMoreMenu() {
    val more = findViewById<Button>(R.id.buttonMore)
    more.setOnClickListener { anchor ->
        val menu = PopupMenu(this, anchor)
        val review = buttonReviewReconcile.text.toString()
        val items = listOf(
            review to { buttonReviewReconcile.performClick() },
            "Spending" to { showSpendingDialog(0) },
            "Sync Gmail" to {
                if (buttonGmailSync.isEnabled) buttonGmailSync.performClick()
                else Toast.makeText(this, "Already syncing…", Toast.LENGTH_SHORT).show()
            },
            "SMS log" to { findViewById<Button>(R.id.buttonSmsLog).performClick() },
            "Restore history" to { buttonRestoreHistory.performClick() },
            "Clear history" to { buttonClearLocalHistory.performClick() },
            "Settings" to { buttonOracleSettings.performClick() }
        )
        items.forEachIndexed { index, (title, _) -> menu.menu.add(0, index, index, title) }
        menu.setOnMenuItemClickListener { item ->
            items[item.itemId].second()
            true
        }
        menu.show()
    }
}

/** Month of spending by category, with a bar per category (own-account transfers and credits left out). */
internal fun MainActivity.showSpendingDialog(monthOffset: Int) {
    val now = System.currentTimeMillis()
    val (start, end) = HomeFormat.monthRange(now, monthOffset)
    val totals = HomeFormat.spendingByCategory(repository.getAllTransactions(), start, end)
    val grand = totals.sumOf { it.minor }

    val dp = resources.displayMetrics.density
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
    }
    box.addView(TextView(this).apply {
        text = twoLine("Total spent", formatMinor(grand), 0.55f)
        textSize = 28f
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    if (totals.isEmpty()) {
        box.addView(TextView(this).apply { text = "No spending recorded this month." })
    }
    totals.forEach { row ->
        val style = HomeFormat.categoryStyle(row.category)
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
            addView(TextView(context).apply {
                text = style.symbol + " " + row.category.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = formatMinor(row.minor)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        })
        box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (grand > 0) (row.minor * 1000 / grand).toInt() else 0
            progressTintList = android.content.res.ColorStateList.valueOf(style.color)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt())
        })
    }

    AlertDialog.Builder(this)
        .setTitle("Spending · " + HomeFormat.monthTitle(start))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton("◀ Earlier") { _, _ -> showSpendingDialog(monthOffset - 1) }
        .apply { if (monthOffset < 0) setNeutralButton("Later ▶") { _, _ -> showSpendingDialog(monthOffset + 1) } }
        .setPositiveButton("Close", null)
        .show()
}
