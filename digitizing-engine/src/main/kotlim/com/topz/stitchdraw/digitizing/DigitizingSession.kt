package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.embroidery.DesignObject
import com.topz.stitchdraw.embroidery.EmbroideryDesign
import com.topz.stitchdraw.embroidery.Layer
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.StitchPlan
import com.topz.stitchdraw.embroidery.StitchPlanner
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.embroidery.ThreadColor
import com.topz.stitchdraw.vector.Polyline
import com.topz.stitchdraw.vector.Vec2

enum class DigitizingTool(val label: String, val stitchType: StitchType?) {
    SELECT("Select / Pan", null),
    RUNNING("Running Stitch", StitchType.RUNNING),
    TRIPLE_RUNNING("Triple Run", StitchType.TRIPLE_RUNNING),
    SATIN("Satin", StitchType.SATIN),
    ZIGZAG("Zigzag", StitchType.ZIGZAG),
    TATAMI("Tatami Fill", StitchType.TATAMI),
    APPLIQUE("Appliqué", StitchType.APPLIQUE);

    val isDrawing: Boolean get() = stitchType != null
}

/**
 * The live digitizing operation: current design, active tool, the object being digitized
 * (pending points) and undo/redo history.
 *
 * This class knows nothing about the workspace, panels or Android. Panel rearrangement
 * therefore cannot alter [design], [pending], [tool] or the history.
 */
class DigitizingSession(initial: EmbroideryDesign = EmbroideryDesign()) {

    fun interface Listener { fun onSessionChanged() }

    var design: EmbroideryDesign = initial
        private set
    var tool: DigitizingTool = DigitizingTool.SELECT
        private set
    var activeThread: Int = 0
        private set
    var params: StitchParams = StitchParams()
        private set
    var selectedObjectId: Int? = null
        private set

    private val pendingPoints = ArrayList<Vec2>()
    val pending: List<Vec2> get() = pendingPoints

    /** Smooth the digitized points into a curve through them (Pen / Bézier mode) instead of straight segments. */
    var curveMode: Boolean = false
        set(v) { if (field != v) { field = v; changed() } }

    /** What will become the object path: the raw polyline, or the smoothed curve when [curveMode] is on. */
    val pendingPath: List<Vec2>
        get() = if (curveMode && pendingPoints.size >= 3)
            com.topz.stitchdraw.vector.Curves.smooth(pendingPoints, closed = tool.stitchType?.isFill == true)
        else pendingPoints

    private val undoStack = ArrayDeque<EmbroideryDesign>()
    private val redoStack = ArrayDeque<EmbroideryDesign>()
    private val listeners = LinkedHashSet<Listener>()

    private var cachedFor: EmbroideryDesign? = null
    private var cachedPlan: StitchPlan = StitchPlan.EMPTY

    /** Machine instruction list for [design]; recomputed only when the design changes. */
    val plan: StitchPlan
        get() {
            if (cachedFor !== design) { cachedPlan = StitchPlanner.plan(design); cachedFor = design }
            return cachedPlan
        }

    /** True when the pending points are enough to finish an object with the active tool. */
    val canFinish: Boolean get() = tool.stitchType?.let { pendingPoints.size >= it.minPoints } ?: false

