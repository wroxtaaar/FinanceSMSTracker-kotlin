package com.example.financesmstracker

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** A rounded surface block used to group one account's fields inside a dialog. */
internal fun MainActivity.tile(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(12), dp(14), dp(14))
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(
            MaterialColors.getColor(
                this@tile, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY
            )
        )
        alpha = 110
    }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }
    build()
}

/** Small muted caption that sits above an input. */
internal fun MainActivity.caption(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    alpha = 0.7f
}
