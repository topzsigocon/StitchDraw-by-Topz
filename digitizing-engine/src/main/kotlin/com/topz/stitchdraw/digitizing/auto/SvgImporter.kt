package com.topz.stitchdraw.digitizing.auto

import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Curves
import com.topz.stitchdraw.vector.Vec2
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.*

/** [strokeWidth] is the stroke thickness in SVG user units after transforms (SVG default 1); it decides satin vs running when imported. */
class SvgShape(val subpaths: List<List<Vec2>>, val closed: Boolean, val fillRgb: Int?, val strokeRgb: Int?, val strokeWidth: Float = 1f)
class SvgResult(val shapes: List<SvgShape>, val width: Float, val height: Float, val minX: Float, val minY: Float)

class SvgException(msg: String) : Exception(msg)

/** Minimal but real SVG reader: path (all commands incl. arcs), rect, circle, ellipse, line, polyline, polygon, g, transforms, fill/stroke. */
object SvgImporter {
    /** Strokes at least this thick (mm, after scaling to the chosen design width) become satin columns. */
    const val STROKE_SATIN_MIN_MM = 0.8f


    fun parse(bytes: ByteArray): SvgResult {
        val doc = try {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))
        } catch (e: Exception) { throw SvgException("Not a valid SVG file") }
        val root = doc.documentElement
        if (root.tagName.substringAfter(':') != "svg") throw SvgException("Not an SVG document")
        var minX = 0f; var minY = 0f; var w: Float; var h: Float
        val vb = root.getAttribute("viewBox").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
        if (vb.size == 4 && vb[2] > 0 && vb[3] > 0) { minX = vb[0]; minY = vb[1]; w = vb[2]; h = vb[3] }
        else { w = num(root.getAttribute("width")) ?: 100f; h = num(root.getAttribute("height")) ?: 100f }
        val tol = (max(w, h) * 0.0004f).coerceAtLeast(0.001f)
        val out = ArrayList<SvgShape>()
        walk(root, Mat.I, Style(0x000000, null, 1f), tol, out)
        return SvgResult(out, w, h, minX, minY)
    }

    // ---- styles & transforms ----
    private class Style(val fill: Int?, val stroke: Int?, val strokeW: Float)
    private class Mat(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        fun then(o: Mat) = Mat(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.e + c * o.f + e, b * o.e + d * o.f + f)
        fun ap(p: Vec2) = Vec2(a * p.x + c * p.y + e, b * p.x + d * p.y + f)
        companion object { val I = Mat(1f, 0f, 0f, 1f, 0f, 0f) }
    }

    private fun num(s: String): Float? = Regex("-?[0-9]*\\.?[0-9]+(e[-+]?[0-9]+)?", RegexOption.IGNORE_CASE).find(s)?.value?.toFloatOrNull()

    private fun color(v: String, cur: Int?): Int? {
        val s = v.trim().lowercase()
        return when {
            s.isEmpty() || s == "inherit" -> cur
            s == "none" || s == "transparent" -> null
            s.startsWith("#") -> {
                val h = s.drop(1)
                when (h.length) { 3 -> h.map { "$it$it" }.joinToString("").toIntOrNull(16)
                    6 -> h.toIntOrNull(16); else -> cur }
            }
            s.startsWith("rgb") -> {
                val n = Regex("[0-9.]+%?").findAll(s.substringAfter('(')).map { it.value }.take(3).toList()
                if (n.size < 3) cur else { val v3 = n.map { if (it.endsWith("%")) (it.dropLast(1).toFloat() * 2.55f).roundToInt() else it.toFloat().roundToInt() }.map { it.coerceIn(0, 255) }
                    (v3[0] shl 16) or (v3[1] shl 8) or v3[2] }
            }
            else -> NAMED[s] ?: cur
        }
    }
    private val NAMED = mapOf("black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080, "pink" to 0xFFC0CB,
        "brown" to 0xA52A2A, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "navy" to 0x000080, "gold" to 0xFFD700, "silver" to 0xC0C0C0)

    private fun style(e: Element, parent: Style): Style {
        var fill = parent.fill; var stroke = parent.stroke; var sw = parent.strokeW
        if (e.hasAttribute("fill")) fill = color(e.getAttribute("fill"), fill)
        if (e.hasAttribute("stroke")) stroke = color(e.getAttribute("stroke"), stroke)
        if (e.hasAttribute("stroke-width")) sw = num(e.getAttribute("stroke-width"))?.coerceAtLeast(0f) ?: sw
        for (decl in e.getAttribute("style").split(';')) {
            val kv = decl.split(':', limit = 2); if (kv.size < 2) continue
            when (kv[0].trim()) { "fill" -> fill = color(kv[1], fill); "stroke" -> stroke = color(kv[1], stroke)
                "stroke-width" -> sw = num(kv[1])?.coerceAtLeast(0f) ?: sw }
        }
        return Style(fill, stroke, sw)
    }

    private fun transform(s: String): Mat {
        var m = Mat.I
        for (t in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(s)) {
            val v = t.groupValues[2].split(Regex("[ ,]+")).mapNotNull { it.trim().toFloatOrNull() }
            val n = when (t.groupValues[1]) {
                "translate" -> if (v.isNotEmpty()) Mat(1f, 0f, 0f, 1f, v[0], v.getOrElse(1) { 0f }) else null
                "scale" -> if (v.isNotEmpty()) Mat(v[0], 0f, 0f, v.getOrElse(1) { v[0] }, 0f, 0f) else null
                "rotate" -> if (v.isNotEmpty()) {
                    val r = Math.toRadians(v[0].toDouble()); val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
                    val rot = Mat(cs, sn, -sn, cs, 0f, 0f)
                    if (v.size >= 3) Mat(1f, 0f, 0f, 1f, v[1], v[2]).then(rot).then(Mat(1f, 0f, 0f, 1f, -v[1], -v[2])) else rot
                } else null
                "matrix" -> if (v.size == 6) Mat(v[0], v[1], v[2], v[3], v[4], v[5]) else null
                "skewX" -> if (v.isNotEmpty()) Mat(1f, 0f, tan(Math.toRadians(v[0].toDouble())).toFloat(), 1f, 0f, 0f) else null
                "skewY" -> if (v.isNotEmpty()) Mat(1f, tan(Math.toRadians(v[0].toDouble())).toFloat(), 0f, 1f, 0f, 0f) else null
                else -> null
            }
            if (n != null) m = m.then(n)
        }
        return m
    }

    private fun walk(e: Element, parentM: Mat, parentS: Style, tol: Float, out: MutableList<SvgShape>) {
        val tag = e.tagName.substringAfter(':')
        if (tag in setOf("defs", "clipPath", "mask", "symbol", "metadata", "title", "desc", "style", "pattern", "marker")) return
        if (e.getAttribute("display") == "none") return
        val m = parentM.then(transform(e.getAttribute("transform")))
        val st = style(e, parentS)
        fun f(n: String) = num(e.getAttribute(n)) ?: 0f
        val subs: List<List<Vec2>>? = when (tag) {
            "path" -> pathData(e.getAttribute("d"), tol)
            "rect" -> { val x = f("x"); val y = f("y"); val w = f("width"); val h = f("height")
                if (w > 0 && h > 0) listOf(listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))) else null }
            "circle" -> { val r = f("r"); if (r > 0) listOf(ellipse(f("cx"), f("cy"), r, r)) else null }
            "ellipse" -> { val rx = f("rx"); val ry = f("ry"); if (rx > 0 && ry > 0) listOf(ellipse(f("cx"), f("cy"), rx, ry)) else null }
            "line" -> listOf(listOf(Vec2(f("x1"), f("y1")), Vec2(f("x2"), f("y2"))))
            "polyline", "polygon" -> {
                val n = e.getAttribute("points").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
                val pts = (0 until n.size / 2).map { Vec2(n[it * 2], n[it * 2 + 1]) }
                if (pts.size >= 2) listOf(pts) else null }
            else -> null
        }
        if (subs != null) {
            val closedShape = tag in setOf("rect", "circle", "ellipse", "polygon") || (tag == "path" && lastClosed)
            val tr = subs.map { sp -> sp.map { m.ap(it) } }
            if (tr.isNotEmpty()) out.add(SvgShape(tr, closedShape || tag == "path" && st.fill != null,
                if (tag == "line" || tag == "polyline") null.takeIf { st.stroke == null && st.fill == null } ?: (if (tag == "polyline" && st.fill != null) st.fill else st.stroke ?: st.fill) else st.fill,
                st.stroke, st.strokeW * sqrt(abs(m.a * m.d - m.b * m.c))))
        }
        var c: Node? = e.firstChild
        while (c != null) { if (c is Element) walk(c, m, st, tol, out); c = c.nextSibling }
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<Vec2> {
        val n = (16 + sqrt(max(rx, ry)) * 8).toInt().coerceIn(24, 120)
        return (0 until n).map { val t = 2 * PI * it / n; Vec2((cx + rx * cos(t)).toFloat(), (cy + ry * sin(t)).toFloat()) }
    }

    private var lastClosed = false

    // ---- path data ----
    private fun pathData(d: String, tol: Float): List<List<Vec2>> {
        val toks = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?").findAll(d).map { it.value }.toList()
        var i = 0
        val subs = ArrayList<List<Vec2>>(); var cur = ArrayList<Vec2>()
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var cmd = ' '; var lcx = 0f; var lcy = 0f; var lastCtl = ' '
        lastClosed = false
        fun nx(): Float = toks[i++].toFloat()
        fun flush() { if (cur.size >= 2) subs.add(cur); cur = ArrayList() }
        fun isNum(k: Int) = k < toks.size && toks[k][0].let { it == '-' || it == '.' || it.isDigit() }
        try {
            while (i < toks.size) {
                if (!isNum(i)) cmd = toks[i++][0] else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
                val rel = cmd.isLowerCase()
                val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
                when (cmd.uppercaseChar()) {
                    'M' -> { flush(); x = ox + nx(); y = oy + nx(); sx = x; sy = y; cur.add(Vec2(x, y)); lastCtl = ' ' }
                    'L' -> { x = ox + nx(); y = oy + nx(); cur.add(Vec2(x, y)); lastCtl = ' ' }
                    'H' -> { x = ox + nx(); cur.add(Vec2(x, y)); lastCtl = ' ' }
                    'V' -> { y = oy + nx(); cur.add(Vec2(x, y)); lastCtl = ' ' }
                    'C' -> { val x1 = ox + nx(); val y1 = oy + nx(); val x2 = ox + nx(); val y2 = oy + nx(); val ex = ox + nx(); val ey = oy + nx()
                        cur.addAll(Curves.cubic(Vec2(x, y), Vec2(x1, y1), Vec2(x2, y2), Vec2(ex, ey), tol).drop(1)); lcx = x2; lcy = y2; x = ex; y = ey; lastCtl = 'C' }
                    'S' -> { val x1 = if (lastCtl == 'C') 2 * x - lcx else x; val y1 = if (lastCtl == 'C') 2 * y - lcy else y
                        val x2 = ox + nx(); val y2 = oy + nx(); val ex = ox + nx(); val ey = oy + nx()
                        cur.addAll(Curves.cubic(Vec2(x, y), Vec2(x1, y1), Vec2(x2, y2), Vec2(ex, ey), tol).drop(1)); lcx = x2; lcy = y2; x = ex; y = ey; lastCtl = 'C' }
                    'Q' -> { val x1 = ox + nx(); val y1 = oy + nx(); val ex = ox + nx(); val ey = oy + nx()
                        cur.addAll(Curves.quadratic(Vec2(x, y), Vec2(x1, y1), Vec2(ex, ey), tol).drop(1)); lcx = x1; lcy = y1; x = ex; y = ey; lastCtl = 'Q' }
                    'T' -> { val x1 = if (lastCtl == 'Q') 2 * x - lcx else x; val y1 = if (lastCtl == 'Q') 2 * y - lcy else y
                        val ex = ox + nx(); val ey = oy + nx()
                        cur.addAll(Curves.quadratic(Vec2(x, y), Vec2(x1, y1), Vec2(ex, ey), tol).drop(1)); lcx = x1; lcy = y1; x = ex; y = ey; lastCtl = 'Q' }
                    'A' -> { val rx = nx(); val ry = nx(); val rot = nx(); val fa = nx(); val fs = nx(); val ex = ox + nx(); val ey = oy + nx()
                        cur.addAll(arc(x, y, rx, ry, rot, fa != 0f, fs != 0f, ex, ey)); x = ex; y = ey; lastCtl = ' ' }
                    'Z' -> { if (cur.isNotEmpty()) { subs.add(cur); cur = ArrayList() }; x = sx; y = sy; lastClosed = true; lastCtl = ' '
                        cur = ArrayList(); cur.add(Vec2(x, y)) }
                    else -> throw SvgException("bad path command")
                }
            }
        } catch (e: IndexOutOfBoundsException) { /* truncated path data: keep what we have */ }
        catch (e: NumberFormatException) { }
        flush()
        return subs
    }

    /** SVG endpoint-arc to polyline (W3C implementation notes F.6). */
    private fun arc(x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, fa: Boolean, fs: Boolean, x2: Float, y2: Float): List<Vec2> {
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0 || (x1 == x2 && y1 == y2)) return listOf(Vec2(x2, y2))
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val xp = cp * dx + sp * dy; val yp = -sp * dx + cp * dy
        val lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
        if (lam > 1) { rx *= sqrt(lam); ry *= sqrt(lam) }
        val num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
        val den = rx * rx * yp * yp + ry * ry * xp * xp
        val co = (if (fa == fs) -1 else 1) * sqrt(max(0.0, num / den))
        val cxp = co * rx * yp / ry; val cyp = -co * ry * xp / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val th1 = ang(1.0, 0.0, (xp - cxp) / rx, (yp - cyp) / ry)
        var dth = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
        if (!fs && dth > 0) dth -= 2 * PI else if (fs && dth < 0) dth += 2 * PI
        val n = max(4, (abs(dth) / (PI / 24)).toInt())
        return (1..n).map { k -> val t = th1 + dth * k / n
            Vec2((cp * rx * cos(t) - sp * ry * sin(t) + cx).toFloat(), (sp * rx * cos(t) + cp * ry * sin(t) + cy).toFloat()) }
    }

    // ---- into a design ----
    /** Scales to [widthMm], centres on (0,0), builds objects. Fills -> TATAMI (inner subpaths become holes), strokes/open -> RUNNING. */
    fun toObjects(svg: SvgResult, widthMm: Float, params: StitchParams, baseThread: Int, firstId: Int, layerId: Int)
        : Pair<List<DesignObject>, List<Int>> {
        val k = widthMm / svg.width
        val cx = svg.minX + svg.width / 2; val cy = svg.minY + svg.height / 2
        fun mm(p: Vec2) = Vec2((p.x - cx) * k, (p.y - cy) * k)
        val colors = ArrayList<Int>()
        fun idx(rgb: Int): Int { var i = colors.indexOf(rgb); if (i < 0) { colors.add(rgb); i = colors.size - 1 }; return baseThread + i }
        val objs = ArrayList<DesignObject>(); var id = firstId
        for (s in svg.shapes) {
            val subs = s.subpaths.map { sp -> clean(sp.map(::mm)) }.filter { it.size >= 2 }
            if (subs.isEmpty()) continue
            if (s.fillRgb != null && s.closed) {
                val polys = subs.filter { it.size >= 3 && area(it) > 0.5f }
                val nested = nest(polys)
                for ((o, hs) in nested) objs.add(DesignObject(id++, "Fill $id", StitchType.TATAMI, o, idx(s.fillRgb), layerId, params, hs))
            }
            val strokeRgb = s.strokeRgb ?: if (s.fillRgb != null && !s.closed) s.fillRgb else null
            if (strokeRgb != null) for (sp in subs) {
                val path = if (s.closed && sp.size >= 3) sp + sp[0] else sp
                // A real stroke has a thickness: thick enough to see = satin column of that width, hairline = running stitch.
                val wMm = if (s.strokeRgb != null) s.strokeWidth * k else 0f
                if (wMm >= STROKE_SATIN_MIN_MM) {
                    val w = wMm.coerceIn(StitchParams.MIN_WIDTH, StitchParams.MAX_WIDTH)
                    objs.add(DesignObject(id++, "Stroke $id", StitchType.SATIN, path, idx(strokeRgb), layerId, params.copy(widthMm = w)))
                } else objs.add(DesignObject(id++, "Line $id", StitchType.RUNNING, path, idx(strokeRgb), layerId, params))
            }
        }
        return objs to colors
    }

    /** Even-odd nesting: contours at even depth are outlines, odd depth are holes of the nearest enclosing outline. */
    fun nest(polys: List<List<Vec2>>): List<Pair<List<Vec2>, List<List<Vec2>>>> {
        val sorted = polys.filter { it.size >= 3 }.sortedByDescending { area(it) }
        val outers = ArrayList<List<Vec2>>(); val holes = HashMap<List<Vec2>, MutableList<List<Vec2>>>()
        for (p in sorted) {
            val depth = sorted.count { it !== p && area(it) > area(p) && inside(p[0], it) }
            if (depth % 2 == 0) { outers.add(p); holes[p] = ArrayList() }
            else outers.lastOrNull { inside(p[0], it) }?.let { holes[it]!!.add(p) }
        }
        return outers.map { it to holes[it]!! }
    }

    private fun clean(p: List<Vec2>): List<Vec2> {
        val o = ArrayList<Vec2>()
        for (q in p) if (o.isEmpty() || o.last().distanceTo(q) > 0.05f) o.add(q)
        if (o.size > 2 && o.first().distanceTo(o.last()) <= 0.05f) o.removeAt(o.size - 1)
        return o
    }
    private fun area(p: List<Vec2>): Float { var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return abs(s / 2).toFloat() }
    private fun inside(pt: Vec2, poly: List<Vec2>): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) { val a = poly[i]; val b = poly[j]
            if ((a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i }
        return c
    }
}