    val canUndo: Boolean get() = pendingPoints.isNotEmpty() || undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toList()) l.onSessionChanged() }

    // ---- tool state ---------------------------------------------------------------------------

    fun selectTool(t: DigitizingTool) {
        if (t == tool) return
        // Switching tools keeps in-progress points only between drawing tools (type applies at finish).
        if (!t.isDrawing) pendingPoints.clear()
        tool = t
        changed()
    }

    fun setActiveThread(i: Int) {
        if (i !in design.threads.indices || i == activeThread) return
        activeThread = i
        // Color Editing while an object is selected recolors it.
        changed()
    }

    fun setParams(p: StitchParams) { params = p; changed() }

    // ---- digitizing ----------------------------------------------------------------------------

    /** Adds a point (mm). Ignores a point closer than 0.2 mm to the previous one. Returns true if added. */
    fun addPoint(p: Vec2): Boolean {
        if (!tool.isDrawing) return false
        if (pendingPoints.isNotEmpty() && pendingPoints.last().distanceTo(p) < MIN_POINT_GAP_MM) return false
        pendingPoints.add(p)
        changed()
        return true
    }

    /** Commits the pending path as an object. Needs at least two points. */
    fun finishObject(): DesignObject? {
        val type = tool.stitchType ?: return null
        if (pendingPoints.size < type.minPoints) return null
        val id = design.nextObjectId
        val o = DesignObject(
            id = id,
            name = "${type.label} $id",
            type = type,
            path = pendingPath.toList(),
            threadIndex = activeThread,
            layerId = design.layers.firstOrNull { !it.locked }?.id ?: design.layers.first().id,
            params = params,
        )
        pendingPoints.clear()
        commit(design.addObject(o))
        selectedObjectId = id
        changed()
        return o
    }

    fun cancelPending() {
        if (pendingPoints.isEmpty()) return
        pendingPoints.clear()
        changed()
    }

    /**
     * Re-orders objects to cut colour changes and jump travel (see Sequencer). One undo step; nothing changes when the
     * current order is already as good. Always returns the before/after numbers.
     */
    fun optimizeSequence(): com.topz.stitchdraw.embroidery.SequenceResult {
        val r = com.topz.stitchdraw.embroidery.Sequencer.optimize(design)
        if (r.changed) { commit(design.copy(objects = r.objects)); changed() }
        return r
    }

    // ---- transform (move / scale / rotate / mirror) ---------------------------------------------

    private var transformBase: EmbroideryDesign? = null
    val isTransforming: Boolean get() = transformBase != null

    /** Bounding box (min, max) in mm of the selected object, or null. */
    fun selectedBounds(): Pair<Vec2, Vec2>? = selectedObjectId?.let { design.obj(it) }?.let { ObjectTransform.bounds(it) }

    /** Starts an interactive transform of the selected object. History is untouched until [endTransform]. */
    fun beginTransform(): Boolean {
        val id = selectedObjectId ?: return false
        if (design.obj(id) == null || transformBase != null) return false
        val layer = design.layers.firstOrNull { it.id == design.obj(id)!!.layerId }
        if (layer?.locked == true) return false
        transformBase = design
        return true
    }

    /** Live preview: always computed from the design as it was at [beginTransform], so it never accumulates error. */
    fun previewTransform(t: ObjectTransform) {
        val base = transformBase ?: return
        val id = selectedObjectId ?: return
        val o = base.obj(id) ?: return
        design = base.replaceObject(ObjectTransform.apply(o, t))
        changed()
    }

    /** Commits the preview as ONE undo step (nothing is recorded when the transform was the identity). */
    fun endTransform() {
        val base = transformBase ?: return
        transformBase = null
        if (design !== base && design != base) {
            undoStack.addLast(base)
            while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
            redoStack.clear()
        }
        changed()
    }

    fun cancelTransform() {
        val base = transformBase ?: return
        transformBase = null
        design = base
        changed()
    }

    /** One-shot transform of the selected object (used by the numeric dialog). Returns false if nothing was selected/locked. */
    fun transformSelected(t: ObjectTransform): Boolean {
        if (!beginTransform()) return false
        previewTransform(t); endTransform()
        return true
    }

    // ---- editing -------------------------------------------------------------------------------

    fun select(id: Int?) { if (selectedObjectId != id) { selectedObjectId = id; changed() } }

    /** Object whose path is nearest to [p] within [toleranceMm], topmost first. */
    fun hitTest(p: Vec2, toleranceMm: Float): DesignObject? =
        design.objects.asReversed().firstOrNull { o ->
            design.layers.firstOrNull { it.id == o.layerId }?.visible != false &&
                (Polyline.distanceToPath(p, o.path) <= toleranceMm ||
                    (o.type.isFill && o.path.size >= 3 && pointInPolygon(p, o.path)))
        }

    private fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    fun deleteObject(id: Int) {
        if (design.obj(id) == null) return
        commit(design.removeObject(id))
        if (selectedObjectId == id) selectedObjectId = null
        changed()
    }

    fun duplicateObject(id: Int, offsetMm: Vec2 = Vec2(2f, 2f)): DesignObject? {
        val o = design.obj(id) ?: return null
        val copy = o.copy(id = design.nextObjectId, name = o.name + " copy", path = o.path.map { it + offsetMm })
        commit(design.addObject(copy))
        selectedObjectId = copy.id
        changed()
        return copy
    }

    fun moveObject(id: Int, delta: Int) {
        val next = design.moveObject(id, delta)
        if (next === design) return
        commit(next); changed()
    }

    fun changeStitchType(id: Int, type: StitchType) {
        val o = design.obj(id) ?: return
        if (o.type == type) return
        commit(design.replaceObject(o.copy(type = type, name = o.name.replace(o.type.label, type.label))))
        changed()
    }

    fun setObjectThread(id: Int, thread: Int) {
        val o = design.obj(id) ?: return
        if (thread !in design.threads.indices || o.threadIndex == thread) return
        commit(design.replaceObject(o.copy(threadIndex = thread))); changed()
    }

    fun setObjectParams(id: Int, p: StitchParams) {
        val o = design.obj(id) ?: return
        if (o.params == p) return
        commit(design.replaceObject(o.copy(params = p))); changed()
    }

    fun addThread(t: ThreadColor): Int {
        commit(design.addThread(t))
        changed()
        return design.threads.lastIndex
    }

    fun replaceThread(i: Int, t: ThreadColor) {
        if (i !in design.threads.indices) return
        commit(design.replaceThread(i, t)); changed()
    }

    fun addLayer(name: String) { commit(design.addLayer(name)); changed() }
    fun toggleLayerVisible(id: Int) {
        val l = design.layers.firstOrNull { it.id == id } ?: return
        commit(design.replaceLayer(l.copy(visible = !l.visible))); changed()
    }
    fun toggleLayerLocked(id: Int) {
        val l = design.layers.firstOrNull { it.id == id } ?: return
        commit(design.replaceLayer(l.copy(locked = !l.locked))); changed()
    }

    fun setHoop(h: com.topz.stitchdraw.embroidery.Hoop) { commit(design.copy(hoop = h)); changed() }

    // ---- history -------------------------------------------------------------------------------

    /** Undo removes the last pending point first, then steps back through committed edits. */
    fun undo() {
        if (pendingPoints.isNotEmpty()) { pendingPoints.removeAt(pendingPoints.lastIndex); changed(); return }
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(design)
        design = prev
        fixSelection()
        changed()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(design)
        design = next
        fixSelection()
        changed()
    }

    /**
     * Applies an automatic-digitizing result as ONE undoable step: new thread colours are appended to the palette,
     * a new "Auto" layer is created, and each region becomes a TATAMI object (with holes). Returns objects added.
     */
    fun applyAutoDigitize(result: com.topz.stitchdraw.digitizing.auto.AutoResult): Int {
        if (result.objects.isEmpty()) return 0
        var d = design
        val base = d.threads.size
        d = d.copy(threads = d.threads + result.threadRgb.map { ThreadColor(it and 0xFFFFFF) })
        val layerId = d.nextLayerId
        d = d.copy(layers = d.layers + Layer(layerId, "Auto"), nextLayerId = layerId + 1)
        var id = d.nextObjectId
        for ((i, o) in result.objects.withIndex()) {
            val satin = o.type == StitchType.SATIN
            d = d.addObject(DesignObject(
                id = id++, name = (if (satin) "Satin " else "Region ") + (i + 1), type = o.type,
                path = o.outline, threadIndex = base + o.colorIndex, layerId = layerId,
                params = if (satin) params.copy(widthMm = o.widthMm.coerceIn(StitchParams.MIN_WIDTH, StitchParams.MAX_WIDTH)) else params,
                holes = o.holes))
        }
        commit(d)
        pendingPoints.clear()
        selectedObjectId = null
        changed()
        return result.objects.size
    }

    /** Imports parsed SVG as one undoable step (new "SVG" layer, new thread colours). Returns objects added. */
    fun applySvg(svg: com.topz.stitchdraw.digitizing.auto.SvgResult, widthMm: Float): Int {
        var d = design
        val base = d.threads.size
        val layerId = d.nextLayerId
        val (objs, colors) = com.topz.stitchdraw.digitizing.auto.SvgImporter.toObjects(svg, widthMm, params, base, d.nextObjectId, layerId)
        if (objs.isEmpty()) return 0
        d = d.copy(threads = d.threads + colors.map { ThreadColor(it and 0xFFFFFF) },
            layers = d.layers + Layer(layerId, "SVG"), nextLayerId = layerId + 1)
        for (o in objs) d = d.addObject(o)
        commit(d); pendingPoints.clear(); selectedObjectId = null; changed()
        return objs.size
    }

    /**
     * Adds lettering as TATAMI objects in the active thread colour, one undoable step, on a new "Text" layer.
     * Returns the number of objects (one per letter outline). Throws IllegalArgumentException on bad input.
     */
    fun addText(text: String, heightMm: Float, source: com.topz.stitchdraw.digitizing.auto.GlyphSource,
                align: com.topz.stitchdraw.digitizing.auto.TextAlign = com.topz.stitchdraw.digitizing.auto.TextAlign.CENTER,
                fill: com.topz.stitchdraw.digitizing.auto.LetterFill = com.topz.stitchdraw.digitizing.auto.LetterFill.AUTO): Int {
        val lay = com.topz.stitchdraw.digitizing.auto.Lettering.layout(text, heightMm, source, align)
        var d = design
        val layerId = d.nextLayerId
        d = d.copy(layers = d.layers + Layer(layerId, "Text"), nextLayerId = layerId + 1)
        val thread = activeThread.coerceIn(0, d.threads.size - 1)
        val label = text.trim().replace('\n', ' ').take(12)
        val objs = com.topz.stitchdraw.digitizing.auto.Lettering.toObjects(lay, fill, params, thread, layerId, d.nextObjectId, label)
        for (o in objs) d = d.addObject(o)
        commit(d); pendingPoints.clear(); selectedObjectId = null; changed()
        return objs.size
    }

    /** Replaces the whole design (e.g. a project was opened). Clears history and pending points. */
    fun load(newDesign: EmbroideryDesign) {
        design = newDesign
        pendingPoints.clear(); undoStack.clear(); redoStack.clear()
        selectedObjectId = null
        activeThread = 0
        changed()
    }

    private fun commit(next: EmbroideryDesign) {
        undoStack.addLast(design)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        design = next
    }

    private fun fixSelection() {
        if (selectedObjectId != null && design.obj(selectedObjectId!!) == null) selectedObjectId = null
    }

    companion object {
        const val MIN_POINT_GAP_MM = 0.2f
        const val HISTORY_LIMIT = 100
    }
}
