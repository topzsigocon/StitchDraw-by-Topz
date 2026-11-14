package com.topz.stitchdraw.vectorart

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.Base64
import java.io.OutputStream
import java.util.Locale
import kotlin.math.*

object Render {
    val CAPS = arrayOf(Paint.Cap.ROUND, Paint.Cap.SQUARE, Paint.Cap.BUTT)
    val JOINS = arrayOf(Paint.Join.ROUND, Paint.Join.BEVEL, Paint.Join.MITER)

    fun dashArr(o: VObj): FloatArray? {
        val w = max(o.strokeW, 0.2f)
        return when (o.dash) { 1 -> floatArrayOf(4 * w, 2 * w); 2 -> floatArrayOf(w, 2 * w); 3 -> floatArrayOf(4 * w, 2 * w, w, 2 * w); else -> null }
    }

    /** Gradient / fountain axis through the centre of the shape's own (rotated) frame. */
    fun framePts(o: VObj, angle: Float): FloatArray {
        val f = frameOf(o); val c = f.center(); val r = max(f.b.width(), f.b.height()) / 2f; val a = Math.toRadians((angle + o.fRot).toDouble())
        val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
        return floatArrayOf(c.x - dx, c.y - dy, c.x + dx, c.y + dy)
    }

    fun gradPts(o: VObj): FloatArray = framePts(o, o.gradAngle)

    private fun a255(o: VObj, la: Float) = (255 * o.alpha * la).toInt().coerceIn(0, 255)

