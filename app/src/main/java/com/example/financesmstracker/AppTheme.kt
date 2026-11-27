package com.example.financesmstracker

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StyleRes
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

        /** Typography that cannot be set from a theme: the home title and the big "True available" number. */
        fun styleHome(activity: Activity) {
            val title = activity.findViewById<TextView>(R.id.textViewTitle)
            val amount = activity.findViewById<TextView>(R.id.textViewTrueAvailable)
            when (current(activity)) {
                WARM_PAPER -> {
                    val serif = Typeface.create("serif", Typeface.BOLD)
                    title.typeface = serif
                    amount.typeface = serif
                    amount.textSize = 36f
                }
                DARK_LEDGER -> {
                    amount.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    amount.letterSpacing = -0.03f
                    amount.textSize = 40f
                }
                CALM_TEAL -> Unit
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
