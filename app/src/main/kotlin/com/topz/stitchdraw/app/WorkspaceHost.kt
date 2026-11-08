package com.topz.stitchdraw.app

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.topz.stitchdraw.workspace.DockZone
import com.topz.stitchdraw.workspace.LayoutStyle
import com.topz.stitchdraw.workspace.NPoint
import com.topz.stitchdraw.workspace.PanelId
import com.topz.stitchdraw.workspace.QuickCommand
import com.topz.stitchdraw.workspace.WorkspaceAction
import com.topz.stitchdraw.workspace.WorkspaceController
import com.topz.stitchdraw.workspace.WorkspaceLayout
import kotlin.math.abs

/**
 * Lays out canvas, docked zones (rails + panels), floating panels and focus-mode controls from a
 * [WorkspaceLayout]. It renders layout state only; it never touches the digitizing session.
 */
class WorkspaceHost(
    context: Context,
    private val workspace: WorkspaceController,
    private val canvas: View,
    private val frames: Map<PanelId, PanelFrame>,
    private val onQuickCommand: (QuickCommand) -> Unit,
) : ViewGroup(context), FrameHost {

    // ---- chrome views -------------------------------------------------------------------------
    val topBarHolder = FrameLayout(context).apply { setBackgroundColor(0xFF151519.toInt()) }
    private val rails = DockZone.values().filter { it != DockZone.FLOATING }.associateWith { z -> Rail(context, z) }
    private val handles = rails.keys.associateWith { z -> ZoneHandle(context, z) }
    private val quickBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        roundBg(0xEE26262B.toInt(), 28f, Theme.BORDER, 1f)
        elevation = context.dp(10).toFloat()
    }
    /** Quick buttons floating over the canvas in the Menu layout: ☰, undo, redo, fit, full screen (user order). */
    private val menuBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        roundBg(0xEE26262B.toInt(), 28f, Theme.BORDER, 1f)
        elevation = context.dp(10).toFloat()
    }
    /** One-line status (stitches, size, objects) drawn over the bottom edge of the canvas on small layouts. */
    private val statusView = context.label("", 11f, Theme.TEXT_DIM).apply {
        maxLines = 1
        setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
        roundBg(0xAA0E0E10.toInt(), 8f)
    }
    private val focusButton = context.glyphButton("⛶", Theme.ACCENT, 48f) {}.apply { elevation = context.dp(10).toFloat() }

    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0
    private var structureKey: Any? = null
    private var floatOrder: List<PanelId> = emptyList()
    private val rects = HashMap<View, Rect>()
    private var quickEnabled: (QuickCommand) -> Boolean = { true }

    private val listener = WorkspaceController.Listener { _, _ -> refresh() }

    init {
        setBackgroundColor(Theme.BG)
        setOnApplyWindowInsetsListener { _, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetL = i.left; insetT = i.top; insetR = i.right; insetB = i.bottom
            } else {
                @Suppress("DEPRECATION") run {
                    insetL = ins.systemWindowInsetLeft; insetT = ins.systemWindowInsetTop
                    insetR = ins.systemWindowInsetRight; insetB = ins.systemWindowInsetBottom
                }
            }
            requestLayout(); ins
        }
        focusButton.setOnClickListener(null)
        attachDrag(focusButton, focusButton, onTap = { workspace.dispatch(WorkspaceAction.SetFocus(false)) }) { p ->
            workspace.dispatch(WorkspaceAction.MoveFocusButton(p))
        }
        for (z in rails.keys) handles.getValue(z).install()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); workspace.addListener(listener); structureKey = null; refresh(); requestApplyInsets() }
    override fun onDetachedFromWindow() { workspace.removeListener(listener); super.onDetachedFromWindow() }

    // ---- FrameHost ------------------------------------------------------------------------------
    override fun hostWidth() = width
    override fun hostHeight() = height
    override fun floatArea(): Rect {
        val l = workspace.layout
        return if (l.chromeVisible) Rect(insetL, insetT + topH(l), width - insetR, height - insetB) else Rect(0, 0, width, height)
    }

    /** Called by the activity when undo/redo/finish availability changes. */
    fun setQuickEnabled(f: (QuickCommand) -> Boolean) { quickEnabled = f; refreshQuickEnabled() }

    fun setStatus(text: String) { if (statusView.text.toString() != text) statusView.text = text }

    private fun smallLayout(l: WorkspaceLayout) = l.style == LayoutStyle.MENU || l.exclusiveExpand

    // ---- state -> views -------------------------------------------------------------------------

    private fun key(l: WorkspaceLayout): Any = listOf(
        l.focusMode, l.iconScale, l.showTopBar, l.quickToolbar, l.style, l.menuQuick, l.exclusiveExpand,
        l.panels.values.sortedBy { it.id }.map {
            // floating z-order is handled without re-attaching views, so floating `order` is excluded
            listOf(it.id, it.zone, it.open, it.collapsed, if (it.zone == DockZone.FLOATING) -1 else it.order)
        }
    )

    private fun refresh() {
        val l = workspace.layout
        for ((id, f) in frames) f.bind(l.panel(id), l.iconScale)
        val k = key(l)
        if (k != structureKey) { structureKey = k; rebuild(l) }
        val order = l.inZone(DockZone.FLOATING).map { it.id }
        if (order != floatOrder) { floatOrder = order; if (l.chromeVisible) order.forEach { frames.getValue(it).takeIf { f -> f.parent === this }?.bringToFront() } }
        requestLayout()
    }

    private fun rebuild(l: WorkspaceLayout) {
        removeAllViews()
        addView(canvas)
        if (l.chromeVisible) {
            if (l.showTopBar) addView(topBarHolder)
            for (z in rails.keys) {
                val docked = l.inZone(z)
                if (docked.isEmpty()) continue
                rails.getValue(z).rebuild(docked.map { it.id }, l)
                addView(rails.getValue(z))
                val expanded = docked.filter { !it.collapsed }
                expanded.forEach { addView(frames.getValue(it.id)) }
                if (expanded.isNotEmpty()) addView(handles.getValue(z))
            }
            if (smallLayout(l)) addView(statusView)           // below floating sheets so an open panel covers it
            l.inZone(DockZone.FLOATING).forEach { addView(frames.getValue(it.id)) }
            if (l.style == LayoutStyle.MENU) { rebuildMenuBar(l); addView(menuBar) }
        } else {
            rebuildQuickBar(l)
            addView(quickBar)
            addView(focusButton)
        }
    }

    private fun rebuildQuickBar(l: WorkspaceLayout) {
        quickBar.removeAllViews()
        val size = 44f * l.iconScale
        val grip = context.glyphButton("⠿", Theme.TEXT_DIM, size) {}
        grip.background = null
        quickBar.addView(grip)
        attachDrag(grip, quickBar, onTap = null) { p -> workspace.dispatch(WorkspaceAction.MoveQuickToolbar(p)) }
        for (cmd in l.quickToolbar) {
            val b = context.glyphButton(cmd.glyph, Theme.GLYPH[cmd.ordinal % Theme.GLYPH.size], size) { onQuickCommand(cmd) }
            b.tag = cmd
            quickBar.addView(b)
        }
        refreshQuickEnabled()
    }

    private fun rebuildMenuBar(l: WorkspaceLayout) {
        menuBar.removeAllViews()
        val size = 44f * l.iconScale
        val grip = context.glyphButton("⠿", Theme.TEXT_DIM, size) {}
        grip.background = null
        menuBar.addView(grip)
        attachDrag(grip, menuBar, onTap = null) { p -> workspace.dispatch(WorkspaceAction.MoveMenuBar(p)) }
        for (cmd in l.menuQuick) {
            val tint = if (cmd == QuickCommand.PANELS_MENU) Theme.WARN else Theme.GLYPH[cmd.ordinal % Theme.GLYPH.size]
            val b = context.glyphButton(cmd.glyph, tint, size) { onQuickCommand(cmd) }
            b.tag = cmd
            b.contentDescription = cmd.label
            menuBar.addView(b)
        }
        refreshQuickEnabled()
    }

    fun refreshQuickEnabled() {
        for (bar in listOf(quickBar, menuBar)) {
            for (i in 0 until bar.childCount) {
                val v = bar.getChildAt(i)
                (v.tag as? QuickCommand)?.let { v.alpha = if (quickEnabled(it)) 1f else 0.35f }
            }
        }
    }

    // ---- geometry ------------------------------------------------------------------------------

    private fun topH(l: WorkspaceLayout) = if (l.chromeVisible && l.showTopBar) context.dp(48f * l.iconScale) else 0

    private fun computeLayout(w: Int, h: Int) {
        rects.clear()
        val l = workspace.layout
        val s = l.iconScale
        val railPx = context.dp(44f * s)
        val x0 = if (l.chromeVisible) insetL else 0
        val y0 = if (l.chromeVisible) insetT else 0
        val x1 = w - (if (l.chromeVisible) insetR else 0)
        val y1 = h - (if (l.chromeVisible) insetB else 0)
        val top = topH(l)
        if (!l.chromeVisible) {
            rects[canvas] = Rect(0, 0, w, h)
            return
        }
        if (l.showTopBar) rects[topBarHolder] = Rect(x0, y0, x1, y0 + top)

        fun zoneInfo(z: DockZone): Pair<List<com.topz.stitchdraw.workspace.PanelState>, Int> {
            val docked = l.inZone(z)
            if (docked.isEmpty()) return docked to 0
            val expanded = docked.filter { !it.collapsed }
            val span = if (z == DockZone.BOTTOM) (y1 - y0 - top) else (x1 - x0)
            val content = if (expanded.isEmpty()) 0 else (l.sizeOf(z) * span).toInt().coerceAtMost((span * 0.6f).toInt())
            return docked to (railPx + content)
        }
        val (leftP, leftTotal) = zoneInfo(DockZone.LEFT)
        val (rightP, rightTotal) = zoneInfo(DockZone.RIGHT)
        val (botP, botTotal) = zoneInfo(DockZone.BOTTOM)
        val cy0 = y0 + top
        // Small layouts (exclusiveExpand): an expanded panel floats OVER the canvas, so the canvas only gives up the rails.
        fun share(total: Int) = if (l.exclusiveExpand) minOf(total, railPx) else total
        val cx0 = x0 + share(leftTotal); val cx1 = x1 - share(rightTotal); val cy1 = y1 - share(botTotal)
        val sideBottom = if (l.exclusiveExpand && botP.isNotEmpty()) y1 - railPx else y1
        rects[canvas] = Rect(cx0, cy0, cx1, cy1)
        if (smallLayout(l)) {
            val sh = context.dp(22)
            rects[statusView] = Rect(cx0 + context.dp(4), cy1 - sh - context.dp(4), cx1 - context.dp(4), cy1 - context.dp(4))
        }

        // LEFT: rail at the outer edge, content toward the canvas
        if (leftP.isNotEmpty()) {
            rects[rails.getValue(DockZone.LEFT)] = Rect(x0, cy0, x0 + railPx, y1)
            stack(leftP.filter { !it.collapsed }.map { it.id }, Rect(x0 + railPx, cy0, x0 + leftTotal, sideBottom), vertical = true)
            if (leftTotal > railPx) rects[handles.getValue(DockZone.LEFT)] = Rect(x0 + leftTotal - context.dp(10), cy0, x0 + leftTotal + context.dp(10), y1)
        }
        if (rightP.isNotEmpty()) {
            rects[rails.getValue(DockZone.RIGHT)] = Rect(x1 - railPx, cy0, x1, y1)
            stack(rightP.filter { !it.collapsed }.map { it.id }, Rect(x1 - rightTotal, cy0, x1 - railPx, sideBottom), vertical = true)
            if (rightTotal > railPx) rects[handles.getValue(DockZone.RIGHT)] = Rect(x1 - rightTotal - context.dp(10), cy0, x1 - rightTotal + context.dp(10), y1)
        }
        if (botP.isNotEmpty()) {
            rects[rails.getValue(DockZone.BOTTOM)] = Rect(cx0, y1 - railPx, cx1, y1)
            stack(botP.filter { !it.collapsed }.map { it.id }, Rect(cx0, y1 - botTotal, cx1, y1 - railPx), vertical = false)
            if (botTotal > railPx) rects[handles.getValue(DockZone.BOTTOM)] = Rect(cx0, y1 - botTotal - context.dp(10), cx1, y1 - botTotal + context.dp(10))
        }

        // FLOATING
        val area = Rect(x0, cy0, x1, y1)
        for (p in l.inZone(DockZone.FLOATING)) {
            val titleH = context.dp(40f * s)
            val r = when {
                p.maximized -> Rect(area)
                else -> {
                    val fl = p.floatRect
                    val rl = area.left + (fl.x * area.width()).toInt(); val rt = area.top + (fl.y * area.height()).toInt()
                    val rw = (fl.w * area.width()).toInt(); val rh = if (p.minimized) titleH else (fl.h * area.height()).toInt()
                    Rect(rl, rt, rl + rw, rt + rh)
                }
            }
            rects[frames.getValue(p.id)] = r
        }
    }

    private fun stack(ids: List<PanelId>, area: Rect, vertical: Boolean) {
        if (ids.isEmpty()) return
        val n = ids.size
        ids.forEachIndexed { i, id ->
            rects[frames.getValue(id)] = if (vertical) {
                val a = area.top + area.height() * i / n; val b = area.top + area.height() * (i + 1) / n
                Rect(area.left, a, area.right, b)
            } else {
                val a = area.left + area.width() * i / n; val b = area.left + area.width() * (i + 1) / n
                Rect(a, area.top, b, area.bottom)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeLayout(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val r = rects[c]
            if (r != null) {
                c.measure(MeasureSpec.makeMeasureSpec(r.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(r.height().coerceAtLeast(0), MeasureSpec.EXACTLY))
            } else c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val lay = workspace.layout
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.translationX = 0f; c.translationY = 0f
            val rc = rects[c]
            if (rc != null) { c.layout(rc.left, rc.top, rc.right, rc.bottom); continue }
            // free-floating controls (focus mode): placed by normalized centre position
            val pos: NPoint = when (c) {
                quickBar -> lay.quickToolbarPos
                menuBar -> lay.menuBarPos
                focusButton -> lay.focusButtonPos
                else -> NPoint(0.5f, 0.5f)
            }
            val cw = c.measuredWidth; val ch = c.measuredHeight
            val cx = (pos.x * (r - l)).toInt().coerceIn(cw / 2, (r - l) - cw / 2)
            val cy = (pos.y * (b - t)).toInt().coerceIn(ch / 2, (b - t) - ch / 2)
            c.layout(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        }
    }

    // ---- dragging of free controls ---------------------------------------------------------------

    private fun attachDrag(handle: View, target: View, onTap: (() -> Unit)?, commit: (NPoint) -> Unit) {
        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var moved = false
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moved = false; v.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    dx = e.rawX - sx; dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > context.dp(8)) moved = true
                    if (moved) { target.translationX = dx; target.translationY = dy }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    else {
                        val cx = target.left + target.width / 2f + dx
                        val cy = target.top + target.height / 2f + dy
                        commit(NPoint(cx / width.coerceAtLeast(1), cy / height.coerceAtLeast(1)))
                    }
                }
                MotionEvent.ACTION_CANCEL -> { target.translationX = 0f; target.translationY = 0f }
            }
            true
        }
    }

    // ---- rails (small icon tabs; swipe to collapse/expand the whole zone) --------------------------

    private inner class Rail(ctx: Context, val zone: DockZone) : LinearLayout(ctx) {
        private var downX = 0f; private var downY = 0f; private var swiped = false
        init {
            orientation = if (zone == DockZone.BOTTOM) HORIZONTAL else VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF121215.toInt())
        }

        fun rebuild(ids: List<PanelId>, l: WorkspaceLayout) {
            removeAllViews()
            val size = 40f * l.iconScale
            ids.forEachIndexed { i, id ->
                val p = l.panel(id)
                val b = context.glyphButton(id.iconGlyph, Theme.GLYPH[id.ordinal % Theme.GLYPH.size], size) {
                    workspace.dispatch(WorkspaceAction.ToggleCollapsed(id))
                }
                // expanded tabs get an accent outline; collapsed tabs are plain icons
                if (!p.collapsed) b.roundBg(0xFF30343C.toInt(), 8f, Theme.ACCENT, 1.5f)
                (b.layoutParams as LinearLayout.LayoutParams).setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                b.contentDescription = id.title
                addView(b)
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiped = false }
                MotionEvent.ACTION_MOVE -> {
                    val d = along(e)
                    if (abs(d) > context.dp(24) && abs(d) > abs(across(e))) { swiped = true; return true }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && swiped) {
                val d = along(e)
                if (abs(d) > context.dp(40)) {
                    // LEFT: swipe right opens; RIGHT: swipe left opens; BOTTOM: swipe up opens
                    val opens = when (zone) { DockZone.LEFT -> d > 0; DockZone.RIGHT, DockZone.BOTTOM -> d < 0; else -> false }
                    workspace.dispatch(WorkspaceAction.SetZoneCollapsed(zone, !opens))
                }
                swiped = false
            }
            return true
        }

        private fun along(e: MotionEvent) = if (zone == DockZone.BOTTOM) e.rawY - downY else e.rawX - downX
        private fun across(e: MotionEvent) = if (zone == DockZone.BOTTOM) e.rawX - downX else e.rawY - downY
    }

    // ---- zone resize handle ----------------------------------------------------------------------

    private inner class ZoneHandle(ctx: Context, val zone: DockZone) : View(ctx) {
        init { setBackgroundColor(0x00000000) }
        private val gripPaint = android.graphics.Paint().apply { color = Theme.ACCENT; alpha = 150 }
        override fun onDraw(c: android.graphics.Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (zone == DockZone.BOTTOM) c.drawRoundRect(cx - 24f, cy - 3f, cx + 24f, cy + 3f, 3f, 3f, gripPaint)
            else c.drawRoundRect(cx - 3f, cy - 24f, cx + 3f, cy + 24f, 3f, 3f, gripPaint)
        }
        fun install() {
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val l = workspace.layout
                    val railPx = context.dp(44f * l.iconScale)
                    val loc = IntArray(2); this@WorkspaceHost.getLocationOnScreen(loc)
                    val x = e.rawX - loc[0]; val y = e.rawY - loc[1]
                    val frac = when (zone) {
                        DockZone.LEFT -> (x - insetL - railPx) / (width - insetL - insetR)
                        DockZone.RIGHT -> (width - insetR - x - railPx) / (width - insetL - insetR)
                        else -> (height - insetB - y - railPx) / (height - insetT - insetB - topH(l))
                    }
                    workspace.dispatch(WorkspaceAction.ResizeZone(zone, frac))
                }
                true
            }
        }
    }
}
