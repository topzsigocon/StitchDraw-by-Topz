package com.topz.stitchdraw.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.digitizing.DigitizingTool
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.workspace.CanvasViewState
import com.topz.stitchdraw.workspace.ContextMenus
import com.topz.stitchdraw.workspace.MenuCommand
import com.topz.stitchdraw.workspace.PanelId
import com.topz.stitchdraw.workspace.QuickCommand
import com.topz.stitchdraw.workspace.ToolMenuKind
import com.topz.stitchdraw.workspace.UserMode
import com.topz.stitchdraw.workspace.WorkspaceAction
import com.topz.stitchdraw.workspace.WorkspaceController
import com.topz.stitchdraw.workspace.WorkspaceLayout
import com.topz.stitchdraw.workspace.WorkspacePresets
import com.topz.stitchdraw.workspace.WorkspaceStorage
import android.content.Intent
import com.topz.stitchdraw.files.StdFormatException
import java.io.File

/** Workspace layouts live in app-internal storage; written atomically so a crash never corrupts them. */
class FileWorkspaceStorage(private val file: File) : WorkspaceStorage {
    override fun read(): String? = if (file.exists()) file.readText() else null
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }
}

class MainActivity : Activity() {

    private lateinit var session: DigitizingSession
    private lateinit var workspace: WorkspaceController
    private lateinit var storage: FileWorkspaceStorage
    private lateinit var projectIO: ProjectIO
    private lateinit var imageImport: ImageImport
    private lateinit var textTool: TextTool
    private lateinit var transformDialog: TransformDialog
    private lateinit var restoreDialog: RestoreDialog
    private lateinit var simBar: SimulatorBar
    private lateinit var canvas: DesignCanvasView
    private lateinit var host: WorkspaceHost
    private val frames = LinkedHashMap<PanelId, PanelFrame>()
    private val contents = HashMap<PanelId, PanelContent>()
    private var topBarState: List<Any> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The engine session and the workspace are created independently and never reference each other.
        session = DigitizingSession()
        workspace = WorkspaceController()
        storage = FileWorkspaceStorage(File(filesDir, "workspace/layout.json"))
        try { workspace.restore(storage) } catch (e: Exception) { /* keep default layout */ }

        projectIO = ProjectIO(this)
        imageImport = ImageImport(this, session, ::toast)
        textTool = TextTool(this, session, ::toast)
        transformDialog = TransformDialog(this, session, ::toast)
        restoreDialog = RestoreDialog(this, session, projectIO, ::toast)
        if (savedInstanceState == null && projectIO.hasAutosave()) {
            projectIO.openAutosave()?.let { session.load(it.design) }   // restore work after a crash/kill
        }

        canvas = DesignCanvasView(this, session)
        canvas.stylusOnlyDrawing = workspace.layout.stylusOnlyDrawing
        canvas.callbacks = object : DesignCanvasView.Callbacks {
            override fun onObjectLongPress(objectId: Int, screenX: Int, screenY: Int) = showObjectMenu(objectId, screenX, screenY)
            override fun onCanvasMenu(screenX: Int, screenY: Int) = showCanvasMenu(screenX, screenY)
        }

        val env = StudioEnv(this, session, canvas, ::showToolMenu, ::refreshPanels)
        val frameHost = object : FrameHost {
            override fun floatArea() = host.floatArea()
            override fun hostWidth() = host.hostWidth()
            override fun hostHeight() = host.hostHeight()
        }
        for (id in PanelId.values()) {
            val pc = PanelContents.build(id, env)
            contents[id] = pc
            frames[id] = PanelFrame(this, id, pc.view, workspace, frameHost)
        }
        simBar = SimulatorBar(this, canvas, session)
        host = WorkspaceHost(this, workspace, canvas, frames, ::runQuickCommand)
        setContentView(host)

        buildTopBar()
        applyFocusChrome(workspace.layout.focusMode)