    fun fillPaint(o: VObj, la: Float): Paint? {
        val f = o.fill ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = f
            val f2 = o.fill2
            if (f2 != null) {
                val g = gradPts(o); val mid = o.fillMid
                val cols = if (mid != null) intArrayOf(f, mid, f2) else intArrayOf(f, f2)
                val pos = if (mid != null) floatArrayOf(0f, 0.5f, 1f) else floatArrayOf(0f, 1f)
                shader = if (o.gradType == 1) RadialGradient((g[0] + g[2]) / 2, (g[1] + g[3]) / 2, max(hypot(g[2] - g[0], g[3] - g[1]) / 2, 0.01f), cols, pos, Shader.TileMode.CLAMP)
                else LinearGradient(g[0], g[1], g[2], g[3], cols, pos, Shader.TileMode.CLAMP)
            }
            alpha = a255(o, la)
        }
    }

    fun strokePaint(o: VObj, la: Float): Paint? {
        val s = o.stroke ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = s; strokeWidth = o.strokeW
            strokeCap = CAPS[o.cap]; strokeJoin = JOINS[o.join]; strokeMiter = 4f
            dashArr(o)?.let { pathEffect = DashPathEffect(it, 0f) }
            alpha = a255(o, la)
        }
    }

    /** Arrowhead triangle (tip, left, right) at the end of an open path. */
    fun arrowPts(o: VObj): FloatArray? {
        if (!o.arrow || o.closed || o.nodes.size < 2) return null
        val e = o.nodes.last(); val pv = o.nodes[o.nodes.size - 2]
        val tailX: Float; val tailY: Float
        if (!e.inLine()) { tailX = e.ix; tailY = e.iy } else if (!pv.outLine()) { tailX = pv.ox; tailY = pv.oy } else { tailX = pv.x; tailY = pv.y }
        var dx = e.x - tailX; var dy = e.y - tailY; val l = hypot(dx, dy); if (l < 1e-4f) return null
        dx /= l; dy /= l
        val s = 3f + o.strokeW * 3f; val bx = e.x - dx * s; val by = e.y - dy * s; val nx = -dy; val ny = dx
        return floatArrayOf(e.x, e.y, bx + nx * s * .45f, by + ny * s * .45f, bx - nx * s * .45f, by - ny * s * .45f)
    }

    const val MESH_SUB = 8   // flat sub-cells per mesh cell, per axis

    /** One flat-coloured quad: p = x0,y0 ... x3,y3 (clockwise from top-left). */
    class MeshCell(val p: FloatArray, val color: Int)

    fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Bilinear colour at normalised (u,v) of an r x c vertex grid. */
    fun meshAt(m: IntArray, r: Int, c: Int, u: Float, v: Float): Int {
        val fx = u.coerceIn(0f, 1f) * (c - 1); val fy = v.coerceIn(0f, 1f) * (r - 1)
        val j = min(fx.toInt(), c - 2); val i = min(fy.toInt(), r - 2); val tx = fx - j; val ty = fy - i
        return mix(mix(m[i * c + j], m[i * c + j + 1], tx), mix(m[(i + 1) * c + j], m[(i + 1) * c + j + 1], tx), ty)
    }

    /** Local frame of a shape: bounds measured after undoing its accumulated rotation, so mesh / pattern / gradient turn with it. */
    class Frame(val b: RectF, val px: Float, val py: Float, val rot: Float) {
        private val cs = cos(Math.toRadians(rot.toDouble())).toFloat(); private val sn = sin(Math.toRadians(rot.toDouble())).toFloat()
        fun toDoc(x: Float, y: Float) = PointF(px + (x - px) * cs - (y - py) * sn, py + (x - px) * sn + (y - py) * cs)
        fun toLocal(x: Float, y: Float) = PointF(px + (x - px) * cs + (y - py) * sn, py - (x - px) * sn + (y - py) * cs)
        fun vecToDoc(x: Float, y: Float) = PointF(x * cs - y * sn, x * sn + y * cs)
        fun vecToLocal(x: Float, y: Float) = PointF(x * cs + y * sn, -x * sn + y * cs)
        fun center() = toDoc(b.centerX(), b.centerY())
    }

    fun frameOf(o: VObj): Frame {
        val ab = o.bounds(); val px = ab.centerX(); val py = ab.centerY(); val r = o.fRot
        if (r == 0f || o.kind != Kind.PATH) return Frame(ab, px, py, 0f)
        val p = o.path(); p.transform(Matrix().apply { setRotate(-r, px, py) })
        val lb = RectF(); p.computeBounds(lb, true)
        return Frame(lb, px, py, r)
    }

    /** Vertex position in document space (regular grid in the shape's frame unless the user moved points). */
    fun meshVertex(o: VObj, idx: Int, f: Frame): PointF {
        val pos = o.meshPos; val c = o.meshC; val r = o.meshR
        val u = if (pos != null && pos.size >= (idx + 1) * 2) pos[idx * 2] else (idx % c) / (c - 1f)
        val v = if (pos != null && pos.size >= (idx + 1) * 2) pos[idx * 2 + 1] else (idx / c) / (r - 1f)
        return f.toDoc(f.b.left + f.b.width() * u, f.b.top + f.b.height() * v)
    }

    /** Control point at vertex a of the cubic edge towards neighbour b: 1/3 along the edge plus the stored handle offset. */
    fun meshCtrl(o: VObj, a: Int, b: Int, dir: Int, f: Frame): PointF {
        val pa = meshVertex(o, a, f); val pb = meshVertex(o, b, f); val h = o.meshH
        var d = PointF(0f, 0f)
        if (h != null && h.size >= (a + 1) * 8) d = f.vecToDoc(h[a * 8 + dir * 2] * f.b.width(), h[a * 8 + dir * 2 + 1] * f.b.height())
        return PointF(pa.x + (pb.x - pa.x) / 3 + d.x, pa.y + (pb.y - pa.y) / 3 + d.y)
    }

    private fun edgePts(o: VObj, a: Int, b: Int, da: Int, db: Int, f: Frame, n: Int): Array<PointF> {
        val p0 = meshVertex(o, a, f); val p3 = meshVertex(o, b, f); val p1 = meshCtrl(o, a, b, da, f); val p2 = meshCtrl(o, b, a, db, f)
        return Array(n + 1) { k ->
            val t = k / n.toFloat(); val u = 1 - t
            PointF(u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
                u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y)
        }
    }

    /**
     * Flat-coloured sub-quads approximating the smooth mesh. Each cell is a Coons patch bounded by the four cubic
     * edges (so curved edges are shared exactly by neighbours); stays 100% vector in screen, SVG and PDF.
     */
    fun meshCells(o: VObj): List<MeshCell> {
        val m = o.mesh ?: return emptyList(); val r = o.meshR; val c = o.meshC
        if (r < 2 || c < 2 || m.size < r * c) return emptyList()
        val f = frameOf(o); val n = MESH_SUB; val out = ArrayList<MeshCell>((r - 1) * (c - 1) * n * n)
        for (i in 0 until r - 1) for (j in 0 until c - 1) {
            val i00 = i * c + j; val i01 = i00 + 1; val i10 = i00 + c; val i11 = i10 + 1
            val p00 = meshVertex(o, i00, f); val p01 = meshVertex(o, i01, f); val p10 = meshVertex(o, i10, f); val p11 = meshVertex(o, i11, f)
            val top = edgePts(o, i00, i01, 1, 0, f, n); val bot = edgePts(o, i10, i11, 1, 0, f, n)
            val left = edgePts(o, i00, i10, 3, 2, f, n); val right = edgePts(o, i01, i11, 3, 2, f, n)
            val gx = Array(n + 1) { FloatArray(n + 1) }; val gy = Array(n + 1) { FloatArray(n + 1) }
            for (iv in 0..n) for (iu in 0..n) {
                val u = iu / n.toFloat(); val v = iv / n.toFloat()
                gx[iv][iu] = (1 - v) * top[iu].x + v * bot[iu].x + (1 - u) * left[iv].x + u * right[iv].x -
                    ((1 - u) * (1 - v) * p00.x + u * (1 - v) * p01.x + (1 - u) * v * p10.x + u * v * p11.x)
                gy[iv][iu] = (1 - v) * top[iu].y + v * bot[iu].y + (1 - u) * left[iv].y + u * right[iv].y -
                    ((1 - u) * (1 - v) * p00.y + u * (1 - v) * p01.y + (1 - u) * v * p10.y + u * v * p11.y)
            }
            for (sy in 0 until n) for (sx in 0 until n) {
                val uc = (sx + 0.5f) / n; val vc = (sy + 0.5f) / n
                val col = mix(mix(m[i00], m[i01], uc), mix(m[i10], m[i11], uc), vc)
                out.add(MeshCell(floatArrayOf(gx[sy][sx], gy[sy][sx], gx[sy][sx + 1], gy[sy][sx + 1], gx[sy + 1][sx + 1], gy[sy + 1][sx + 1], gx[sy + 1][sx], gy[sy + 1][sx]), col))
            }
        }
        return out
    }

    private fun drawMesh(c: Canvas, o: VObj, la: Float, p: Path) {
        val cells = meshCells(o); if (cells.isEmpty()) return
        val a = a255(o, la)
        val saved = if (a < 255) c.saveLayerAlpha(null, a) else c.save()
        c.clipPath(p)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.15f }   // stroke hides seams
        val q = Path()
        for (cell in cells) {
            val v = cell.p; q.rewind(); q.moveTo(v[0], v[1]); q.lineTo(v[2], v[3]); q.lineTo(v[4], v[5]); q.lineTo(v[6], v[7]); q.close()
            pt.color = cell.color; c.drawPath(q, pt)
        }
        c.restoreToCount(saved)
    }

    /** PDF export sets this so masked objects are baked into 300-dpi bitmaps (the PDF backend can't do DST_IN masking). */
    var rasterMasks = false

    private val LUM_TO_ALPHA = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f)))

    private fun maskedLayer(c: Canvas, o: VObj, la: Float, b: RectF) {
        val saved = c.saveLayer(b, null)
        drawBase(c, o, la)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); isFilterBitmap = true }
        if (o.tMode == 1) {
            val g = framePts(o, o.tAngle)
            mp.shader = LinearGradient(g[0], g[1], g[2], g[3],
                Color.argb((o.tA1.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0), Color.argb((o.tA2.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
        } else {
            val bmp = Pat.baked(o); val fr = frameOf(o); val s = o.tTile / bmp.width
            mp.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(fr.b.left, fr.b.top); postRotate(fr.rot, fr.px, fr.py) }) }
            mp.colorFilter = LUM_TO_ALPHA
        }
        c.drawRect(b, mp)
        c.restoreToCount(saved)
    }

    private fun drawMasked(c: Canvas, o: VObj, la: Float) {
        val b = RectF(o.bounds()); val pad = o.strokeW + 3f; b.inset(-pad, -pad)
        if (!rasterMasks) { maskedLayer(c, o, la, b); return }
        val k = 300f / 25.4f
        val w = (b.width() * k).toInt().coerceIn(1, 4000); val h = (b.height() * k).toInt().coerceIn(1, 4000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val bc = Canvas(bmp)
        bc.scale(w / b.width(), h / b.height()); bc.translate(-b.left, -b.top)
        maskedLayer(bc, o, la, b)
        c.drawBitmap(bmp, null, b, Paint(Paint.FILTER_BITMAP_FLAG)); bmp.recycle()
    }

    fun drawObj(c: Canvas, o: VObj, la: Float) { if (o.tMode != 0) drawMasked(c, o, la) else drawBase(c, o, la) }

    private fun drawBase(c: Canvas, o: VObj, la: Float) {
        when (o.kind) {
            Kind.PATH -> {
                val p = o.path()
                if (o.shadow) {
                    c.save(); c.translate(1.5f, 1.5f)
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK; alpha = (70f * o.alpha * la).toInt().coerceIn(0, 255)
                        if (o.fill != null) style = Paint.Style.FILL else { style = Paint.Style.STROKE; strokeWidth = o.strokeW }
                    }
                    c.drawPath(p, sp); c.restore()
                }
                if (o.mesh != null && o.closed) drawMesh(c, o, la, p) else fillPaint(o, la)?.let { c.drawPath(p, it) }
                strokePaint(o, la)?.let { c.drawPath(p, it) }
                arrowPts(o)?.let { a ->
                    val ap = Path().apply { moveTo(a[0], a[1]); lineTo(a[2], a[3]); lineTo(a[4], a[5]); close() }
                    c.drawPath(ap, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.stroke ?: Color.BLACK; alpha = a255(o, la) })
                }
            }
            Kind.TEXT -> {
                val pt = o.textPaint().apply { color = o.fill ?: Color.BLACK; alpha = a255(o, la) }
                if (o.onPath) c.drawTextOnPath(o.text.replace("\n", " "), o.path(), 0f, 0f, pt)
                else {
                    c.save(); if (o.tRot != 0f) c.rotate(o.tRot, o.tx, o.ty)
                    val x = o.textX(o.textW()); o.lines().forEachIndexed { i, s -> c.drawText(s, x, o.ty + i * o.size * o.leading, pt) }
                    c.restore()
                }
            }
            Kind.IMAGE -> o.bmp?.let {
                c.save(); if (o.tRot != 0f) c.rotate(o.tRot, o.rect.centerX(), o.rect.centerY())
                c.drawBitmap(it, null, o.rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = a255(o, la) }); c.restore()
            }
        }
    }

    /** The shape an object is clipped to ("put inside"), if any. */
    fun clipOf(doc: Doc, o: VObj): VObj? = if (o.clipUid == 0) null else doc.objs.firstOrNull { it.uid == o.clipUid && it !== o }

    fun drawAll(c: Canvas, doc: Doc) {
        for (o in doc.objs) {
            val l = doc.layers.getOrNull(o.layer) ?: continue
            if (!(l.visible && o.visible)) continue
            val cont = clipOf(doc, o)
            if (cont != null) { c.save(); c.clipPath(cont.path()); drawObj(c, o, l.opacity); c.restore() } else drawObj(c, o, l.opacity)
        }
    }
}

