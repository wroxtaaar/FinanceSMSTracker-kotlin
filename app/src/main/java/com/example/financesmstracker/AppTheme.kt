package com.example.financesmstracker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The three looks of the app. The choice is stored in SharedPreferences and applied by calling
 * [apply] at the very start of an Activity's onCreate (before super.onCreate).
 */
enum class AppTheme(
    val key: String,
    val label: String,
    val blurb: String,
    @StyleRes val style: Int,
) {
    CALM_TEAL("teal", "Calm Teal", "Teal cards, follows your phone's light / dark setting", R.style.Theme_FinanceSMSTracker),
    DARK_LEDGER("ledger", "Dark Ledger", "Always dark, mint accent, big numbers", R.style.Theme_FinanceSMSTracker_Ledger),
    WARM_PAPER("paper", "Warm Paper", "Cream background, serif numbers, terracotta accent", R.style.Theme_FinanceSMSTracker_Paper);

    companion object {
        private const val PREFS = "app_theme"
        private const val KEY = "theme"

        fun current(context: Context): AppTheme {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.key == saved } ?: CALM_TEAL
        }

        /** Call first thing in onCreate, before super.onCreate(). */
        fun apply(activity: Activity) {
            activity.setTheme(current(activity).style)
        }

        fun set(activity: Activity, theme: AppTheme) {
            if (theme == current(activity)) return
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.key).apply()
            activity.recreate()
        }

        /** Status / navigation bar icons that stay readable on this theme, whatever the phone's own setting. */
        fun edgeToEdge(activity: ComponentActivity) {
            val clear = Color.TRANSPARENT
            when (current(activity)) {
                DARK_LEDGER -> activity.enableEdgeToEdge(SystemBarStyle.dark(clear), SystemBarStyle.dark(clear))
                WARM_PAPER -> activity.enableEdgeToEdge(SystemBarStyle.light(clear, clear), SystemBarStyle.light(clear, clear))
                CALM_TEAL -> activity.enableEdgeToEdge()
            }
        }

        private fun attrColor(context: Context, attr: Int, fallback: Int): Int =
            MaterialColors.getColor(context, attr, fallback)

        private fun view(activity: Activity, id: Int): TextView = activity.findViewById(id)

        /** Typography and small structural touches that a theme alone cannot express (home screen). */
        fun styleHome(activity: Activity) {
            val title = view(activity, R.id.textViewTitle)
            val amount = view(activity, R.id.textViewTrueAvailable)
            val trueLabel = view(activity, R.id.textViewTrueLabel)
            val pill = view(activity, R.id.textViewNotificationStatus)
            val statLabels = listOf(
                R.id.textViewStatBankLabel, R.id.textViewStatSplitwiseLabel, R.id.textViewStatCardsLabel
            ).map { view(activity, it) }
            val statValues = listOf(
                R.id.textViewBankCash, R.id.textViewSplitwiseReceivable, R.id.textViewCardOutstanding
            ).map { view(activity, it) }
            val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val serif = Typeface.create("serif", Typeface.BOLD)
            when (current(activity)) {
                CALM_TEAL -> {
                    title.textSize = 26f
                    amount.textSize = 40f
                    amount.letterSpacing = -0.02f
                }
                DARK_LEDGER -> {
                    title.textSize = 15f
                    title.typeface = medium
                    amount.typeface = medium
                    amount.textSize = 52f
                    amount.letterSpacing = -0.04f
                    trueLabel.text = trueLabel.text.toString().uppercase()
                    trueLabel.letterSpacing = 0.08f
                    trueLabel.textSize = 12f
                    statLabels.forEach {
                        it.text = it.text.toString().uppercase()
                        it.letterSpacing = 0.06f
                    }
                    statValues.forEach { it.typeface = medium }
                    pill.background = null
                }
                WARM_PAPER -> {
                    title.textSize = 28f
                    title.typeface = serif
                    amount.typeface = serif
                    amount.textSize = 48f
                    amount.letterSpacing = -0.02f
                    statValues.forEach { it.typeface = serif }
                    pill.background = null
                }
            }
        }

        private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

        /**
         * The four round / pill shortcuts under the hero. The original (hidden) buttons keep all the
         * behaviour; these views just forward their clicks, so every existing feature keeps working.
         */
        fun setupQuickActions(activity: Activity) {
            val row = activity.findViewById<LinearLayout>(R.id.oracleControlsRow)
            val theme = current(activity)
            val d = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val onSurface = attrColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            val primary = attrColor(activity, com.google.android.material.R.attr.colorPrimary, Color.CYAN)
            val card = attrColor(activity, R.attr.cardSurface, Color.WHITE)
            val field = attrColor(activity, R.attr.fieldSurface, Color.LTGRAY)
            val line = attrColor(activity, R.attr.dividerColor, Color.LTGRAY)
            val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

            row.gravity = Gravity.CENTER_VERTICAL
            // Splitwise / Banks / Cards are now the three balance tiles in the hero (see setupHeroTiles).
            listOf(R.id.buttonEditSplitwise, R.id.buttonEditBankAccounts, R.id.buttonEditCreditCards)
                .forEach { activity.findViewById<View>(it).visibility = View.GONE }
            val specs = emptyList<Triple<Int, Int, String>>() // the More pill is gone: the gear icon opens the menu
            specs.forEach { (buttonId, iconRes, label) ->
                val source = activity.findViewById<View>(buttonId)
                source.visibility = View.GONE
                val isMore = label == "More"

                val icon = ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(primary)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    if (isMore) tag = "quickMoreIcon"
                }
                val text = TextView(activity).apply {
                    this.text = label
                    setTextColor(onSurface)
                    typeface = medium
                    gravity = Gravity.CENTER
                    if (isMore) tag = "quickMoreLabel"
                }
                val item = LinearLayout(activity).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener { if (source.isEnabled) source.performClick() }
                }

                // An outlined pill with icon + label.
                item.orientation = LinearLayout.HORIZONTAL
                item.gravity = Gravity.CENTER_VERTICAL
                item.background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), line)
                }
                item.setPadding(dp(14), 0, dp(16), 0)
                item.addView(icon, lp(dp(18), dp(18)))
                text.textSize = 13f
                item.addView(text, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                row.addView(item, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            }
        }

        /** The three balance tiles in the hero open Banks, Splitwise and Cards; the top icons open Settings and Theme. */
        fun setupHeroTiles(activity: Activity) {
            val d = activity.resources.displayMetrics.density
            val tiles = listOf(
                R.id.textViewStatBankLabel to R.id.buttonEditBankAccounts,
                R.id.textViewStatSplitwiseLabel to R.id.buttonEditSplitwise,
                R.id.textViewStatCardsLabel to R.id.buttonEditCreditCards,
            )
            val ripple = android.util.TypedValue().also {
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId
            tiles.forEach { (labelId, buttonId) ->
                val tile = activity.findViewById<View>(labelId).parent as View
                val source = activity.findViewById<View>(buttonId)
                tile.isClickable = true
                tile.isFocusable = true
                if (ripple != 0) tile.foreground = ContextCompat.getDrawable(activity, ripple)
                tile.setOnClickListener { if (source.isEnabled) source.performClick() }
            }
            val onSurface = attrColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            listOf(R.id.buttonThemeTop, R.id.buttonSettingsTop).forEach {
                activity.findViewById<ImageView>(it).imageTintList = ColorStateList.valueOf(onSurface)
            }
            activity.findViewById<View>(R.id.buttonThemeTop).setOnClickListener { showPicker(activity) }
            // The gear opens the full menu (Review, Spending, Sync, Settings, ...). The old More button is hidden.
            activity.findViewById<View>(R.id.buttonMore).visibility = View.GONE
            activity.findViewById<View>(R.id.buttonSettingsTop).setOnClickListener {
                activity.findViewById<View>(R.id.buttonMore).performClick()
            }
        }

        /** The red unreviewed-items count on the More shortcut. */
        fun setMoreBadge(activity: Activity, count: Int) {
            val row = activity.findViewById<View>(R.id.oracleControlsRow) ?: return
            val red = Color.parseColor("#D64545")
            val primary = attrColor(activity, com.google.android.material.R.attr.colorPrimary, Color.CYAN)
            val onSurface = attrColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            // The review count now lives on the gear: it turns red while items wait.
            activity.findViewById<ImageView>(R.id.buttonSettingsTop)?.imageTintList =
                ColorStateList.valueOf(if (count > 0) red else onSurface)
            row.findViewWithTag<TextView>("quickMoreLabel")?.let {
                it.text = if (count > 0) "More ($count)" else "More"
                it.setTextColor(if (count > 0) red else onSurface)
            }
            row.findViewWithTag<ImageView>("quickMoreIcon")?.imageTintList =
                ColorStateList.valueOf(if (count > 0) red else primary)
        }

        /** Look of the credit-card limit tiles above the transaction list. */
        fun styleCardTile(tile: View) {
            val context = tile.context
            val d = context.resources.displayMetrics.density
            when (current(context)) {
                CALM_TEAL -> tile.background = GradientDrawable().apply {
                    cornerRadius = 20 * d
                    setColor(attrColor(context, R.attr.cardSurface, Color.WHITE))
                    setStroke((1 * d).toInt(), attrColor(context, R.attr.dividerColor, Color.LTGRAY))
                }
                DARK_LEDGER -> tile.background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(Color.TRANSPARENT)
                    setStroke((1 * d).toInt(), attrColor(context, R.attr.dividerColor, Color.DKGRAY))
                }
                WARM_PAPER -> {
                    tile.background = GradientDrawable().apply {
                        cornerRadius = 18 * d
                        setColor(ContextCompat.getColor(context, R.color.paper_ink))
                    }
                    tintTexts(tile, ContextCompat.getColor(context, R.color.paper_background))
                }
            }
        }

        private fun tintTexts(view: View, color: Int) {
            if (view is TextView) {
                if (view.tag != "due") view.setTextColor(color)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) tintTexts(view.getChildAt(i), color)
            }
        }

        fun showPicker(activity: Activity) {
            val all = entries.toList()
            val items = all.map { "${it.label}\n${it.blurb}" }.toTypedArray()
            MaterialAlertDialogBuilder(activity)
                .setTitle("Theme")
                .setSingleChoiceItems(items, all.indexOf(current(activity))) { dialog, which ->
                    dialog.dismiss()
                    set(activity, all[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
