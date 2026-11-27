package com.example.financesmstracker.ui

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** A soft tinted circle with the category symbol; reads well on both light and dark surfaces. */
internal fun TextView.showCategoryBadge(category: String?) {
    val style = HomeFormat.categoryStyle(category)
    text = style.symbol
    setTextColor(style.color)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ColorUtils.setAlphaComponent(style.color, 0x33))
    }
}
