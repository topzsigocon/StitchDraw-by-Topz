package com.topz.stitchdraw.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.digitizing.DigitizingTool
import com.topz.stitchdraw.embroidery.Hoop
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.workspace.ContextMenus
import com.topz.stitchdraw.workspace.PanelId
import com.topz.stitchdraw.workspace.ToolMenuKind
import kotlin.math.roundToInt

/** A panel's body plus a refresh hook invoked whenever the digitizing session changes. */
class PanelContent(val view: View, val refresh: () -> Unit)

/** Everything panels may use. Panels talk to the session and canvas only; never to the workspace layout. */
class StudioEnv(
    val ctx: Context,
    val session: DigitizingSession,
    val canvas: DesignCanvasView,
    val showToolMenu: (anchor: View, kind: ToolMenuKind) -> Unit,
    val refreshAll: () -> Unit,
)

object PanelContents {

    fun build(id: PanelId, env: StudioEnv): PanelContent = when (id) {
        PanelId.DRAWING_TOOLS -> viewTools(env)
        PanelId.DIGITIZING_TOOLS -> digitizingTools(env)
        PanelId.SELECTION_TOOLS -> selectionTools(env)
        PanelId.EDITING_TOOLS -> editingTools(env)
        PanelId.OBJECT_MANAGER -> objectManager(env)
        PanelId.LAYER_MANAGER -> layerManager(env)
        PanelId.THREAD_COLORS -> threadColors(env)
        PanelId.STITCH_PROPERTIES -> stitchProperties(env)
        PanelId.OBJECT_PROPERTIES -> objectProperties(env)
        PanelId.STITCH_INFO -> stitchInfo(env)
        PanelId.DESIGN_MEASUREMENTS -> measurements(env)
        PanelId.MACHINE_INFO -> machineInfo(env)
        PanelId.STITCH_SEQUENCE -> stitchSequence(env)
    }

    // ---- small helpers --------------------------------------------------------------------------

