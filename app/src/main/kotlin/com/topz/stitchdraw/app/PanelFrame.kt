package com.topz.stitchdraw.app

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.topz.stitchdraw.workspace.DockZone
import com.topz.stitchdraw.workspace.NRect
import com.topz.stitchdraw.workspace.PanelId
import com.topz.stitchdraw.workspace.PanelState
import com.topz.stitchdraw.workspace.WorkspaceAction
import com.topz.stitchdraw.workspace.WorkspaceController
import kotlin.math.abs
import kotlin.math.hypot

/** What a frame needs to know about the host's geometry. */
interface FrameHost {
    /** Area (in host pixels) in which floating panels live. */
    fun floatArea(): Rect
    fun hostWidth(): Int
    fun hostHeight(): Int
}

/**
 * Window chrome around one panel's content: title bar (drag, pin, collapse/minimize, maximize,
 * dock menu, close) and a resize grip when floating. Talks only to [WorkspaceController].
 */
class PanelFrame(
    context: Context,
    val id: PanelId,
    private val content: View,
    private val workspace: WorkspaceController,
    private val host: FrameHost,
) : LinearLayout(context) {

    private val titleBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val titleText = context.label("${id.iconGlyph}  ${id.title}", 13f, Theme.TEXT, bold = true).apply {
        setPadding(context.dp(10), 0, context.dp(4), 0)
        maxLines = 1
    }
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val footer = FrameLayout(context)
    private val grip = context.label("◢", 16f, Theme.TEXT_DIM).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(10), context.dp(2), context.dp(4), context.dp(2))
    }

    private var state: PanelState? = null
    private var iconScale = 1f

    init {
        orientation = VERTICAL
        roundBg(Theme.PANEL, 10f, Theme.BORDER, 1f)
        clipToOutline = true
        elevation = context.dp(6).toFloat()
        titleBar.roundBg(Theme.PANEL_TITLE, 10f)
        titleBar.addView(titleText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(buttons)
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        footer.addView(grip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        installTitleDrag()
        installResizeGrip()
    }

    /** Re-applies the state; cheap, called on every layout change. */
    fun bind(s: PanelState, iconScale: Float) {
        val sameButtons = state?.let { it.zone == s.zone && it.pinned == s.pinned && it.maximized == s.maximized && it.minimized == s.minimized } == true &&
            this.iconScale == iconScale
        state = s
        this.iconScale = iconScale
        val floating = s.zone == DockZone.FLOATING
        content.visibility = if (floating && s.minimized) GONE else VISIBLE
        footer.visibility = if (floating && !s.minimized && !s.maximized && !s.pinned) VISIBLE else GONE
        if (!sameButtons) rebuildButtons(s)
    }

    private fun rebuildButtons(s: PanelState) {
        buttons.removeAllViews()
        val c = context
        val size = 38f * iconScale
        fun add(glyph: String, tint: Int, active: Boolean = false, onClick: () -> Unit) {
            val b = c.glyphButton(glyph, tint, size, onClick)
            b.background = null
            if (active) b.roundBg(0x3329B6F6, 8f)
            buttons.addView(b)
        }
        add("📌", Theme.WARN, s.pinned) { workspace.dispatch(WorkspaceAction.SetPinned(id, !s.pinned)) }
        if (s.zone == DockZone.FLOATING) {
            add(if (s.minimized) "▢" else "–", Theme.TEXT) { workspace.dispatch(WorkspaceAction.SetMinimized(id, !s.minimized)) }
            add(if (s.maximized) "❐" else "□", Theme.OK) { workspace.dispatch(WorkspaceAction.SetMaximized(id, !s.maximized)) }
        } else {
            add("–", Theme.TEXT) { workspace.dispatch(WorkspaceAction.SetCollapsed(id, true)) }
        }
        add("⋮", Theme.ACCENT) { showMenu(buttons) }
        add("✕", Theme.DANGER) { workspace.dispatch(WorkspaceAction.Close(id)) }
    }

    /** Dock / undock / reorder menu. */
    private fun showMenu(anchor: View) {
        val s = state ?: return
        val m = PopupMenu(context, anchor)
        if (s.zone != DockZone.LEFT) m.menu.add(0, 1, 0, "Dock left")
        if (s.zone != DockZone.RIGHT) m.menu.add(0, 2, 1, "Dock right")
        if (s.zone != DockZone.BOTTOM) m.menu.add(0, 3, 2, "Dock bottom")
        if (s.zone != DockZone.FLOATING) {
            m.menu.add(0, 4, 3, "Float (undock)")
            m.menu.add(0, 5, 4, "Move up / earlier")
            m.menu.add(0, 6, 5, "Move down / later")
        }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> workspace.dispatch(WorkspaceAction.Dock(id, DockZone.LEFT))
                2 -> workspace.dispatch(WorkspaceAction.Dock(id, DockZone.RIGHT))
                3 -> workspace.dispatch(WorkspaceAction.Dock(id, DockZone.BOTTOM))
                4 -> workspace.dispatch(WorkspaceAction.Undock(id, floatRectNear()))
                5 -> workspace.dispatch(WorkspaceAction.Reorder(id, s.order - 1))
                6 -> workspace.dispatch(WorkspaceAction.Reorder(id, s.order + 1))
            }
            true
        }
        m.show()
    }

    private fun floatRectNear(): NRect {
        val a = host.floatArea()
        val loc = IntArray(2); getLocationOnScreen(loc)
        val hostLoc = IntArray(2); (parent as? View)?.getLocationOnScreen(hostLoc)
        val w = a.width().coerceAtLeast(1); val h = a.height().coerceAtLeast(1)
        return NRect((loc[0] - hostLoc[0] - a.left).toFloat() / w, (loc[1] - hostLoc[1] - a.top).toFloat() / h, 0.30f, 0.45f).clamped()
    }

    // ---- drag by title (floating) -----------------------------------------------------------

    private fun installTitleDrag() {
        var downX = 0f; var downY = 0f; var moved = false
        titleBar.setOnTouchListener { _, e ->
            val s = state ?: return@setOnTouchListener false
            if (s.zone != DockZone.FLOATING) {
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawX - downX) < 10) {
                    workspace.dispatch(WorkspaceAction.ToggleCollapsed(id))
                }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.rawX; downY = e.rawY }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (!s.pinned && !s.maximized) {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (hypot(dx, dy) > context.dp(6)) moved = true
                        if (moved) { translationX = dx; translationY = dy }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { workspace.dispatch(WorkspaceAction.Raise(id)) }
                    else if (e.actionMasked == MotionEvent.ACTION_UP) commitMove(e.rawX, e.rawY, s)
                    translationX = 0f; translationY = 0f
                }
            }
            true
        }
    }

    private fun commitMove(rawX: Float, rawY: Float, s: PanelState) {
        val hostLoc = IntArray(2); (parent as? View)?.getLocationOnScreen(hostLoc)
        val px = rawX - hostLoc[0]; val py = rawY - hostLoc[1]
        val w = host.hostWidth(); val h = host.hostHeight()
        val edge = 0.06f
        // Drop near a screen edge = dock there.
        when {
            px < w * edge -> { workspace.dispatch(WorkspaceAction.Dock(id, DockZone.LEFT)); return }
            px > w * (1 - edge) -> { workspace.dispatch(WorkspaceAction.Dock(id, DockZone.RIGHT)); return }
            py > h * (1 - edge) -> { workspace.dispatch(WorkspaceAction.Dock(id, DockZone.BOTTOM)); return }
        }
        val a = host.floatArea()
        val aw = a.width().coerceAtLeast(1).toFloat(); val ah = a.height().coerceAtLeast(1).toFloat()
        val nx = (left + translationX - a.left) / aw
        val ny = (top + translationY - a.top) / ah
        workspace.dispatch(WorkspaceAction.SetFloatRect(id, s.floatRect.copy(x = nx, y = ny).clamped()))
    }

    // ---- resize grip (floating) -----------------------------------------------------------------

    private fun installResizeGrip() {
        var startW = 0f; var startH = 0f; var downX = 0f; var downY = 0f
        grip.setOnTouchListener { v, e ->
            val s = state ?: return@setOnTouchListener false
            if (s.pinned || s.maximized) return@setOnTouchListener false
            val a = host.floatArea()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startW = s.floatRect.w; startH = s.floatRect.h
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = startW + (e.rawX - downX) / a.width().coerceAtLeast(1)
                    val h = startH + (e.rawY - downY) / a.height().coerceAtLeast(1)
                    workspace.dispatch(WorkspaceAction.SetFloatRect(id, s.floatRect.copy(w = w, h = h)))
                }
            }
            true
        }
    }
}
