package com.topz.stitchdraw.app

import com.topz.stitchdraw.vectorart.Doc
import com.topz.stitchdraw.vectorart.Kind as VectorKind
import com.topz.stitchdraw.vectorart.Node as VectorNode
import com.topz.stitchdraw.vectorart.VObj
import com.topz.stitchdraw.vectorart.VectorStitchAdapter
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.StitchType
import android.graphics.Matrix

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
    private lateinit var layoutEditor: LayoutEditor
    private lateinit var menuSheet: MenuSheet
    private val frames = LinkedHashMap<PanelId, PanelFrame>()
    private val contents = HashMap<PanelId, PanelContent>()
    private var topBarState: List<Any> = emptyList()
    private lateinit var vectorDocument: Doc
    private var lastVectorUid: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        // The engine session and the workspace are created independently and never reference each other.
        session = DigitizingSession()
        workspace = WorkspaceController()
        storage = FileWorkspaceStorage(File(filesDir, "workspace/layout.json"))
        try { workspace.restore(storage) } catch (e: Exception) { /* keep default layout */ }
        // Phones: the old docked "Professional" layout leaves almost no canvas. Once per install (and once more
        // for installs that predate this setting) switch narrow screens to the Compact layout; the user can change it.
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (!prefs.getBoolean("compact_default_applied", false)) {
            if (resources.configuration.screenWidthDp < 600) workspace.applyPreset(WorkspacePresets.COMPACT)
            prefs.edit().putBoolean("compact_default_applied", true).apply()
        }

        projectIO = ProjectIO(this)
        vectorDocument = Doc().apply { w = session.design.hoop.widthMm; h = session.design.hoop.heightMm }
        imageImport = ImageImport(this, session, ::toast)
        textTool = TextTool(this, session, ::toast)
        transformDialog = TransformDialog(this, session, ::toast)
        restoreDialog = RestoreDialog(
            this, session, projectIO, ::toast,
            getVectorArt = { vectorDocument.toJson().toString() },
            setVectorArt = { json ->
                vectorDocument = json?.let { runCatching { Doc.fromJson(org.json.JSONObject(it)) }.getOrNull() }
                    ?: Doc().apply { w = session.design.hoop.widthMm; h = session.design.hoop.heightMm }
                if (::canvas.isInitialized) canvas.vectorDocument = vectorDocument
            },
        )
        if (savedInstanceState == null && projectIO.hasAutosave()) {
            projectIO.openAutosave()?.let { recovered ->
                session.load(recovered.design)
                recovered.vectorArtJson?.let { json -> runCatching { vectorDocument = Doc.fromJson(org.json.JSONObject(json)) } }
            }   // restore work after a crash/kill
        }

        canvas = DesignCanvasView(this, session)
        canvas.vectorDocument = vectorDocument
        canvas.onVectorChanged = {
            if (::projectIO.isInitialized) projectIO.autosave(projectIO.makeProject(session.design, vectorDocument.toJson().toString()))
            refreshPanels()
        }
        canvas.stylusOnlyDrawing = workspace.layout.stylusOnlyDrawing
        canvas.callbacks = object : DesignCanvasView.Callbacks {
            override fun onObjectLongPress(objectId: Int, screenX: Int, screenY: Int) = showObjectMenu(objectId, screenX, screenY)
            override fun onCanvasMenu(screenX: Int, screenY: Int) = showCanvasMenu(screenX, screenY)
        }

        layoutEditor = LayoutEditor(this, workspace) { /* the workspace listener redraws everything */ }
        menuSheet = MenuSheet(this, workspace) { layoutEditor.show() }
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
        if (savedInstanceState == null) CrashLog.read(this)?.let { showLog("The app closed unexpectedly last time", it, clearAfter = true) }
    }

    /** Shows [text] (version info and/or a crash trace) with Copy and Share, so it can be sent without typing it out. */
    private fun showLog(title: String, text: String, clearAfter: Boolean) {
        val body = android.widget.TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Share") { _, _ -> CrashLog.share(this, text); if (clearAfter) CrashLog.clear(this) }
            .setNeutralButton("Copy") { _, _ -> CrashLog.copy(this, text); toast("Copied"); if (clearAfter) CrashLog.clear(this) }
            .setNegativeButton("Close") { _, _ -> if (clearAfter) CrashLog.clear(this) }
            .show()
    }

    private fun showAbout() {
        val crash = CrashLog.read(this)
        val info = CrashLog.header(this) + "Layout: ${workspace.layout.name}\n" +
            (if (crash != null) "\n--- last crash ---\n" + crash else "\nNo crash recorded.")
        showLog("About / Diagnostics", info, clearAfter = false)
    }

    override fun onPause() {
        try { workspace.save(storage) } catch (e: Exception) { /* best effort */ }
        simBar.onPause()
        projectIO.autosave(projectIO.makeProject(session.design, vectorDocument.toJson().toString()))
        super.onPause()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (workspace.layout.focusMode) workspace.dispatch(WorkspaceAction.SetFocus(false)) else super.onBackPressed()
    }

    // ---- panel refresh ---------------------------------------------------------------------------

    private fun refreshPanels() {
        for ((id, pc) in contents) if (frames.getValue(id).parent != null) pc.refresh()
        val st = session.plan.stats
        val b = st.bounds
        host.setStatus("%,d stitches · %.0f × %.0f mm · %d objects".format(st.stitchCount, b.width, b.height, session.design.objects.size))
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
            val next = if (l.mode == UserMode.BEGINNER) UserMode.PROFESSIONAL else UserMode.BEGINNER
            // Compact and Menu are phone layouts: only the mode flag changes, the arrangement stays.
            if (l.name == WorkspacePresets.COMPACT || l.name == WorkspacePresets.MENU) workspace.dispatch(WorkspaceAction.SetMode(next))
            else workspace.applyPreset(if (next == UserMode.BEGINNER) WorkspacePresets.BEGINNER else WorkspacePresets.PROFESSIONAL)
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
        val vectorBtn = textButton("Vector ▾", Theme.PURPLE) {}
        vectorBtn.setOnClickListener { showVectorMenu(vectorBtn) }
        row.addView(vectorBtn); gap()
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
        m.menu.add(1, 15, 15, "About / Diagnostics…")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> {
                    val fresh = com.topz.stitchdraw.embroidery.EmbroideryDesign()
                    session.load(fresh); projectIO.newProject()
                    vectorDocument = Doc().apply { w = fresh.hoop.widthMm; h = fresh.hoop.heightMm }
                    canvas.vectorDocument = vectorDocument; lastVectorUid = 0
                }
                1 -> projectIO.launchOpen()
                2 -> try {
                    if (projectIO.save(projectIO.makeProject(session.design, vectorDocument.toJson().toString()))) toast("Saved") else projectIO.launchSaveAs()
                } catch (e: Exception) { toast("Save failed: ${e.message}") }
                3 -> projectIO.launchSaveAs()
                11 -> imageImport.launchPicker()
                12 -> textTool.show()
                13 -> restoreDialog.show()
                15 -> showAbout()
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
            val opened = projectIO.onResult(requestCode, uri, projectIO.makeProject(session.design, vectorDocument.toJson().toString()))
            if (opened != null) {
                session.load(opened.design)
                vectorDocument = opened.vectorArtJson?.let { runCatching { Doc.fromJson(org.json.JSONObject(it)) }.getOrNull() }
                    ?: Doc().apply { w = opened.design.hoop.widthMm; h = opened.design.hoop.heightMm }
                canvas.vectorDocument = vectorDocument
                canvas.invalidate()
            } else if (requestCode == ProjectIO.REQ_SAVE_AS) toast("Saved")
        } catch (e: StdFormatException) { toast(e.message ?: "Invalid project file")
        } catch (e: Exception) { toast("File error: ${e.message}") }
    }

    private fun showVectorMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 1, if (canvas.showVectorArtwork) "Hide vector artwork" else "Show vector artwork")
        menu.menu.add(0, 2, 2, "Select / Move")
        menu.menu.add(0, 3, 3, "Node Edit")
        menu.menu.add(0, 4, 4, "Draw Rectangle")
        menu.menu.add(0, 5, 5, "Draw Ellipse")
        menu.menu.add(0, 7, 7, "Draw Hexagon")
        menu.menu.add(0, 8, 8, "Freehand Pencil")
        menu.menu.add(0, 9, 9, "Stop Vector Tool")
        menu.menu.add(1, 10, 10, "Delete selected vector")
        menu.menu.add(2, 11, 11, "Convert selected vector to Running stitch")
        menu.menu.add(2, 12, 12, "Convert selected vector to Tatami fill")
        menu.menu.add(3, 6, 6, "Clear vector artwork…")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> canvas.showVectorArtwork = !canvas.showVectorArtwork
                2 -> canvas.vectorTool = DesignCanvasView.VectorTool.SELECT
                3 -> canvas.vectorTool = DesignCanvasView.VectorTool.NODE
                4 -> canvas.vectorTool = DesignCanvasView.VectorTool.RECTANGLE
                5 -> canvas.vectorTool = DesignCanvasView.VectorTool.ELLIPSE
                7 -> canvas.vectorTool = DesignCanvasView.VectorTool.POLYGON
                8 -> canvas.vectorTool = DesignCanvasView.VectorTool.FREEHAND
                9 -> canvas.vectorTool = DesignCanvasView.VectorTool.OFF
                10 -> if (!canvas.deleteSelectedVector()) toast("Select an unlocked vector object first.")
                11 -> convertSelectedVector(StitchType.RUNNING)
                12 -> convertSelectedVector(StitchType.TATAMI)
                6 -> android.app.AlertDialog.Builder(this).setTitle("Clear vector artwork?")
                    .setMessage("This removes the vector objects from the current project. Existing embroidery stitches are not affected.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        vectorDocument.objs.clear(); lastVectorUid = 0; canvas.invalidate(); refreshPanels()
                    }.show()
            }
            true
        }
        menu.show()
    }

    private fun addVectorShape(ellipse: Boolean) {
        val w = vectorDocument.w; val h = vectorDocument.h
        val left = w * 0.30f; val right = w * 0.70f
        val top = h * 0.30f; val bottom = h * 0.70f
        val obj = VObj(VectorKind.PATH).apply {
            name = if (ellipse) "Vector Ellipse ${vectorDocument.objs.size + 1}" else "Vector Rectangle ${vectorDocument.objs.size + 1}"
            closed = true; fill = 0xFF2D8CFF.toInt(); stroke = 0xFFFFC857.toInt(); strokeW = 0.45f
            if (!ellipse) nodes = mutableListOf(
                VectorNode(left, top), VectorNode(right, top), VectorNode(right, bottom), VectorNode(left, bottom)
            ) else {
                val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                val rx = (right - left) / 2f; val ry = (bottom - top) / 2f
                val k = 0.55228475f
                nodes = mutableListOf(
                    VectorNode(cx, top).apply { ix = cx - rx * k; iy = top; ox = cx + rx * k; oy = top },
                    VectorNode(right, cy).apply { ix = right; iy = cy - ry * k; ox = right; oy = cy + ry * k },
                    VectorNode(cx, bottom).apply { ix = cx + rx * k; iy = bottom; ox = cx - rx * k; oy = bottom },
                    VectorNode(left, cy).apply { ix = left; iy = cy + ry * k; ox = left; oy = cy - ry * k },
                )
            }
            uid = (vectorDocument.objs.maxOfOrNull { it.uid } ?: 0) + 1
        }
        vectorDocument.objs.add(obj); lastVectorUid = obj.uid
        canvas.showVectorArtwork = true; canvas.invalidate(); refreshPanels()
        toast("Added ${if (ellipse) "ellipse" else "rectangle"}. Vector artwork remains editable.")
    }

    private fun convertSelectedVector(type: StitchType) {
        val source = canvas.selectedVectorUid?.let { id -> vectorDocument.objs.firstOrNull { it.uid == id } }
            ?: vectorDocument.objs.lastOrNull()
        if (source == null) { toast("Add a vector shape first."); return }
        if (vectorDocument.layers.getOrNull(source.layer)?.locked == true) { toast("The source vector layer is locked."); return }
        val centered = source.copy()
        val matrix = Matrix().apply { setTranslate(-vectorDocument.w / 2f, -vectorDocument.h / 2f) }
        centered.transform(matrix)
        val targetLayer = session.design.layers.firstOrNull { !it.locked }
        if (targetLayer == null) { toast("Unlock an embroidery layer before converting vector artwork."); return }
        val conversion = VectorStitchAdapter.convertPath(
            centered, session.design.nextObjectId,
            targetLayer.id,
            session.activeThread, type, StitchParams(),
        )
        val embroidery = conversion.embroideryObject
        if (embroidery == null) { toast(conversion.warnings.joinToString(" ")); return }
        session.addExternalObject(embroidery)
        val warning = conversion.warnings.firstOrNull()
        toast(if (warning == null) "Converted selected vector to ${type.label}. Original vector retained." else "Converted. $warning")
        refreshPanels()
    }

    private fun showPresetMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val names = workspace.allPresetNames()
        names.forEachIndexed { i, n -> m.menu.add(0, i, i, (if (n == workspace.layout.name) "✓ " else "") + n) }
        m.menu.add(1, 999, 999, "⇅ Customize order…")
        m.menu.add(1, 1000, 1000, "💾 Save current layout…")
        if (workspace.savedNames().isNotEmpty()) m.menu.add(1, 1001, 1001, "🗑 Delete saved layout…")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                999 -> layoutEditor.show()
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
            QuickCommand.TOOL_SELECT -> { canvas.vectorTool = DesignCanvasView.VectorTool.OFF; session.selectTool(DigitizingTool.SELECT) }
            QuickCommand.TOOL_RUNNING -> { canvas.vectorTool = DesignCanvasView.VectorTool.OFF; session.selectTool(DigitizingTool.RUNNING) }
            QuickCommand.TOOL_TRIPLE -> { canvas.vectorTool = DesignCanvasView.VectorTool.OFF; session.selectTool(DigitizingTool.TRIPLE_RUNNING) }
            QuickCommand.EXIT_FOCUS -> workspace.dispatch(WorkspaceAction.SetFocus(false))
            QuickCommand.PANELS_MENU ->
                if (workspace.layout.style == com.topz.stitchdraw.workspace.LayoutStyle.MENU) menuSheet.show()
                else workspace.dispatch(WorkspaceAction.SetFocus(false))
            QuickCommand.TOGGLE_FOCUS -> workspace.dispatch(WorkspaceAction.SetFocus(!workspace.layout.focusMode))
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
