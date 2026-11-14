package com.topz.stitchdraw.app

import com.topz.stitchdraw.vectorart.Doc
import com.topz.stitchdraw.vectorart.Render as VectorRender
import com.topz.stitchdraw.vectorart.VObj
import com.topz.stitchdraw.vectorart.Kind as VectorKind
import com.topz.stitchdraw.vectorart.Node as VectorNode
import com.topz.stitchdraw.vectorart.Geo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.rendering.DesignRenderer
import com.topz.stitchdraw.rendering.ThreadSimRenderer
import com.topz.stitchdraw.simulation.Playback
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import com.topz.stitchdraw.digitizing.ObjectTransform
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Central embroidery canvas: hoop, grid, rulers, stitches, in-progress path.
 *
 * Touch model:
 *  - pinch = zoom, two-finger drag = pan, one-finger drag = pan
 *  - tap with a drawing tool = add point; tap with Select = select object / open canvas menu
 *  - long press = context menu (object or canvas)
 *  - optional stylus-only drawing: finger never adds points, only navigates
 *
 * The view reads the [DigitizingSession] but never mutates workspace state; layout changes
 * only resize this view.
 */
class DesignCanvasView(context: Context, private val session: DigitizingSession) : View(context) {

    interface Callbacks {
        fun onObjectLongPress(objectId: Int, screenX: Int, screenY: Int)
        fun onCanvasMenu(screenX: Int, screenY: Int)
    }

    var callbacks: Callbacks? = null
    var stylusOnlyDrawing = false
    var showGrid = true
    var showRulers = true
    var showHoop = true

    private val renderer = DesignRenderer()
    private val simRenderer = ThreadSimRenderer()

    /** Optional editable VecDraw document rendered in the same millimetre coordinate space. */
    var vectorDocument: Doc? = null
        set(value) { field = value; invalidate() }
    var showVectorArtwork: Boolean = true
        set(value) { field = value; invalidate() }

