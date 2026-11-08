package com.topz.stitchdraw.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Bottom-sheet dialog with a back stack. Each page is a builder lambda so [refresh] can rebuild the
 * current page after the data behind it changed (for example after a drag-reorder).
 */
class NavDialog(private val ctx: Context) {
    private class Page(val title: String, val build: () -> View)

    private val dialog = Dialog(ctx)
    private val pages = ArrayList<Page>()
    private val titleView = ctx.label("", 17f, Theme.TEXT, bold = true)
    private val back = ctx.glyphButton("←", Theme.ACCENT, 40f) { pop() }
    private val body = FrameLayout(ctx)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            roundBg(Theme.PANEL, 16f, Theme.BORDER, 1f)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        }
        head.addView(back)
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(8) })
        head.addView(ctx.glyphButton("✕", Theme.DANGER, 40f) { dialog.dismiss() })
        root.addView(head)
        val h = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                if (pages.size > 1) pop() else dialog.dismiss()
                true
            } else false
        }
    }

    fun push(title: String, build: () -> View) {
        pages.add(Page(title, build))
        render()
    }

    fun pop() {
        if (pages.size > 1) { pages.removeAt(pages.lastIndex); render() } else dialog.dismiss()
    }

    /** Rebuilds the page that is currently shown. */
    fun refresh() = render()

    fun dismiss() = dialog.dismiss()

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.96f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
        }
    }

    private fun render() {
        val p = pages.lastOrNull() ?: return
        titleView.text = p.title
        back.visibility = if (pages.size > 1) View.VISIBLE else View.INVISIBLE
        body.removeAllViews()
        body.addView(p.build(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    companion object {
        /** A tappable row: glyph + title (+ small subtitle) + chevron. */
        fun menuRow(ctx: Context, title: String, subtitle: String?, chevron: Boolean, onClick: () -> Unit): View =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                roundBg(Theme.PANEL_TITLE, 10f, Theme.BORDER, 1f)
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(ctx.label(title, 16f))
                if (subtitle != null) texts.addView(ctx.label(subtitle, 11f, Theme.TEXT_DIM))
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (chevron) addView(ctx.label("›", 24f, Theme.ACCENT))
                isClickable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3)) }
            }

        /** Scrollable vertical column of [rows]. */
        fun scrollColumn(ctx: Context, rows: List<View>): View {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(4), 0, ctx.dp(8)) }
            rows.forEach { col.addView(it) }
            return ScrollView(ctx).apply { addView(col) }
        }
    }
}
