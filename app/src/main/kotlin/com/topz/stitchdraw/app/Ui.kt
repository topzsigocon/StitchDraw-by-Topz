package com.topz.stitchdraw.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.topz.stitchdraw.workspace.ContextMenuItem

/** Dark charcoal palette + tiny view helpers (no XML layouts, no external UI libraries). */
object Theme {
    const val BG = 0xFF0E0E10.toInt()
    const val CANVAS = 0xFF18181B.toInt()
    const val PANEL = 0xFF1F1F24.toInt()
    const val PANEL_TITLE = 0xFF2A2A31.toInt()
    const val BORDER = 0xFF3C3C46.toInt()
    const val TEXT = 0xFFE8E8EE.toInt()
    const val TEXT_DIM = 0xFF9A9AA8.toInt()
    const val ACCENT = 0xFF29B6F6.toInt()
    const val OK = 0xFF66BB6A.toInt()
    const val WARN = 0xFFFFB74D.toInt()
    const val DANGER = 0xFFEF5350.toInt()
    const val PURPLE = 0xFFAB47BC.toInt()

    /** Colorful vector-style glyph tints per tool group. */
    val GLYPH = intArrayOf(ACCENT, OK, WARN, PURPLE, 0xFFFF7043.toInt(), 0xFF26C6DA.toInt())
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun View.roundBg(color: Int, radiusDp: Float = 8f, strokeColor: Int = 0, strokeDp: Float = 0f) {
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
        if (strokeDp > 0f) setStroke(context.dp(strokeDp).coerceAtLeast(1), strokeColor)
    }
}

fun Context.label(text: String, sp: Float = 13f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Touch-friendly glyph button; [size] is already scaled by the workspace icon size. */
fun Context.glyphButton(glyph: String, tint: Int, sizeDp: Float, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f)
        setTextColor(tint)
        minimumWidth = dp(sizeDp); minimumHeight = dp(sizeDp)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        roundBg(Theme.PANEL_TITLE, 8f)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.textButton(text: String, accent: Int = Theme.ACCENT, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(accent)
        minHeight = dp(40); minimumHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        roundBg(Theme.PANEL_TITLE, 8f, Theme.BORDER, 1f)
        setOnClickListener { onClick() }
    }

fun Context.swatch(rgb: Int, sizeDp: Int = 22): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { rightMargin = dp(8) }
    roundBg(rgb or (0xFF shl 24), 6f, Theme.BORDER, 1f)
}

/** Dark pop-up menu shown at a screen position (long press, tool tap, canvas tap). */
fun showContextMenu(anchor: View, screenX: Int, screenY: Int, items: List<ContextMenuItem>, onPick: (ContextMenuItem) -> Unit) {
    val ctx = anchor.context
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        roundBg(Theme.PANEL_TITLE, 10f, Theme.BORDER, 1f)
        setPadding(0, ctx.dp(4), 0, ctx.dp(4))
    }
    val popup = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.elevation = ctx.dp(8).toFloat()
    for (item in items) {
        val check = when (item.checked) { true -> "✓  "; false -> "     "; null -> "" }
        val row = ctx.label(check + item.label, 15f, if (item.enabled) Theme.TEXT else Theme.TEXT_DIM).apply {
            setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(20), ctx.dp(11))
            minWidth = ctx.dp(190)
            if (item.enabled) setOnClickListener { popup.dismiss(); onPick(item) }
        }
        box.addView(row)
    }
    popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, screenX, screenY)
}