    enum class VectorTool { OFF, SELECT, NODE, RECTANGLE, ELLIPSE, POLYGON, FREEHAND }
    var vectorTool: VectorTool = VectorTool.OFF
        set(value) { field = value; vectorDraft.clear(); invalidate() }
    var selectedVectorUid: Int? = null
        private set
    private val vectorDraft = mutableListOf<Vec2>()
    private var vectorDown = Vec2.ZERO
    private var vectorDragging = false
    private var vectorNodeIndex = -1
    private var vectorSelectedDown = Vec2.ZERO
    private var vectorOriginalNodes = emptyList<VectorNode>()
    private val vectorSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF38D9FF.toInt(); strokeWidth = 0.45f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(1.8f, 1.2f), 0f) }
    private val vectorHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt(); style = Paint.Style.FILL }
    private val vectorDraftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38D9FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.55f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 1.5f), 0f) }
    var onVectorChanged: (() -> Unit)? = null


    /** Non-null while the simulator is open: the canvas shows the sewn result up to the playback position. */
    var playback: Playback? = null
        set(v) { field = v; invalidate() }
    val simulating: Boolean get() = playback != null
    val sim: ThreadSimRenderer get() = simRenderer

    // View transform: screen = origin + design(mm) * scale
    private var scale = 4f              // px per mm
    private var originX = 0f
    private var originY = 0f
    private var viewportInitialised = false

    private val rulerSize = dp(22f)
    private val hitSlop = dp(10f)

    private val gridPaint = Paint().apply { color = 0xFF2A2A31.toInt(); strokeWidth = 1f }
    private val gridMajorPaint = Paint().apply { color = 0xFF3A3A44.toInt(); strokeWidth = 1.5f }
    private val hoopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.WARN; strokeWidth = 2f
    }
    private val rulerBg = Paint().apply { color = 0xFF222228.toInt() }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT_DIM; textSize = sp(10f) }
    private val rulerTick = Paint().apply { color = Theme.TEXT_DIM; strokeWidth = 1f }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.ACCENT; strokeWidth = dp(2f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.OK; strokeWidth = dp(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.OK }
    private val path = Path()

    private val sessionListener = DigitizingSession.Listener { postInvalidateOnAnimation() }

    init {
        setBackgroundColor(Theme.CANVAS)
        isClickable = true
        isLongClickable = true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); session.addListener(sessionListener) }
    override fun onDetachedFromWindow() { session.removeListener(sessionListener); super.onDetachedFromWindow() }

    // ---- view operations (used by menus / quick toolbar) --------------------------------------

    fun zoomBy(factor: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val k = newScale / scale
        originX = focusX - (focusX - originX) * k
        originY = focusY - (focusY - originY) * k
        scale = newScale
        invalidate()
    }

    fun fitDesign() {
        if (width == 0 || height == 0) return
        val b = session.plan.stats.bounds
        val hoop = session.design.hoop
        val w: Float
        val h: Float
        val cx: Float
        val cy: Float
        if (b.isEmpty) { w = hoop.widthMm; h = hoop.heightMm; cx = 0f; cy = 0f }
        else {
            w = max(b.width, hoop.widthMm * (if (showHoop) 1f else 0f)).coerceAtLeast(10f)
            h = max(b.height, hoop.heightMm * (if (showHoop) 1f else 0f)).coerceAtLeast(10f)
            cx = if (showHoop) 0f else b.centerX; cy = if (showHoop) 0f else b.centerY
        }
        val availW = width - (if (showRulers) rulerSize else 0f) - dp(24f)
        val availH = height - (if (showRulers) rulerSize else 0f) - dp(24f)
        scale = min(availW / w, availH / h).coerceIn(MIN_SCALE, MAX_SCALE)
        centerOn(cx, cy)
        invalidate()
    }

    private fun centerOn(mmX: Float, mmY: Float) {
        val ox = if (showRulers) rulerSize else 0f
        originX = ox + (width - ox) / 2f - mmX * scale
        originY = ox + (height - ox) / 2f - mmY * scale
    }

    fun toDesign(px: Float, py: Float) = Vec2((px - originX) / scale, (py - originY) / scale)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!viewportInitialised && w > 0 && h > 0) { viewportInitialised = true; fitDesign() }
        else if (oldw > 0 && oldh > 0) {
            // Keep the same design point at the view centre when panels change the canvas size.
            val mmX = (oldw / 2f - originX) / scale
            val mmY = (oldh / 2f - originY) / scale
            originX = w / 2f - mmX * scale
            originY = h / 2f - mmY * scale
        }
    }

    // ---- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val d = session.design
        if (showGrid) drawGrid(canvas)

        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        if (showHoop) {
            hoopPaint.strokeWidth = 2f / scale
            canvas.drawRect(-d.hoop.widthMm / 2f, -d.hoop.heightMm / 2f, d.hoop.widthMm / 2f, d.hoop.heightMm / 2f, hoopPaint)
            canvas.drawLine(-3f, 0f, 3f, 0f, hoopPaint); canvas.drawLine(0f, -3f, 0f, 3f, hoopPaint)
        }
        if (showVectorArtwork) vectorDocument?.let { doc ->
            canvas.save()
            canvas.translate(-doc.w / 2f, -doc.h / 2f)
            VectorRender.drawAll(canvas, doc)
            selectedVectorUid?.let { uid ->
                doc.objs.firstOrNull { it.uid == uid }?.let { obj ->
                    canvas.drawPath(obj.path(), vectorSelectionPaint)
                    if (vectorTool == VectorTool.NODE) obj.nodes.forEach { n -> canvas.drawCircle(n.x, n.y, 1.35f, vectorHandlePaint) }
                }
            }
            if (vectorDraft.isNotEmpty()) {
                val draftPath = Path()
                if (vectorDraft.size >= 2 && vectorTool in setOf(VectorTool.RECTANGLE, VectorTool.ELLIPSE, VectorTool.POLYGON)) {
                    val a = vectorDraft.first(); val b = vectorDraft.last()
                    val l = min(a.x, b.x); val r = max(a.x, b.x); val t = min(a.y, b.y); val bot = max(a.y, b.y)
                    when (vectorTool) {
                        VectorTool.RECTANGLE -> draftPath.addRect(l, t, r, bot, Path.Direction.CW)
                        VectorTool.ELLIPSE -> draftPath.addOval(l, t, r, bot, Path.Direction.CW)
                        VectorTool.POLYGON -> {
                            val pts = Geo.polygon((l+r)/2f, (t+bot)/2f, hypot(r-l, bot-t)/2f, -Math.PI.toFloat()/2f, 6)
                            pts.forEachIndexed { i, n -> if (i == 0) draftPath.moveTo(n.x, n.y) else draftPath.lineTo(n.x, n.y) }; draftPath.close()
                        }
                        else -> Unit
                    }
                } else {
                    vectorDraft.forEachIndexed { i, p -> if (i == 0) draftPath.moveTo(p.x, p.y) else draftPath.lineTo(p.x, p.y) }
                }
                canvas.drawPath(draftPath, vectorDraftPaint)
            }
            canvas.restore()
        }
        val pb = playback
        if (pb != null) {
            simRenderer.drawFabric(canvas, -d.hoop.widthMm / 2f, -d.hoop.heightMm / 2f, d.hoop.widthMm / 2f, d.hoop.heightMm / 2f, scale)
            simRenderer.draw(canvas, session.plan, scale, pb.shown, showNeedle = !pb.finished)
        } else {
            renderer.draw(canvas, session.plan, scale)
        }

        if (pb == null) session.selectedObjectId?.let { id ->
            d.obj(id)?.let { o ->
                path.reset()
                o.path.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                selectPaint.strokeWidth = dp(1.5f) / scale
                canvas.drawPath(path, selectPaint)
            }
        }
        canvas.restore()

        // Touch-friendly node handles for the selected object / in-progress path (screen space).
        if (pb == null) session.selectedObjectId?.let { id ->
            session.design.obj(id)?.path?.forEach { p ->
                canvas.drawCircle(originX + p.x * scale, originY + p.y * scale, dp(5f), handlePaint)
            }
        }
        if (pb == null && !session.tool.isDrawing) session.selectedBounds()?.let { (lo, hi) ->
            val l = originX + lo.x * scale; val t = originY + lo.y * scale; val r = originX + hi.x * scale; val b = originY + hi.y * scale
            canvas.drawRect(l, t, r, b, boxPaint)
            handles()?.let { (sc, ro) ->
                canvas.drawLine((l + r) / 2, t, ro[0], ro[1], boxPaint)
                canvas.drawRect(sc[0] - dp(7f), sc[1] - dp(7f), sc[0] + dp(7f), sc[1] + dp(7f), handlePaint)
                canvas.drawCircle(ro[0], ro[1], dp(8f), handlePaint)
            }
        }
        val pend = session.pending
        if (pb == null && pend.isNotEmpty()) {
            path.reset()
            val shape = session.pendingPath
            val closeIt = session.tool.stitchType?.isFill == true && shape.size >= 3
            shape.forEachIndexed { i, p ->
                val sx = originX + p.x * scale; val sy = originY + p.y * scale
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            if (closeIt) path.close()
            canvas.drawPath(path, pendingPaint)
            pend.forEach { p -> canvas.drawCircle(originX + p.x * scale, originY + p.y * scale, dp(6f), pointPaint) }
        }
        if (showRulers) drawRulers(canvas)
    }

    private fun gridStepMm(): Float {
        // pick 1, 5, 10, 50 mm so lines stay >= ~16 px apart
        for (s in floatArrayOf(1f, 5f, 10f, 50f, 100f)) if (s * scale >= dp(16f)) return s
        return 100f
    }

    private fun drawGrid(c: Canvas) {
        val step = gridStepMm()
        val left = (0f - originX) / scale
        val right = (width - originX) / scale
        val top = (0f - originY) / scale
        val bottom = (height - originY) / scale
        var x = floor(left / step) * step
        while (x <= right) {
            val sx = originX + x * scale
            c.drawLine(sx, 0f, sx, height.toFloat(), if (abs(x % 10f) < 1e-3f) gridMajorPaint else gridPaint)
            x += step
        }
        var y = floor(top / step) * step
        while (y <= bottom) {
            val sy = originY + y * scale
            c.drawLine(0f, sy, width.toFloat(), sy, if (abs(y % 10f) < 1e-3f) gridMajorPaint else gridPaint)
            y += step
        }
    }

    private fun drawRulers(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), rulerSize, rulerBg)
        c.drawRect(0f, 0f, rulerSize, height.toFloat(), rulerBg)
        val tick = gridStepMm()
        var label = tick.toInt()
        for (cand in intArrayOf(1, 5, 10, 50, 100, 500)) { if (cand >= tick.toInt()) { label = cand; if (cand * scale >= dp(44f)) break } }
        val tickI = tick.toInt().coerceAtLeast(1)

        val kx0 = Math.ceil(((rulerSize - originX) / scale / tickI).toDouble()).toInt()
        val kx1 = Math.floor(((width - originX) / scale / tickI).toDouble()).toInt()
        for (k in kx0..kx1) {
            val mm = k * tickI
            val sx = originX + mm * scale
            val major = mm % label == 0
            c.drawLine(sx, rulerSize - (if (major) dp(10f) else dp(5f)), sx, rulerSize, rulerTick)
            if (major) c.drawText(mm.toString(), sx + 2f, rulerSize - dp(11f), rulerText)
        }
        val ky0 = Math.ceil(((rulerSize - originY) / scale / tickI).toDouble()).toInt()
        val ky1 = Math.floor(((height - originY) / scale / tickI).toDouble()).toInt()
        for (k in ky0..ky1) {
            val mm = k * tickI
            val sy = originY + mm * scale
            val major = mm % label == 0
            c.drawLine(rulerSize - (if (major) dp(10f) else dp(5f)), sy, rulerSize, sy, rulerTick)
            if (major) {
                c.save(); c.rotate(-90f, rulerSize - dp(11f), sy - 2f)
                c.drawText(mm.toString(), rulerSize - dp(11f), sy - 2f, rulerText); c.restore()
            }
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var multiTouch = false
    private var downToolType = MotionEvent.TOOL_TYPE_FINGER
    private var longPressFired = false

    // ---- object transform gestures (Select tool): drag body = move, corner handle = scale, top handle = rotate ----
    private enum class TMode { NONE, MOVE, SCALE, ROTATE }
    private var tMode = TMode.NONE
    private var tCenter = Vec2.ZERO
    private var tDown = Vec2.ZERO
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.ACCENT; strokeWidth = dp(1.2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }

    /** Screen positions of the scale handle (bottom-right of the box) and rotate handle (above the top edge). */
    private fun handles(): Pair<FloatArray, FloatArray>? {
        val (lo, hi) = session.selectedBounds() ?: return null
        val sx = originX + hi.x * scale; val sy = originY + hi.y * scale
        val rx = originX + (lo.x + hi.x) / 2 * scale; val ry = originY + lo.y * scale - dp(28f)
        return floatArrayOf(sx, sy) to floatArrayOf(rx, ry)
    }

    private fun pickTransformMode(x: Float, y: Float) {
        tMode = TMode.NONE
        if (playback != null || session.tool.isDrawing || session.selectedObjectId == null) return
        val h = handles() ?: return
        val r = dp(24f)
        when {
            hypot(x - h.second[0], y - h.second[1]) <= r -> tMode = TMode.ROTATE
            hypot(x - h.first[0], y - h.first[1]) <= r -> tMode = TMode.SCALE
            else -> {
                val hit = session.hitTest(toDesign(x, y), hitSlop / scale)
                if (hit != null && hit.id == session.selectedObjectId) tMode = TMode.MOVE
            }
        }
        if (tMode != TMode.NONE) {
            val (lo, hi) = session.selectedBounds() ?: run { tMode = TMode.NONE; return }
            tCenter = Vec2((lo.x + hi.x) / 2, (lo.y + hi.y) / 2)
            tDown = toDesign(x, y)
        }
    }

    private fun updateTransform(x: Float, y: Float) {
        val p = toDesign(x, y)
        val t = when (tMode) {
            TMode.MOVE -> ObjectTransform(dxMm = p.x - tDown.x, dyMm = p.y - tDown.y)
            TMode.SCALE -> {
                val d0 = hypot(tDown.x - tCenter.x, tDown.y - tCenter.y).coerceAtLeast(0.1f)
                ObjectTransform(scale = hypot(p.x - tCenter.x, p.y - tCenter.y) / d0)
            }
            TMode.ROTATE -> {
                var a = Math.toDegrees((atan2(p.y - tCenter.y, p.x - tCenter.x) - atan2(tDown.y - tCenter.y, tDown.x - tCenter.x)).toDouble()).toFloat()
                val snap = Math.round(a / 15f) * 15f
                if (abs(a - snap) < 3f) a = snap                  // gentle snap to 15 degree steps
                ObjectTransform(rotateDeg = a)
            }
            TMode.NONE -> return
        }
        session.previewTransform(t)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (multiTouch || dragging || playback != null || vectorTool != VectorTool.OFF) return
            longPressFired = true
            val p = toDesign(e.x, e.y)
            val hit = session.hitTest(p, hitSlop / scale)
            val loc = IntArray(2); getLocationOnScreen(loc)
            val sx = loc[0] + e.x.toInt(); val sy = loc[1] + e.y.toInt()
            if (hit != null) { session.select(hit.id); callbacks?.onObjectLongPress(hit.id, sx, sy) }
            else callbacks?.onCanvasMenu(sx, sy)
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (vectorTool != VectorTool.OFF && playback == null) return handleVectorTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                dragging = false; multiTouch = false; longPressFired = false
                downToolType = e.getToolType(0)
                pickTransformMode(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (session.isTransforming) session.cancelTransform(); tMode = TMode.NONE; multiTouch = true; lastX = centroidX(e); lastY = centroidY(e) }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val cx = centroidX(e); val cy = centroidY(e)
                    originX += cx - lastX; originY += cy - lastY
                    lastX = cx; lastY = cy
                    invalidate()
                } else if (!multiTouch) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > dp(8f)) dragging = true
                    if (dragging && tMode != TMode.NONE) {
                        if (!session.isTransforming && !session.beginTransform()) tMode = TMode.NONE
                        else updateTransform(e.x, e.y)
                    } else if (dragging && !longPressFired) {
                        originX += e.x - lastX; originY += e.y - lastY
                        invalidate()
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // continue panning with the remaining finger without a jump
                val idx = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(idx); lastY = e.getY(idx)
                if (e.pointerCount <= 2) dragging = true
            }
            MotionEvent.ACTION_CANCEL -> { if (session.isTransforming) session.cancelTransform(); tMode = TMode.NONE }
            MotionEvent.ACTION_UP -> {
                if (session.isTransforming) { session.endTransform(); tMode = TMode.NONE; return true }
                tMode = TMode.NONE
                if (!dragging && !multiTouch && !longPressFired) onTap(e.x, e.y, downToolType)
            }
        }
        return true
    }

    private fun handleVectorTouch(e: MotionEvent): Boolean {
        val doc = vectorDocument ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                vectorDown = toVectorDoc(e.x, e.y, doc)
                vectorDragging = false
                vectorDraft.clear()
                vectorNodeIndex = -1
                when (vectorTool) {
                    VectorTool.SELECT -> {
                        val hit = hitVector(vectorDown, doc)
                        selectedVectorUid = hit?.uid
                        if (hit != null && !isVectorLocked(hit, doc)) {
                            vectorOriginalNodes = hit.nodes.map { it.copy() }
                            vectorSelectedDown = vectorDown
                        }
                        invalidate()
                    }
                    VectorTool.NODE -> {
                        val obj = selectedVectorUid?.let { id -> doc.objs.firstOrNull { it.uid == id } }
                        if (obj != null && !isVectorLocked(obj, doc)) {
                            vectorNodeIndex = obj.nodes.indices.minByOrNull { i ->
                                val n = obj.nodes[i]; hypot(n.x - vectorDown.x, n.y - vectorDown.y)
                            } ?: -1
                            if (vectorNodeIndex >= 0 && hypot(obj.nodes[vectorNodeIndex].x - vectorDown.x, obj.nodes[vectorNodeIndex].y - vectorDown.y) > 5f) vectorNodeIndex = -1
                        }
                    }
                    VectorTool.FREEHAND -> vectorDraft.add(vectorDown)
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { vectorDraft.clear(); return true }
            MotionEvent.ACTION_MOVE -> {
                val p = toVectorDoc(e.x, e.y, doc)
                if (hypot(e.x - downX, e.y - downY) > dp(5f)) vectorDragging = true
                when (vectorTool) {
                    VectorTool.SELECT -> {
                        val obj = selectedVectorUid?.let { id -> doc.objs.firstOrNull { it.uid == id } }
                        if (vectorDragging && obj != null && vectorOriginalNodes.isNotEmpty() && !isVectorLocked(obj, doc)) {
                            val dx = p.x - vectorSelectedDown.x; val dy = p.y - vectorSelectedDown.y
                            obj.nodes.forEachIndexed { i, n -> vectorOriginalNodes.getOrNull(i)?.let { n.x = it.x + dx; n.y = it.y + dy; n.ix = it.ix + dx; n.iy = it.iy + dy; n.ox = it.ox + dx; n.oy = it.oy + dy } }
                            invalidate()
                        }
                    }
                    VectorTool.NODE -> {
                        val obj = selectedVectorUid?.let { id -> doc.objs.firstOrNull { it.uid == id } }
                        if (vectorNodeIndex >= 0 && obj != null && !isVectorLocked(obj, doc)) {
                            val n = obj.nodes[vectorNodeIndex]; val dx = p.x - n.x; val dy = p.y - n.y
                            n.x = p.x; n.y = p.y
                            if (n.smooth) { n.ix += dx; n.iy += dy; n.ox += dx; n.oy += dy }
                            invalidate()
                        }
                    }
                    VectorTool.FREEHAND -> { if (vectorDraft.isEmpty() || hypot(p.x - vectorDraft.last().x, p.y - vectorDraft.last().y) > 0.7f) vectorDraft.add(p); invalidate() }
                    VectorTool.RECTANGLE, VectorTool.ELLIPSE, VectorTool.POLYGON -> { vectorDraft.clear(); vectorDraft.add(vectorDown); vectorDraft.add(p); invalidate() }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = toVectorDoc(e.x, e.y, doc)
                when (vectorTool) {
                    VectorTool.RECTANGLE, VectorTool.ELLIPSE, VectorTool.POLYGON -> if (vectorDragging) createVectorShape(doc, vectorTool, vectorDown, p)
                    VectorTool.FREEHAND -> if (vectorDraft.size >= 2) createFreehand(doc)
                    VectorTool.SELECT -> if (vectorDragging) onVectorChanged?.invoke()
                    VectorTool.NODE -> if (vectorNodeIndex >= 0) onVectorChanged?.invoke()
                    else -> Unit
                }
                vectorDraft.clear(); vectorOriginalNodes = emptyList(); vectorNodeIndex = -1
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { vectorDraft.clear(); vectorOriginalNodes = emptyList(); vectorNodeIndex = -1; invalidate(); return true }
        }
        return true
    }

    private fun toVectorDoc(x: Float, y: Float, doc: Doc): Vec2 {
        val p = toDesign(x, y)
        return Vec2(p.x + doc.w / 2f, p.y + doc.h / 2f)
    }

    private fun isVectorLocked(obj: VObj, doc: Doc) = obj.locked || doc.layers.getOrNull(obj.layer)?.locked == true || doc.layers.getOrNull(obj.layer)?.visible == false

    private fun hitVector(p: Vec2, doc: Doc): VObj? {
        return doc.objs.asReversed().firstOrNull { obj ->
            if (!obj.visible || isVectorLocked(obj, doc)) false else {
                val b = RectF(); obj.path().computeBounds(b, true)
                b.contains(p.x, p.y) || obj.nodes.any { hypot(it.x - p.x, it.y - p.y) < 4f }
            }
        }
    }

    private fun createVectorShape(doc: Doc, tool: VectorTool, a: Vec2, b: Vec2) {
        val l = min(a.x, b.x); val r = max(a.x, b.x); val t = min(a.y, b.y); val bot = max(a.y, b.y)
        if (r - l < 0.8f || bot - t < 0.8f) return
        val nodes = when (tool) {
            VectorTool.RECTANGLE -> mutableListOf(VectorNode(l,t), VectorNode(r,t), VectorNode(r,bot), VectorNode(l,bot))
            VectorTool.ELLIPSE -> Geo.ellipse((l+r)/2f, (t+bot)/2f, (r-l)/2f, 0f, 0f, (bot-t)/2f)
            else -> Geo.polygon((l+r)/2f, (t+bot)/2f, hypot(r-l, bot-t)/2f, -Math.PI.toFloat()/2f, 6)
        }
        addVectorObject(doc, nodes, tool != VectorTool.POLYGON, "${tool.name.lowercase().replaceFirstChar { it.uppercase() }} ${doc.objs.size + 1}")
    }

    private fun createFreehand(doc: Doc) {
        val pts = vectorDraft.map { android.graphics.PointF(it.x, it.y) }
        val simplified = Geo.rdp(pts, 0.45f)
        if (simplified.size < 2) return
        val nodes = Geo.smooth(simplified, false)
        addVectorObject(doc, nodes, false, "Freehand ${doc.objs.size + 1}")
    }

    private fun addVectorObject(doc: Doc, nodes: MutableList<VectorNode>, closed: Boolean, name: String) {
        val layer = doc.layers.indexOfLast { it.visible && !it.locked }
        if (layer < 0) return
        val obj = VObj(VectorKind.PATH).apply {
            this.name = name; this.nodes = nodes; this.closed = closed
            fill = if (closed) 0x332D8CFF else null; stroke = 0xFFFFC857.toInt(); strokeW = 0.45f; this.layer = layer
            uid = (doc.objs.maxOfOrNull { it.uid } ?: 0) + 1
        }
        doc.objs.add(obj); selectedVectorUid = obj.uid; onVectorChanged?.invoke(); invalidate()
    }

    fun deleteSelectedVector(): Boolean {
        val doc = vectorDocument ?: return false
        val id = selectedVectorUid ?: return false
        val obj = doc.objs.firstOrNull { it.uid == id } ?: return false
        if (isVectorLocked(obj, doc)) return false
        doc.objs.remove(obj); selectedVectorUid = null; onVectorChanged?.invoke(); invalidate(); return true
    }

    private fun centroidX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun centroidY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun onTap(x: Float, y: Float, toolType: Int) {
        if (showRulers && (x < rulerSize || y < rulerSize)) return
        val p = toDesign(x, y)
        if (session.tool.isDrawing) {
            val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
            if (playback != null) return                  // simulator is view-only
            if (stylusOnlyDrawing && !isStylus) return   // finger only navigates in stylus mode
            session.addPoint(p)
        } else {
            if (playback != null) return
            val hit = session.hitTest(p, hitSlop / scale)
            session.select(hit?.id)
            if (hit == null) {
                val loc = IntArray(2); getLocationOnScreen(loc)
                callbacks?.onCanvasMenu(loc[0] + x.toInt(), loc[1] + y.toInt())
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 120f
    }
}
