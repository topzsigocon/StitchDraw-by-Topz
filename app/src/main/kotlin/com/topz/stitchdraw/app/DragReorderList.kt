package com.topz.stitchdraw.app

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** One row of a [DragReorderList]. [trailing] is an optional extra control shown at the right edge. */
class DragRow(val title: String, val subtitle: String? = null, val trailing: View? = null, val onTap: (() -> Unit)? = null)

/**
 * Vertical list whose rows are reordered by dragging the ≡ handle. While dragging, the other rows slide
 * out of the way; on release [onMove] is called once with the old and the new index and the owner
 * rebuilds the list with [setRows]. The list itself never reorders its own data.
 */
class DragReorderList(context: Context, private val onMove: (from: Int, to: Int) -> Unit) : ScrollView(context) {

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rowViews = ArrayList<View>()
    private var from = -1
    private var startY = 0f
    private var startScroll = 0

    init { addView(column) }

    fun setRows(list: List<DragRow>) {
        column.removeAllViews()
        rowViews.clear()
        val gap = context.dp(3)
        list.forEachIndexed { i, r ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                roundBg(Theme.PANEL_TITLE, 10f, Theme.BORDER, 1f)
                setPadding(context.dp(2), context.dp(2), context.dp(6), context.dp(2))
            }
            val handle = context.label("≡", 24f, Theme.ACCENT).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(context.label(r.title, 15f))
            val sub = r.subtitle
            if (sub != null) texts.addView(context.label(sub, 11f, Theme.TEXT_DIM))
            val tap = r.onTap
            if (tap != null) texts.setOnClickListener { tap() }
            row.addView(handle)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val trailing = r.trailing
            if (trailing != null) row.addView(trailing)
            handle.setOnTouchListener { _, e -> drag(i, row, e); true }
            column.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(context.dp(8), gap, context.dp(8), gap) }
            )
            rowViews.add(row)
        }
    }

    private fun offset(rawY: Float): Float = rawY - startY + (scrollY - startScroll)

    private fun target(row: View, rawY: Float): Int {
        val center = row.top + row.height / 2f + offset(rawY)
        var n = 0
        for ((j, v) in rowViews.withIndex()) if (j != from && v.top + v.height / 2f < center) n++
        return n
    }

    private fun follow(row: View, rawY: Float) {
        val dy = offset(rawY)
        val to = target(row, rawY)
        val h = (row.height + context.dp(6)).toFloat()
        for ((j, v) in rowViews.withIndex()) {
            v.translationY = when {
                j == from -> dy
                from < j && j <= to -> -h
                to <= j && j < from -> h
                else -> 0f
            }
        }
    }

    private fun reset() {
        for (v in rowViews) { v.translationY = 0f; v.elevation = 0f; v.alpha = 1f }
        from = -1
    }

    private fun drag(i: Int, row: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                from = i; startY = e.rawY; startScroll = scrollY
                row.elevation = context.dp(8f).toFloat(); row.alpha = 0.88f
                requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (from < 0) return
                val loc = IntArray(2); getLocationOnScreen(loc)
                if (e.rawY < loc[1] + context.dp(56)) scrollBy(0, -context.dp(10))
                else if (e.rawY > loc[1] + height - context.dp(56)) scrollBy(0, context.dp(10))
                follow(row, e.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (from < 0) return
                val src = from
                val to = target(row, e.rawY)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                reset()
                if (up && to != src) onMove(src, to)
            }
        }
    }
}