        session.addListener { runOnUiThread { refreshPanels(); host.refreshQuickEnabled(); refreshTopBarEnabled() } }
        workspace.addListener { old, new ->
            canvas.stylusOnlyDrawing = new.stylusOnlyDrawing
            if (old.focusMode != new.focusMode) applyFocusChrome(new.focusMode)
            val tb = topBarKey(new)
            if (tb != topBarState) buildTopBar()
        }
        host.setQuickEnabled { quickEnabled(it) }
        refreshPanels()
    }

    override fun onPause() {
        try { workspace.save(storage) } catch (e: Exception) { /* best effort */ }
        simBar.onPause()
        projectIO.autosave(projectIO.makeProject(session.design))
        super.onPause()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (workspace.layout.focusMode) workspace.dispatch(WorkspaceAction.SetFocus(false)) else super.onBackPressed()
    }

    // ---- panel refresh ---------------------------------------------------------------------------

    private fun refreshPanels() {
        for ((id, pc) in contents) if (frames.getValue(id).parent != null) pc.refresh()
        canvas.invalidate()
    }

    // ---- top bar --------------------------------------------------------------------------------------

    private fun topBarKey(l: WorkspaceLayout): List<Any> = listOf(l.mode, l.iconScale, l.name, l.panels.values.map { it.open })
    private var undoBtn: View? = null
    private var redoBtn: View? = null
    private var finishBtn: View? = null
    private var cancelBtn: View? = null

    private fun buildTopBar() {
        val l = workspace.layout
        topBarState = topBarKey(l)
        val s = 40f * l.iconScale
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        fun gap() = row.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        fun glyph(g: String, tint: Int, onClick: () -> Unit): View = glyphButton(g, tint, s, onClick).also { row.addView(it); gap() }

        val fileBtn = textButton("File ▾", Theme.WARN) {}
        fileBtn.setOnClickListener { showFileMenu(fileBtn) }
        row.addView(fileBtn); gap()
        val simBtn = textButton(if (simBar.isOpen) "■ Simulator" else "▶ Simulate", Theme.OK) {}
        simBtn.setOnClickListener {
            if (session.plan.stitches.isEmpty()) toast("Nothing to simulate yet - digitize something first")
            else { simBar.toggle(host); simBtn.text = if (simBar.isOpen) "■ Simulator" else "▶ Simulate" }
        }
        simBar.onClosed = { simBtn.text = "▶ Simulate" }
        row.addView(simBtn); gap()
        row.addView(textButton(if (l.mode == UserMode.BEGINNER) "Mode: Beginner" else "Mode: Pro", Theme.PURPLE) {
            workspace.applyPreset(if (l.mode == UserMode.BEGINNER) WorkspacePresets.PROFESSIONAL else WorkspacePresets.BEGINNER)
        }); gap()
        val presets = textButton("Layout: ${l.name} ▾", Theme.ACCENT) {}
        presets.setOnClickListener { showPresetMenu(presets) }
        row.addView(presets); gap()
        if (l.mode == UserMode.PROFESSIONAL) {
            val panels = textButton("Panels ▾", Theme.OK) {}
            panels.setOnClickListener { showPanelsMenu(panels) }
            row.addView(panels); gap()
        }
        undoBtn = glyph("↶", Theme.ACCENT) { session.undo() }
        redoBtn = glyph("↷", Theme.ACCENT) { session.redo() }
        finishBtn = glyph("✔", Theme.OK) { session.finishObject() }
        cancelBtn = glyph("✖", Theme.DANGER) { session.cancelPending() }
        glyph("⤢", Theme.WARN) { canvas.fitDesign() }
        glyph("⛶", Theme.PURPLE) { workspace.dispatch(WorkspaceAction.SetFocus(true)) }   // FULL SCREEN / FOCUS MODE
        val settings = glyphButton("⚙", Theme.TEXT_DIM, s) {}
        settings.setOnClickListener { showSettingsMenu(settings) }
        row.addView(settings)

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        host.topBarHolder.removeAllViews()
        host.topBarHolder.addView(scroll)
        refreshTopBarEnabled()
    }

    private fun refreshTopBarEnabled() {
        undoBtn?.alpha = if (session.canUndo) 1f else 0.35f
        redoBtn?.alpha = if (session.canRedo) 1f else 0.35f
        finishBtn?.alpha = if (session.canFinish) 1f else 0.35f
        cancelBtn?.alpha = if (session.pending.isNotEmpty()) 1f else 0.35f
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun showFileMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "New project")
        m.menu.add(0, 1, 1, "Open…")
        m.menu.add(0, 2, 2, "Save")
        m.menu.add(0, 3, 3, "Save As…")
        m.menu.add(0, 4, 4, "Export DST…")
        m.menu.add(0, 5, 5, "Export EXP…")
        m.menu.add(0, 6, 6, "Export JEF…")
        m.menu.add(0, 7, 7, "Export PES…")
        m.menu.add(0, 8, 8, "Export VP3…")
        m.menu.add(0, 9, 9, "Export XXX…")
        m.menu.add(0, 10, 10, "Export HUS…")
        m.menu.add(0, 12, 12, "Add text / lettering…")
        m.menu.add(0, 13, 13, "Restore from backup…")
        m.menu.add(0, 14, 14, "Optimize stitch order")
        m.menu.add(0, 11, 11, "Import SVG / image / PDF…")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> { session.load(com.topz.stitchdraw.embroidery.EmbroideryDesign()); projectIO.newProject() }
                1 -> projectIO.launchOpen()
                2 -> try {
                    if (projectIO.save(projectIO.makeProject(session.design))) toast("Saved") else projectIO.launchSaveAs()
                } catch (e: Exception) { toast("Save failed: ${e.message}") }
                3 -> projectIO.launchSaveAs()
                11 -> imageImport.launchPicker()
                12 -> textTool.show()
                13 -> restoreDialog.show()
                14 -> {
                    val r = session.optimizeSequence()
                    toast(if (!r.changed) "Stitch order is already efficient"
                        else "Thread changes ${r.before.colorChanges} → ${r.after.colorChanges}, jump travel ${r.before.travelMm.toInt()} → ${r.after.travelMm.toInt()} mm. Undo restores the old order.")
                }
                4, 5, 6, 7, 8, 9, 10 -> {
                    if (session.plan.stitches.isEmpty()) toast("Nothing to export yet - digitize something first")
                    else projectIO.launchExport(when (it.itemId) {
                        4 -> com.topz.stitchdraw.export.DstEncoder
                        5 -> com.topz.stitchdraw.export.ExpEncoder
                        6 -> com.topz.stitchdraw.export.JefEncoder
                        7 -> com.topz.stitchdraw.export.PesEncoder
                        8 -> com.topz.stitchdraw.export.Vp3Encoder
                        9 -> com.topz.stitchdraw.export.XxxEncoder
                        else -> com.topz.stitchdraw.export.HusEncoder
                    })
                }
            }
            true
        }
        m.show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == ImageImport.REQ_IMAGE) { imageImport.onPicked(uri); return }
        if (requestCode == ProjectIO.REQ_EXPORT) {
            try { toast("Exported - " + projectIO.finishExport(uri, session.plan)) }
            catch (e: Exception) { toast("Export failed: ${e.message}") }
            return
        }
        try {
            val opened = projectIO.onResult(requestCode, uri, projectIO.makeProject(session.design))
            if (opened != null) session.load(opened.design) else if (requestCode == ProjectIO.REQ_SAVE_AS) toast("Saved")
        } catch (e: StdFormatException) { toast(e.message ?: "Invalid project file")
        } catch (e: Exception) { toast("File error: ${e.message}") }
    }

    private fun showPresetMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val names = workspace.allPresetNames()
        names.forEachIndexed { i, n -> m.menu.add(0, i, i, (if (n == workspace.layout.name) "✓ " else "") + n) }
        m.menu.add(1, 1000, 1000, "💾 Save current layout…")
        if (workspace.savedNames().isNotEmpty()) m.menu.add(1, 1001, 1001, "🗑 Delete saved layout…")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1000 -> promptText(this, "Save workspace layout as", "My layout") { name ->
                    if (!workspace.saveCurrentAs(name)) Toast.makeText(this, "Choose a different name", Toast.LENGTH_SHORT).show()
                    else { try { workspace.save(storage) } catch (e: Exception) {}; buildTopBar() }
                }
                1001 -> {
                    val d = PopupMenu(this, anchor)
                    workspace.savedNames().forEachIndexed { i, n -> d.menu.add(0, i, i, n) }
                    d.setOnMenuItemClickListener { di ->
                        workspace.deleteSaved(workspace.savedNames()[di.itemId]); try { workspace.save(storage) } catch (e: Exception) {}; true
                    }
                    d.show()
                }
                else -> workspace.applyPreset(names[item.itemId])
            }
            true
        }
        m.show()
    }

    private fun showPanelsMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        PanelId.values().forEachIndexed { i, id ->
            val open = workspace.layout.panel(id).open
            m.menu.add(0, i, i, (if (open) "✓ " else "    ") + id.title)
        }
        m.setOnMenuItemClickListener { item ->
            val id = PanelId.values()[item.itemId]
            workspace.dispatch(if (workspace.layout.panel(id).open) WorkspaceAction.Close(id) else WorkspaceAction.Open(id))
            true
        }
        m.show()
    }

    private fun showSettingsMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val scales = listOf("Small icons" to 0.85f, "Medium icons" to 1.0f, "Large icons" to 1.25f, "Extra large icons" to 1.5f)
        scales.forEachIndexed { i, (label, v) ->
            m.menu.add(0, i, i, (if (kotlin.math.abs(workspace.layout.iconScale - v) < 0.05f) "✓ " else "    ") + label)
        }
        m.menu.add(1, 100, 100, (if (workspace.layout.stylusOnlyDrawing) "✓ " else "    ") + "Stylus-only drawing (finger navigates)")
        m.setOnMenuItemClickListener { item ->
            if (item.itemId == 100) workspace.dispatch(WorkspaceAction.SetStylusOnly(!workspace.layout.stylusOnlyDrawing))
            else workspace.dispatch(WorkspaceAction.SetIconScale(scales[item.itemId].second))
            true
        }
        m.show()
    }

    // ---- quick toolbar (focus mode) -------------------------------------------------------------------------

    private fun quickEnabled(c: QuickCommand): Boolean = when (c) {
        QuickCommand.UNDO -> session.canUndo
        QuickCommand.REDO -> session.canRedo
        QuickCommand.FINISH_OBJECT -> session.canFinish
        QuickCommand.CANCEL_OBJECT -> session.pending.isNotEmpty()
        else -> true
    }

    private fun runQuickCommand(c: QuickCommand) {
        when (c) {
            QuickCommand.UNDO -> session.undo()
            QuickCommand.REDO -> session.redo()
            QuickCommand.FINISH_OBJECT -> session.finishObject()
            QuickCommand.CANCEL_OBJECT -> session.cancelPending()
            QuickCommand.ZOOM_FIT -> canvas.fitDesign()
            QuickCommand.TOGGLE_GRID -> { canvas.showGrid = !canvas.showGrid; canvas.invalidate() }
            QuickCommand.TOOL_SELECT -> session.selectTool(DigitizingTool.SELECT)
            QuickCommand.TOOL_RUNNING -> session.selectTool(DigitizingTool.RUNNING)
            QuickCommand.TOOL_TRIPLE -> session.selectTool(DigitizingTool.TRIPLE_RUNNING)
            QuickCommand.EXIT_FOCUS -> workspace.dispatch(WorkspaceAction.SetFocus(false))
            QuickCommand.PANELS_MENU -> workspace.dispatch(WorkspaceAction.SetFocus(false))
        }
    }

    // ---- context menus -------------------------------------------------------------------------------------

    private fun showToolMenu(anchor: View, kind: ToolMenuKind) {
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        showContextMenu(anchor, loc[0] + anchor.width / 2, loc[1] + anchor.height / 2, ContextMenus.forTool(kind)) { item ->
            when (item.command) {
                MenuCommand.TOOL_SETTINGS, MenuCommand.STITCH_LENGTH -> {
                    promptStitchLength(this, session.params.stitchLengthMm) { mm -> session.setParams(session.params.copy(stitchLengthMm = mm)) }
                }
                else -> Unit
            }
        }
    }

    private fun showObjectMenu(objectId: Int, x: Int, y: Int) {
        val d = session.design
        val idx = d.objects.indexOfFirst { it.id == objectId }
        val items = ContextMenus.forObject(canBringForward = idx in 0 until d.objects.lastIndex, canSendBackward = idx > 0)
        showContextMenu(canvas, x, y, items) { item ->
            when (item.command) {
                MenuCommand.EDIT_OBJECT -> workspace.dispatch(WorkspaceAction.Open(PanelId.OBJECT_PROPERTIES))
                MenuCommand.STITCH_PROPERTIES -> workspace.dispatch(WorkspaceAction.Open(PanelId.STITCH_PROPERTIES))
                MenuCommand.CHANGE_STITCH_TYPE -> {
                    val m = PopupMenu(this, canvas)
                    StitchType.values().forEachIndexed { i, t -> m.menu.add(0, i, i, t.label) }
                    m.setOnMenuItemClickListener { session.changeStitchType(objectId, StitchType.values()[it.itemId]); true }
                    m.show()
                }
                MenuCommand.TRANSFORM_OBJECT -> transformDialog.show(objectId)
                MenuCommand.DUPLICATE -> session.duplicateObject(objectId)
                MenuCommand.DELETE -> session.deleteObject(objectId)
                MenuCommand.BRING_FORWARD -> session.moveObject(objectId, +1)
                MenuCommand.SEND_BACKWARD -> session.moveObject(objectId, -1)
                else -> Unit
            }
        }
    }

    private fun showCanvasMenu(x: Int, y: Int) {
        val items = ContextMenus.forCanvas(CanvasViewState(canvas.showGrid, canvas.showRulers, canvas.showHoop))
        showContextMenu(canvas, x, y, items) { item ->
            when (item.command) {
                MenuCommand.ZOOM_IN -> canvas.zoomBy(1.4f)
                MenuCommand.ZOOM_OUT -> canvas.zoomBy(1f / 1.4f)
                MenuCommand.FIT_DESIGN -> canvas.fitDesign()
                MenuCommand.TOGGLE_GRID -> canvas.showGrid = !canvas.showGrid
                MenuCommand.TOGGLE_RULERS -> canvas.showRulers = !canvas.showRulers
                MenuCommand.TOGGLE_HOOP -> canvas.showHoop = !canvas.showHoop
                else -> Unit
            }
            canvas.invalidate()
            refreshPanels()
        }
    }

    // ---- immersive focus mode -------------------------------------------------------------------------------

    private fun applyFocusChrome(focus: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (focus) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (focus)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
