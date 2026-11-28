package com.example.financesmstracker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar

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

/** A bottom sheet with a title, a scrolling body and Cancel / Save buttons; replaces the centred form dialogs. */
internal class FormSheet(val dialog: BottomSheetDialog, val positive: MaterialButton) {
    var positiveEnabled: Boolean
        get() = positive.isEnabled
        set(value) { positive.isEnabled = value }

    fun dismiss() = dialog.dismiss()
}

internal fun MainActivity.formSheet(
    title: String,
    body: View,
    positiveText: String = "Save",
    subtitle: String? = null,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    onPositive: (FormSheet) -> Unit
): FormSheet {
    val dialog = BottomSheetDialog(this)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(BottomSheetDragHandleView(this))
    root.addView(TextView(this).apply {
        text = title
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(20), dp(4), dp(20), dp(if (subtitle == null) 8 else 2))
    })
    if (subtitle != null) {
        root.addView(caption(subtitle).apply {
            textSize = 13f
            setPadding(dp(20), 0, dp(20), dp(8))
        })
    }
    val scroll = (body as? NestedScrollView) ?: NestedScrollView(this).apply { addView(body) }
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    val positive = MaterialButton(this).apply { text = positiveText }
    val cancel = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }
    root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(20), dp(12))
        if (neutralText != null) {
            addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = neutralText
                setOnClickListener { onNeutral?.invoke() }
            })
        }
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(cancel)
        addView(positive)
    })

    dialog.setContentView(root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

    val sheet = FormSheet(dialog, positive)
    positive.setOnClickListener { onPositive(sheet) }
    dialog.show()
    return sheet
}

internal data class SheetAction(
    val icon: String,
    val title: String,
    val badge: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** A solid bottom-sheet menu (replaces the translucent popup that overlapped the list). */
internal fun MainActivity.actionSheet(title: String?, actions: List<SheetAction>) {
    val dialog = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
    }
    root.addView(BottomSheetDragHandleView(this))
    if (title != null) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(4), dp(20), dp(8))
        })
    }
    val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
    actions.forEach { action ->
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(ripple.resourceId)
            addView(TextView(context).apply {
                text = action.icon
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(context).apply {
                text = action.title
                textSize = 16f
                setPadding(dp(12), 0, 0, 0)
                if (action.destructive) setTextColor(Color.parseColor("#D64545"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            action.badge?.let { badge ->
                addView(TextView(context).apply {
                    text = badge
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#D64545"))
                    }
                })
            }
            setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
        })
    }
    dialog.setContentView(root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    dialog.show()
}

/** A short message at the bottom of the screen, optionally with one action such as Undo. */
internal fun MainActivity.showSnack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    val bar = Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_LONG)
    if (actionLabel != null && action != null) bar.setAction(actionLabel) { action() }
    bar.show()
}