object Export {
    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun contourData(n: List<Node>, closed: Boolean): String {
        if (n.isEmpty()) return ""
        val sb = StringBuilder("M${f(n[0].x)} ${f(n[0].y)}")
        for (i in 0 until (if (closed) n.size else n.size - 1)) {
            val a = n[i]; val b = n[(i + 1) % n.size]
            if (a.outLine() && b.inLine()) sb.append("L${f(b.x)} ${f(b.y)}")
            else sb.append("C${f(a.ox)} ${f(a.oy)} ${f(b.ix)} ${f(b.iy)} ${f(b.x)} ${f(b.y)}")
        }
        if (closed) sb.append("Z")
        return sb.toString()
    }

    private fun pathData(o: VObj): String {
        val sb = StringBuilder(contourData(o.nodes, o.closed))
        for (h in o.holes) sb.append(contourData(h.nodes, h.closed))
        return sb.toString()
    }

    private fun strokeAttrs(o: VObj, op: Float): String {
        val s = o.stroke ?: return " stroke=\"none\""
        val sb = StringBuilder(" stroke=\"${hex(s)}\" stroke-width=\"${f(o.strokeW)}\"")
        sb.append(" stroke-linecap=\"${arrayOf("round", "square", "butt")[o.cap]}\" stroke-linejoin=\"${arrayOf("round", "bevel", "miter")[o.join]}\"")
        Render.dashArr(o)?.let { d -> sb.append(" stroke-dasharray=\"${d.joinToString(" ") { f(it) }}\"") }
        if (op < 0.999f) sb.append(" opacity=\"${f(op)}\"")
        return sb.toString()
    }