    private fun scroller(ctx: Context, body: LinearLayout): View =
        ScrollView(ctx).apply { isFillViewport = false; addView(body) }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
    }

    private fun wrapRow(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    private fun LinearLayout.addGap() = addView(View(context), LinearLayout.LayoutParams(1, context.dp(6)))

    private fun kv(ctx: Context, k: String, v: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ctx.dp(3), 0, ctx.dp(3))
        addView(ctx.label(k, 12f, Theme.TEXT_DIM), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ctx.label(v, 13f, Theme.TEXT, bold = true))
    }

    private fun btn(ctx: Context, text: String, tint: Int, enabled: Boolean = true, onClick: () -> Unit): View =
        ctx.textButton(text, if (enabled) tint else Theme.TEXT_DIM) { if (enabled) onClick() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = ctx.dp(6) }
        }

    private fun simple(env: StudioEnv, render: LinearLayout.() -> Unit): PanelContent {
        val body = column(env.ctx)
        return PanelContent(scroller(env.ctx, body)) { body.removeAllViews(); body.render() }
            .also { body.render() }
    }

    // ---- tool panels ------------------------------------------------------------------------------

    private fun viewTools(env: StudioEnv) = simple(env) {
        val c = context
        addView(c.label("Canvas & view", 12f, Theme.TEXT_DIM)); addGap()
        addView(btn(c, "Zoom in", Theme.ACCENT) { env.canvas.zoomBy(1.4f) })
        addView(btn(c, "Zoom out", Theme.ACCENT) { env.canvas.zoomBy(1 / 1.4f) })
        addView(btn(c, "Fit design", Theme.OK) { env.canvas.fitDesign() })
        addView(btn(c, (if (env.canvas.showGrid) "Hide" else "Show") + " grid", Theme.WARN) { env.canvas.showGrid = !env.canvas.showGrid; env.canvas.invalidate(); env.refreshAll() })
        addView(btn(c, (if (env.canvas.showRulers) "Hide" else "Show") + " rulers", Theme.WARN) { env.canvas.showRulers = !env.canvas.showRulers; env.canvas.invalidate(); env.refreshAll() })
        addView(btn(c, (if (env.canvas.showHoop) "Hide" else "Show") + " hoop", Theme.WARN) { env.canvas.showHoop = !env.canvas.showHoop; env.canvas.invalidate(); env.refreshAll() })
    }

    private fun toolButton(env: StudioEnv, parent: LinearLayout, t: DigitizingTool, kind: ToolMenuKind, tint: Int) {
        val c = env.ctx
        val active = env.session.tool == t
        val b = c.textButton((if (active) "● " else "") + t.label, tint) {}
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = c.dp(6) }
        if (active) b.roundBg(0xFF30343C.toInt(), 8f, tint, 2f)
        // First tap selects the tool; tapping the active tool again opens its settings menu.
        b.setOnClickListener { if (env.session.tool == t && t.isDrawing) env.showToolMenu(b, kind) else env.session.selectTool(t) }
        parent.addView(b)
    }

    private fun digitizingTools(env: StudioEnv) = simple(env) {
        val c = context
        addView(c.label("Tap a tool, tap again for its settings", 11f, Theme.TEXT_DIM)); addGap()
        toolButton(env, this, DigitizingTool.RUNNING, ToolMenuKind.RUNNING, Theme.ACCENT)
        toolButton(env, this, DigitizingTool.TRIPLE_RUNNING, ToolMenuKind.TRIPLE_RUNNING, Theme.PURPLE)
        toolButton(env, this, DigitizingTool.SATIN, ToolMenuKind.SATIN, Theme.WARN)
        toolButton(env, this, DigitizingTool.ZIGZAG, ToolMenuKind.ZIGZAG, 0xFF26C6DA.toInt())
        toolButton(env, this, DigitizingTool.TATAMI, ToolMenuKind.TATAMI, Theme.OK)
        toolButton(env, this, DigitizingTool.APPLIQUE, ToolMenuKind.APPLIQUE, Theme.DANGER)
        addGap()
        addView(Switch(c).apply {
            text = "Smooth curve (Pen)"; isChecked = env.session.curveMode; setTextColor(Theme.TEXT)
            setOnCheckedChangeListener { _, v -> env.session.curveMode = v }
        })
        addGap()
        val n = env.session.pending.size
        addView(c.label(if (env.session.tool.isDrawing) "Points: $n  (tap the canvas to add)" else "Choose a stitch tool", 12f, Theme.TEXT))
        addGap()
        addView(btn(c, "✔ Finish object", Theme.OK, env.session.canFinish) { env.session.finishObject() })
        addView(btn(c, "✖ Cancel", Theme.DANGER, n > 0) { env.session.cancelPending() })
    }

    private fun selectionTools(env: StudioEnv) = simple(env) {
        val c = context
        toolButton(env, this, DigitizingTool.SELECT, ToolMenuKind.GENERIC, Theme.OK)
        val sel = env.session.selectedObjectId
        addView(c.label(if (sel == null) "Tap an object on the canvas" else "Selected: ${env.session.design.obj(sel)?.name ?: ""}", 12f, Theme.TEXT))
        addGap()
        addView(btn(c, "Deselect", Theme.TEXT_DIM, sel != null) { env.session.select(null) })
    }

    private fun editingTools(env: StudioEnv) = simple(env) {
        val c = context
        val s = env.session
        val sel = s.selectedObjectId
        addView(btn(c, "↶ Undo", Theme.ACCENT, s.canUndo) { s.undo() })
        addView(btn(c, "↷ Redo", Theme.ACCENT, s.canRedo) { s.redo() })
        addView(btn(c, "⧉ Duplicate", Theme.OK, sel != null) { s.duplicateObject(sel!!) })
        addView(btn(c, "▲ Bring forward", Theme.WARN, sel != null) { s.moveObject(sel!!, +1) })
        addView(btn(c, "▼ Send backward", Theme.WARN, sel != null) { s.moveObject(sel!!, -1) })
        addView(btn(c, "🗑 Delete", Theme.DANGER, sel != null) { s.deleteObject(sel!!) })
    }

    // ---- managers -----------------------------------------------------------------------------------

    private fun objectManager(env: StudioEnv) = simple(env) {
        val c = context
        val d = env.session.design
        if (d.objects.isEmpty()) addView(c.label("No objects yet.\nPick a stitch tool and tap the canvas.", 12f, Theme.TEXT_DIM))
        d.objects.asReversed().forEach { o ->
            val selected = env.session.selectedObjectId == o.id
            val row = wrapRow(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
                roundBg(if (selected) 0xFF2E3A44.toInt() else Theme.PANEL_TITLE, 8f, if (selected) Theme.ACCENT else 0, if (selected) 1.5f else 0f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(5) }
                addView(c.swatch(d.threads.getOrNull(o.threadIndex)?.rgb ?: 0xFFFFFF))
                addView(c.label(o.name, 13f), LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { env.session.select(o.id) }
            }
            addView(row)
        }
    }

    private fun layerManager(env: StudioEnv) = simple(env) {
        val c = context
        val d = env.session.design
        d.layers.forEach { l ->
            val count = d.objects.count { it.layerId == l.id }
            val row = wrapRow(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(8), c.dp(4), c.dp(4), c.dp(4))
                roundBg(Theme.PANEL_TITLE, 8f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(5) }
                addView(c.label("${l.name}  ($count)", 13f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(c.glyphButton(if (l.visible) "👁" else "–", Theme.ACCENT, 38f) { env.session.toggleLayerVisible(l.id) })
                addView(c.glyphButton(if (l.locked) "🔒" else "🔓", Theme.WARN, 38f) { env.session.toggleLayerLocked(l.id) })
            }
            addView(row)
        }
        addGap()
        addView(btn(c, "+ Add layer", Theme.OK) { promptText(c, "Layer name", "Layer ${d.layers.size + 1}") { env.session.addLayer(it.ifBlank { "Layer" }) } })
    }

    private fun threadColors(env: StudioEnv) = simple(env) {
        val c = context
        val s = env.session
        addView(c.label("Tap = active thread · long-press = edit", 11f, Theme.TEXT_DIM)); addGap()
        s.design.threads.forEachIndexed { i, t ->
            val active = i == s.activeThread
            val row = wrapRow(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
                roundBg(if (active) 0xFF2E3A44.toInt() else Theme.PANEL_TITLE, 8f, if (active) Theme.ACCENT else 0, if (active) 1.5f else 0f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(5) }
                addView(c.swatch(t.rgb))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(c.label("${i + 1}. ${t.name}", 13f))
                    addView(c.label("${t.hex}  RGB ${t.red},${t.green},${t.blue}", 10f, Theme.TEXT_DIM))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { s.setActiveThread(i) }
                setOnLongClickListener { promptColor(c, t) { nt -> s.replaceThread(i, nt) }; true }
            }
            addView(row)
        }
        addGap()
        addView(btn(c, "+ Add color (RGB / HEX)", Theme.OK) { promptColor(c, null) { nt -> val idx = s.addThread(nt); s.setActiveThread(idx) } })
        val sel = s.selectedObjectId
        addView(btn(c, "Apply to selected object", Theme.ACCENT, sel != null) { s.setObjectThread(sel!!, s.activeThread) })
    }

    // ---- properties ---------------------------------------------------------------------------------

    private fun stitchProperties(env: StudioEnv) = simple(env) {
        val c = context
        val s = env.session
        val sel = s.selectedObjectId?.let { s.design.obj(it) }
        val p = sel?.params ?: s.params
        addView(c.label(if (sel != null) "Editing: ${sel.name}" else "Defaults for new objects", 12f, Theme.ACCENT)); addGap()
        val lenLabel = c.label("Stitch length: %.1f mm".format(p.stitchLengthMm), 13f)
        addView(lenLabel)
        val bar = SeekBar(c).apply {
            max = 55 // 0.5 .. 6.0 mm in 0.1 steps
            progress = ((p.stitchLengthMm.coerceIn(0.5f, 6f) - 0.5f) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) lenLabel.text = "Stitch length: %.1f mm".format(0.5f + v / 10f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val mm = 0.5f + (sb?.progress ?: 0) / 10f
                    if (sel != null) s.setObjectParams(sel.id, sel.params.copy(stitchLengthMm = mm)) else s.setParams(s.params.copy(stitchLengthMm = mm))
                }
            })
        }
        addView(bar)
        addGap()
        fun sw(text: String, on: Boolean, set: (Boolean) -> Unit) = Switch(c).apply {
            this.text = text; isChecked = on; setTextColor(Theme.TEXT)
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        // ---- type-specific controls: only shown for the types that use them ----
        val type = sel?.type ?: s.tool.stitchType
        fun commitParams(np: com.topz.stitchdraw.embroidery.StitchParams) {
            if (sel != null) s.setObjectParams(sel.id, np) else s.setParams(np)
        }
        fun slider(title: String, unit: String, min: Float, max: Float, step: Float, value: Float, commit: (Float) -> Unit) {
            val steps = Math.round((max - min) / step)
            val label = c.label("$title: %.2f $unit".format(value), 13f)
            addView(label)
            addView(SeekBar(c).apply {
                this.max = steps
                progress = Math.round((value.coerceIn(min, max) - min) / step)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (fromUser) label.text = "$title: %.2f $unit".format(min + v * step)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { commit(min + (sb?.progress ?: 0) * step) }
                })
            })
        }
        if (type == StitchType.SATIN || type == StitchType.ZIGZAG || type == StitchType.TATAMI || type == StitchType.APPLIQUE) {
            addGap()
            slider("Density (spacing)", "mm", 0.2f, 1.5f, 0.05f, p.densityMm) { commitParams(p.copy(densityMm = it)) }
        }
        if (type == StitchType.SATIN || type == StitchType.ZIGZAG || type == StitchType.APPLIQUE) {
            slider("Column width", "mm", 0.5f, 12f, 0.1f, p.widthMm) { commitParams(p.copy(widthMm = it)) }
        }
        if (type == StitchType.TATAMI) {
            slider("Stitch angle", "°", 0f, 175f, 5f, p.angleDeg % 180f) { commitParams(p.copy(angleDeg = it)) }
        }
        if (type == StitchType.SATIN || type == StitchType.TATAMI || type == StitchType.APPLIQUE) {
            slider("Pull compensation", "mm", 0f, 1.0f, 0.05f, p.pullCompMm) { commitParams(p.copy(pullCompMm = it)) }
            addView(Switch(c).apply {
                text = "Underlay"; isChecked = p.underlay; setTextColor(Theme.TEXT)
                setOnCheckedChangeListener { _, v -> commitParams(p.copy(underlay = v)) }
            })
        }
        addGap()
        addView(sw("Tie-in (lock stitches at start)", p.tieIn) { v ->
            if (sel != null) s.setObjectParams(sel.id, sel.params.copy(tieIn = v)) else s.setParams(s.params.copy(tieIn = v)) })
        addView(sw("Tie-off (lock stitches at end)", p.tieOut) { v ->
            if (sel != null) s.setObjectParams(sel.id, sel.params.copy(tieOut = v)) else s.setParams(s.params.copy(tieOut = v)) })
    }

    private fun objectProperties(env: StudioEnv) = simple(env) {
        val c = context
        val s = env.session
        val o = s.selectedObjectId?.let { s.design.obj(it) }
        if (o == null) { addView(c.label("Select an object to see its properties.", 12f, Theme.TEXT_DIM)); return@simple }
        val thread = s.design.threads.getOrNull(o.threadIndex)
        addView(kv(c, "Name", o.name))
        addView(kv(c, "Stitch type", o.type.label))
        addView(kv(c, "Points", o.path.size.toString()))
        addView(kv(c, "Path length", "%.1f mm".format(com.topz.stitchdraw.vector.Polyline.length(o.path))))
        addView(kv(c, "Thread", thread?.let { "${it.name} ${it.hex}" } ?: "-"))
        addView(kv(c, "Layer", s.design.layers.firstOrNull { it.id == o.layerId }?.name ?: "-"))
        addGap()
        StitchType.values().forEach { t ->
            addView(btn(c, (if (t == o.type) "● " else "") + t.label, Theme.ACCENT, t != o.type) { s.changeStitchType(o.id, t) })
        }
    }

    // ---- information panels -----------------------------------------------------------------------

    private fun stitchInfo(env: StudioEnv) = simple(env) {
        val c = context
        val st = env.session.plan.stats
        addView(kv(c, "Stitches", st.stitchCount.toString()))
        addView(kv(c, "Color changes", st.colorChanges.toString()))
        addView(kv(c, "Jumps", st.jumpCount.toString()))
        addView(kv(c, "Trims", st.trimCount.toString()))
        addView(kv(c, "Thread length", "%.2f m".format(st.threadLengthMm / 1000f)))
        addView(kv(c, "Objects", env.session.design.objects.size.toString()))
    }

    private fun measurements(env: StudioEnv) = simple(env) {
        val c = context
        val b = env.session.plan.stats.bounds
        val hoop = env.session.design.hoop
        addView(kv(c, "Width", "%.1f mm (%.2f in)".format(b.width, b.width / 25.4f)))
        addView(kv(c, "Height", "%.1f mm (%.2f in)".format(b.height, b.height / 25.4f)))
        val fits = b.isEmpty || (b.minX >= -hoop.widthMm / 2 && b.maxX <= hoop.widthMm / 2 && b.minY >= -hoop.heightMm / 2 && b.maxY <= hoop.heightMm / 2)
        addView(kv(c, "Hoop", hoop.name))
        addView(c.label(if (fits) "✓ Design fits the hoop" else "⚠ Design exceeds the hoop", 13f, if (fits) Theme.OK else Theme.DANGER))
    }

    private fun machineInfo(env: StudioEnv) = simple(env) {
        val c = context
        addView(kv(c, "Resolution", "0.1 mm / unit"))
        addView(kv(c, "Max stitch", "12.1 mm"))
        addView(kv(c, "Trim above", "3.0 mm jump"))
        addView(kv(c, "Lock stitch", "0.4 mm"))
        addGap()
        addView(c.label("Hoop size", 12f, Theme.TEXT_DIM))
        Hoop.ALL.forEach { h ->
            addView(btn(c, (if (h == env.session.design.hoop) "● " else "") + h.name, Theme.WARN, h != env.session.design.hoop) {
                env.session.setHoop(h); env.canvas.fitDesign()
            })
        }
    }

    private fun stitchSequence(env: StudioEnv) = simple(env) {
        val c = context
        val d = env.session.design
        val counts = env.session.plan.stitchCountsInOrder()
        if (counts.isEmpty()) { addView(c.label("No stitches yet.", 12f, Theme.TEXT_DIM)); return@simple }
        var prevThread = -1
        counts.forEachIndexed { i, (objId, n) ->
            val o = d.obj(objId) ?: return@forEachIndexed
            if (prevThread != -1 && o.threadIndex != prevThread) addView(c.label("— color change —", 11f, Theme.WARN))
            prevThread = o.threadIndex
            addView(wrapRow(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(6), c.dp(5), c.dp(6), c.dp(5))
                addView(c.swatch(d.threads.getOrNull(o.threadIndex)?.rgb ?: 0xFFFFFF, 16))
                addView(c.label("${i + 1}. ${o.name}", 12f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(c.label("$n st", 12f, Theme.TEXT_DIM))
            })
        }
        addView(c.label("— end —", 11f, Theme.TEXT_DIM))
    }
}