    private fun meshSvg(o: VObj, d: String, id: Int, op: Float): String {
        val sb = StringBuilder("<defs><clipPath id=\"mc$id\"><path d=\"$d\" clip-rule=\"evenodd\"/></clipPath></defs>\n<g clip-path=\"url(#mc$id)\"${if (op < 0.999f) " opacity=\"${f(op)}\"" else ""}>\n")
        for (c in Render.meshCells(o)) {
            val h = hex(c.color)
            val pts = (0 until 4).joinToString(" ") { "${f(c.p[it * 2])},${f(c.p[it * 2 + 1])}" }
            sb.append("<polygon points=\"$pts\" fill=\"$h\" stroke=\"$h\" stroke-width=\"0.15\"/>\n")
        }
        return sb.append("</g>\n").toString()
    }

    private fun obj(o: VObj, la: Float, id: Int): String {
        val sb = StringBuilder(); val op = o.alpha * la
        when (o.kind) {
            Kind.PATH -> {
                val d = pathData(o)
                if (o.shadow) sb.append("<path d=\"$d\" transform=\"translate(1.5 1.5)\" fill=\"${if (o.fill != null) "#000000" else "none"}\" stroke=\"${if (o.stroke != null) "#000000" else "none"}\" stroke-width=\"${f(o.strokeW)}\" opacity=\"${f(0.27f * op)}\"/>\n")
                var fillRef = "none"
                val f1 = o.fill; val f2 = o.fill2
                if (o.mesh != null && o.closed) sb.append(meshSvg(o, d, id, op))
                else if (f1 != null && f2 != null) {
                    val g = Render.gradPts(o); val mid = o.fillMid
                    val stops = "<stop offset=\"0\" stop-color=\"${hex(f1)}\"/>" + (if (mid != null) "<stop offset=\"0.5\" stop-color=\"${hex(mid)}\"/>" else "") + "<stop offset=\"1\" stop-color=\"${hex(f2)}\"/>"
                    if (o.gradType == 1) sb.append("<defs><radialGradient id=\"g$id\" gradientUnits=\"userSpaceOnUse\" cx=\"${f((g[0] + g[2]) / 2)}\" cy=\"${f((g[1] + g[3]) / 2)}\" r=\"${f(hypot(g[2] - g[0], g[3] - g[1]) / 2)}\">$stops</radialGradient></defs>\n")
                    else sb.append("<defs><linearGradient id=\"g$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${f(g[0])}\" y1=\"${f(g[1])}\" x2=\"${f(g[2])}\" y2=\"${f(g[3])}\">$stops</linearGradient></defs>\n")
                    fillRef = "url(#g$id)"
                } else if (f1 != null) fillRef = hex(f1)
                val fo = if (f1 != null && op < 0.999f && o.stroke == null) " opacity=\"${f(op)}\"" else ""
                sb.append("<path d=\"$d\" fill=\"$fillRef\"${if (o.holes.isNotEmpty()) " fill-rule=\"evenodd\"" else ""}${strokeAttrs(o, op)}$fo/>\n")
                Render.arrowPts(o)?.let { a -> sb.append("<path d=\"M${f(a[0])} ${f(a[1])}L${f(a[2])} ${f(a[3])}L${f(a[4])} ${f(a[5])}Z\" fill=\"${hex(o.stroke ?: 0)}\"/>\n") }
            }
            Kind.TEXT -> {
                val common = "font-family=\"${esc(Fonts.cssFamily(o.font))}\" font-size=\"${f(o.size)}\" fill=\"${hex(o.fill ?: 0)}\"" +
                    (if (o.bold) " font-weight=\"bold\"" else "") + (if (o.italic) " font-style=\"italic\"" else "") +
                    (if (o.underline || o.strikeThrough) " text-decoration=\"" + listOfNotNull(if (o.underline) "underline" else null, if (o.strikeThrough) "line-through" else null).joinToString(" ") + "\"" else "") +
                    (if (o.spacing != 0f) " letter-spacing=\"${f(o.spacing * o.size)}\"" else "") + (if (op < 0.999f) " opacity=\"${f(op)}\"" else "")
                val rot = if (o.tRot != 0f && !o.onPath) " transform=\"rotate(${f(o.tRot)} ${f(o.tx)} ${f(o.ty)})\"" else ""
                if (o.onPath) {
                    sb.append("<defs><path id=\"tp$id\" d=\"${pathData(o)}\"/></defs>\n<text $common><textPath xlink:href=\"#tp$id\" href=\"#tp$id\">${esc(o.text.replace("\n", " "))}</textPath></text>\n")
                } else {
                    val anchor = arrayOf("start", "middle", "end")[o.align]; val x = o.textX(o.textW())
                    o.lines().forEachIndexed { i, s -> sb.append("<text x=\"${f(x)}\" y=\"${f(o.ty + i * o.size * o.leading)}\" text-anchor=\"$anchor\" xml:space=\"preserve\" $common$rot>${esc(s)}</text>\n") }
                }
            }
            Kind.IMAGE -> o.bmp?.let { b ->
                val data = Base64.encodeToString(Png.bytes(b), Base64.NO_WRAP)
                sb.append("<image transform=\"rotate(${f(o.tRot)} ${f(o.rect.centerX())} ${f(o.rect.centerY())})\" x=\"${f(o.rect.left)}\" y=\"${f(o.rect.top)}\" width=\"${f(o.rect.width())}\" height=\"${f(o.rect.height())}\" preserveAspectRatio=\"none\" opacity=\"${f(op)}\" xlink:href=\"data:image/png;base64,$data\"/>\n")
            }
        }
        if (o.tMode == 0) return sb.toString()
        return maskSvg(o, id) + "<g mask=\"url(#tm$id)\">\n" + sb + "</g>\n"
    }

    /** SVG luminance mask equivalent of the transparency fountain / bitmap pattern. */
    private fun maskSvg(o: VObj, id: Int): String {
        val ob = o.bounds(); val b = RectF(ob); val pad = o.strokeW + 3f; b.inset(-pad, -pad)
        val sb = StringBuilder("<defs>")
        fun gray(a: Float) = (a.coerceIn(0f, 1f) * 255).toInt()
        val fillRef: String
        if (o.tMode == 1) {
            val g = Render.framePts(o, o.tAngle)
            sb.append("<linearGradient id=\"tg$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${f(g[0])}\" y1=\"${f(g[1])}\" x2=\"${f(g[2])}\" y2=\"${f(g[3])}\">")
            sb.append("<stop offset=\"0\" stop-color=\"rgb(${gray(o.tA1)},${gray(o.tA1)},${gray(o.tA1)})\"/><stop offset=\"1\" stop-color=\"rgb(${gray(o.tA2)},${gray(o.tA2)},${gray(o.tA2)})\"/></linearGradient>")
            fillRef = "url(#tg$id)"
        } else {
            val bmp = Pat.baked(o); val fr = Render.frameOf(o); val tw = o.tTile; val th = o.tTile * bmp.height / bmp.width
            val data = Base64.encodeToString(Png.bytes(bmp), Base64.NO_WRAP)
            sb.append("<pattern id=\"tpat$id\" patternUnits=\"userSpaceOnUse\" patternTransform=\"rotate(${f(fr.rot)} ${f(fr.px)} ${f(fr.py)})\" x=\"${f(fr.b.left)}\" y=\"${f(fr.b.top)}\" width=\"${f(tw)}\" height=\"${f(th)}\">")
            sb.append("<image width=\"${f(tw)}\" height=\"${f(th)}\" preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,$data\"/></pattern>")
            fillRef = "url(#tpat$id)"
        }
        sb.append("<mask id=\"tm$id\" maskUnits=\"userSpaceOnUse\" x=\"${f(b.left)}\" y=\"${f(b.top)}\" width=\"${f(b.width())}\" height=\"${f(b.height())}\">")
        sb.append("<rect x=\"${f(b.left)}\" y=\"${f(b.top)}\" width=\"${f(b.width())}\" height=\"${f(b.height())}\" fill=\"$fillRef\"/></mask></defs>\n")
        return sb.toString()
    }

    fun svg(doc: Doc): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"${f(doc.w)}mm\" height=\"${f(doc.h)}mm\" viewBox=\"0 0 ${f(doc.w)} ${f(doc.h)}\">\n")
        val fonts = doc.objs.filter { it.kind == Kind.TEXT && Fonts.isCustom(it.font) }.map { it.font }.distinct()
        if (fonts.isNotEmpty()) {
            sb.append("<defs><style type=\"text/css\">")
            for (n in fonts) Fonts.fontData(n)?.let { d -> sb.append("@font-face{font-family:'${n.substringBeforeLast('.').replace("'", "")}';src:url(data:${Fonts.mime(n)};base64,$d)}") }
            sb.append("</style></defs>\n")
        }
        var id = 0
        for (o in doc.objs) {
            val l = doc.layers.getOrNull(o.layer) ?: continue
            if (l.visible && o.visible) {
                val cont = Render.clipOf(doc, o)
                if (cont != null) sb.append("<clipPath id=\"cp$id\"><path d=\"${pathData(cont)}\"${if (cont.holes.isNotEmpty()) " clip-rule=\"evenodd\"" else ""}/></clipPath>\n<g clip-path=\"url(#cp$id)\">\n${obj(o, l.opacity, id)}</g>\n")
                else sb.append(obj(o, l.opacity, id))
                id++
            }
        }
        return sb.append("</svg>\n").toString()
    }

    /** Vector PDF via PdfDocument: paths/text stay vector; every page keeps its own size (PostScript points). */
    fun pdfPages(docs: List<Doc>, out: OutputStream) {
        val pd = PdfDocument(); val k = 72f / 25.4f
        docs.forEachIndexed { i, doc ->
            val page = pd.startPage(PdfDocument.PageInfo.Builder((doc.w * k).roundToInt().coerceAtLeast(1), (doc.h * k).roundToInt().coerceAtLeast(1), i + 1).create())
            page.canvas.scale(k, k); page.canvas.clipRect(0f, 0f, doc.w, doc.h)
            Render.rasterMasks = true
            try { Render.drawAll(page.canvas, doc) } finally { Render.rasterMasks = false }
            pd.finishPage(page)
        }
        pd.writeTo(out); pd.close()
    }

    fun pdf(doc: Doc, out: OutputStream) = pdfPages(listOf(doc), out)

    fun zipSvg(docs: List<Doc>, out: OutputStream) {
        val z = java.util.zip.ZipOutputStream(out)
        docs.forEachIndexed { i, d -> z.putNextEntry(java.util.zip.ZipEntry("page${i + 1}.svg")); z.write(svg(d).toByteArray()); z.closeEntry() }
        z.finish()
    }

    fun zipPng(docs: List<Doc>, dpi: Int, out: OutputStream) {
        val z = java.util.zip.ZipOutputStream(out)
        docs.forEachIndexed { i, d ->
            val b = bitmap(d, safeDpi(d, dpi), false)
            z.putNextEntry(java.util.zip.ZipEntry("page${i + 1}.png")); b.compress(Bitmap.CompressFormat.PNG, 100, z); z.closeEntry(); b.recycle()
        }
        z.finish()
    }

    /** Lowers DPI if the bitmap would exceed ~40 megapixels (OOM guard). */
    fun safeDpi(doc: Doc, dpi: Int): Float {
        var d = dpi.toFloat()
        while ((doc.w / 25.4f * d).toLong() * (doc.h / 25.4f * d).toLong() > 40_000_000L) d *= 0.9f
        return d
    }

    fun bitmap(doc: Doc, dpi: Float, opaque: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap((doc.w / 25.4f * dpi).roundToInt().coerceAtLeast(1), (doc.h / 25.4f * dpi).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); if (opaque) c.drawColor(Color.WHITE)
        val s = dpi / 25.4f; c.scale(s, s); Render.drawAll(c, doc)
        return bmp
    }
}
