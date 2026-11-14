package com.topz.stitchdraw.vectorart

import android.graphics.Color
import android.graphics.Matrix
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.*

/**
 * Small offline SVG importer (no network, no libraries): path / rect / circle / ellipse / line / polyline / polygon,
 * groups, transforms, solid fill & stroke (gradients become their first stop colour).
 * Skipped: text, images, clip paths, masks, <use>, filters, CSS classes. Compound paths import as separate shapes.
 */
object SvgImport {
    private class St(var fill: Int?, var stroke: Int?, var sw: Float, var op: Float, var cap: Int, var join: Int) {
        fun copy() = St(fill, stroke, sw, op, cap, join)
    }
    private const val K = 25.4f / 96f      // 1 CSS px in millimetres
    private val NAMED = mapOf("black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080, "orange" to 0xFFA500, "purple" to 0x800080, "cyan" to 0x00FFFF,
        "magenta" to 0xFF00FF, "lime" to 0x00FF00, "navy" to 0x000080, "maroon" to 0x800000, "teal" to 0x008080, "silver" to 0xC0C0C0,
        "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700)
    private val TOK = Regex("([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)")
    private val NUM = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    private fun parser(xml: String): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(StringReader(xml)) }

    private fun attrs(p: XmlPullParser): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (i in 0 until p.attributeCount) m[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
        m["style"]?.split(";")?.forEach { kv -> val x = kv.split(":", limit = 2); if (x.size == 2) m[x[0].trim()] = x[1].trim() }
        return m
    }

    /** -1 = unknown/keep, -2 = none, else 0xFFrrggbb. */
    private fun parseColor(v0: String, grads: Map<String, Int>): Long {
        val v = v0.trim().lowercase()
        if (v == "none" || v == "transparent") return -2L
        try {
            if (v.startsWith("#")) {
                val h = v.substring(1)
                val rgb = when (h.length) {
                    3 -> (h[0].toString().repeat(2) + h[1].toString().repeat(2) + h[2].toString().repeat(2)).toInt(16)
                    6 -> h.toInt(16)
                    8 -> h.substring(0, 6).toInt(16)
                    else -> return -1L
                }
                return 0xFF000000L or rgb.toLong()
            }
            if (v.startsWith("rgb(")) {
                val parts = v.substring(4, v.indexOf(')')).split(",", " ").filter { it.isNotBlank() }
                if (parts.size < 3) return -1L
                val c = parts.take(3).map { s -> if (s.endsWith("%")) (s.dropLast(1).toFloat() * 2.55f).roundToInt() else s.toFloat().roundToInt() }.map { it.coerceIn(0, 255) }
                return 0xFF000000L or ((c[0] shl 16) or (c[1] shl 8) or c[2]).toLong()
            }
            if (v.startsWith("url(")) {
                val id = v.substringAfter("#").substringBefore(")").trim().trim('\'', '"')
                return grads[id]?.let { (it.toLong() and 0xFFFFFFFFL) } ?: -1L
            }
        } catch (e: Exception) { return -1L }
        return NAMED[v]?.let { 0xFF000000L or it.toLong() } ?: -1L
    }

    private fun paintOf(v: String, grads: Map<String, Int>, cur: Int?): Int? {
        val c = parseColor(v, grads)
        return if (c == -2L) null else if (c >= 0L) c.toInt() else cur
    }

    private fun style(a: Map<String, String>, st: St, grads: Map<String, Int>): St {
        a["fill"]?.let { st.fill = paintOf(it, grads, st.fill) }
        a["stroke"]?.let { st.stroke = paintOf(it, grads, st.stroke) }
        a["stroke-width"]?.let { st.sw = num(it, st.sw) }
        a["opacity"]?.toFloatOrNull()?.let { st.op *= it }
        a["stroke-linecap"]?.let { st.cap = when (it.trim()) { "round" -> 0; "square" -> 1; else -> 2 } }
        a["stroke-linejoin"]?.let { st.join = when (it.trim()) { "round" -> 0; "bevel" -> 1; else -> 2 } }
        return st
    }

    private fun num(s: String?, def: Float = 0f): Float =
        s?.trim()?.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }?.toFloatOrNull() ?: def

    private fun lenMm(s: String?): Float? {
        val t = s?.trim() ?: return null; if (t.isEmpty() || t.endsWith("%")) return null
        val v = t.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }.toFloatOrNull() ?: return null
        return when (t.dropWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }.trim()) {
            "mm" -> v; "cm" -> v * 10f; "in" -> v * 25.4f; "pt" -> v * 25.4f / 72f; "pc" -> v * 25.4f / 6f; else -> v * K
        }
    }

    private fun rootMatrix(a: Map<String, String>): Matrix {
        val vb = a["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
        val w = lenMm(a["width"]); val h = lenMm(a["height"]); val m = Matrix()
        if (vb != null && vb.size == 4 && vb[2] > 0f && vb[3] > 0f) {
            val sx = if (w != null) w / vb[2] else null; val sy = if (h != null) h / vb[3] else null
            val s = if (sx != null && sy != null) min(sx, sy) else sx ?: sy ?: K
            m.setScale(s, s); m.preTranslate(-vb[0], -vb[1])
        } else m.setScale(K, K)
        return m
    }

    private fun transform(s: String?): Matrix {
        val m = Matrix(); if (s == null) return m
        for (mt in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(s)) {
            val v = mt.groupValues[2].split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
            val t = Matrix()
            when (mt.groupValues[1]) {
                "translate" -> t.setTranslate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "scale" -> { val sx = v.getOrElse(0) { 1f }; t.setScale(sx, v.getOrElse(1) { sx }) }
                "rotate" -> t.setRotate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                "matrix" -> if (v.size == 6) t.setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f))
                "skewX" -> t.setSkew(tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                "skewY" -> t.setSkew(0f, tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat())
            }
            m.preConcat(t)
        }
        return m
    }

    private fun collectGradients(xml: String): Map<String, Int> {
        val res = HashMap<String, Int>()
        try {
            val p = parser(xml); var cur: String? = null; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val n = p.name.substringAfter(':')
                    if (n == "linearGradient" || n == "radialGradient") cur = p.getAttributeValue(null, "id")
                    else if (n == "stop") {
                        val id = cur
                        if (id != null && !res.containsKey(id)) { val c = parseColor(attrs(p)["stop-color"] ?: "", emptyMap()); if (c >= 0) res[id] = c.toInt() }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = p.name.substringAfter(':'); if (n == "linearGradient" || n == "radialGradient") cur = null
                }
                ev = p.next()
            }
        } catch (e: Exception) { }
        return res
    }

    fun parse(xml: String, layer: Int): List<VObj> {
        val grads = collectGradients(xml); val p = parser(xml)
        val out = ArrayList<VObj>(); val ms = ArrayList<Matrix>(); val ss = ArrayList<St>()
        var skip = 0; var rootSeen = false; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val name = p.name.substringAfter(':')
                if (skip > 0) skip++
                else {
                    val a = attrs(p)
                    when (name) {
                        "svg" -> {
                            val base = if (!rootSeen) Matrix(rootMatrix(a)) else Matrix(ms.last()).also { it.preConcat(transform(a["transform"])) }
                            rootSeen = true; ms.add(base)
                            ss.add(style(a, if (ss.isEmpty()) St(Color.BLACK, null, 1f, 1f, 2, 2) else ss.last().copy(), grads))
                        }
                        "g", "a", "switch" -> if (ms.isNotEmpty()) {
                            val m = Matrix(ms.last()); m.preConcat(transform(a["transform"])); ms.add(m); ss.add(style(a, ss.last().copy(), grads))
                        } else skip = 1
                        "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> { if (ms.isNotEmpty()) shape(name, a, ms.last(), ss.last(), grads, layer, out); skip = 1 }
                        else -> skip = 1
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (skip > 0) skip--
                else {
                    val name = p.name.substringAfter(':')
                    if ((name == "svg" || name == "g" || name == "a" || name == "switch") && ms.isNotEmpty()) { ms.removeAt(ms.size - 1); ss.removeAt(ss.size - 1) }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float): MutableList<Node> {
        val k = 0.5523f; val l = x; val r = x + w; val t = y; val b = y + h
        val n = mutableListOf(Node(l + rx, t), Node(r - rx, t), Node(r, t + ry), Node(r, b - ry), Node(r - rx, b), Node(l + rx, b), Node(l, b - ry), Node(l, t + ry))
        n[1].ox = r - rx + k * rx; n[1].oy = t
        n[2].ix = r; n[2].iy = t + ry - k * ry
        n[3].ox = r; n[3].oy = b - ry + k * ry
        n[4].ix = r - rx + k * rx; n[4].iy = b
        n[5].ox = l + rx - k * rx; n[5].oy = b
        n[6].ix = l; n[6].iy = b - ry + k * ry
        n[7].ox = l; n[7].oy = t + ry - k * ry
        n[0].ix = l + rx - k * rx; n[0].iy = t
        return n
    }

    private fun shape(name: String, a: Map<String, String>, parent: Matrix, base: St, grads: Map<String, Int>, layer: Int, out: MutableList<VObj>) {
        if (a["display"] == "none") return
        val m = Matrix(parent); m.preConcat(transform(a["transform"]))
        val st = style(a, base.copy(), grads)
        if (name == "line") st.fill = null
        if (st.fill == null && st.stroke == null) return
        val subs = ArrayList<Pair<MutableList<Node>, Boolean>>()
        fun n(k: String) = num(a[k])
        when (name) {
            "path" -> subs.addAll(path(a["d"] ?: ""))
            "rect" -> {
                val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                if (w <= 0f || h <= 0f) return
                var rx = num(a["rx"], -1f); var ry = num(a["ry"], -1f)
                if (rx < 0f && ry < 0f) { rx = 0f; ry = 0f } else if (rx < 0f) rx = ry else if (ry < 0f) ry = rx
                rx = min(rx, w / 2); ry = min(ry, h / 2)
                subs.add((if (rx > 0f && ry > 0f) roundRect(x, y, w, h, rx, ry) else Geo.rect(x, y, x + w, y + h)) to true)
            }
            "circle" -> { val r = n("r"); if (r <= 0f) return; subs.add(Geo.ellipse(n("cx"), n("cy"), r, 0f, 0f, r) to true) }
            "ellipse" -> { val rx = n("rx"); val ry = n("ry"); if (rx <= 0f || ry <= 0f) return; subs.add(Geo.ellipse(n("cx"), n("cy"), rx, 0f, 0f, ry) to true) }
            "line" -> subs.add(mutableListOf(Node(n("x1"), n("y1")), Node(n("x2"), n("y2"))) to false)
            "polyline", "polygon" -> {
                val v = NUM.findAll(a["points"] ?: "").mapNotNull { it.value.toFloatOrNull() }.toList()
                val nodes = mutableListOf<Node>(); var i = 0
                while (i + 1 < v.size) { nodes.add(Node(v[i], v[i + 1])); i += 2 }
                subs.add(nodes to (name == "polygon"))
            }
        }
        val sw = st.sw * m.mapRadius(1f)
        for ((nodes, closed) in subs) {
            if (nodes.size < 2) continue
            val o = VObj(Kind.PATH); o.nodes = nodes; o.closed = closed
            o.fill = st.fill; o.stroke = st.stroke; o.strokeW = sw; o.alpha = st.op.coerceIn(0f, 1f); o.cap = st.cap; o.join = st.join; o.layer = layer
            o.transform(m); o.fRot = 0f; out.add(o)
        }
    }

    private fun path(d: String): List<Pair<MutableList<Node>, Boolean>> {
        val toks = TOK.findAll(d).map { it.value }.toList()
        val subs = ArrayList<Pair<MutableList<Node>, Boolean>>()
        var cur: MutableList<Node>? = null
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f; var lcx = 0f; var lcy = 0f; var lqx = 0f; var lqy = 0f; var prev = ' '
        var i = 0
        fun num(): Float = if (i < toks.size) (toks[i++].toFloatOrNull() ?: 0f) else 0f
        fun ensure(): MutableList<Node> { val c = cur ?: mutableListOf(Node(cx, cy)); cur = c; return c }
        fun line(x: Float, y: Float) { ensure().add(Node(x, y)); cx = x; cy = y }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            val c = ensure(); val last = c.last(); last.ox = x1; last.oy = y1
            c.add(Node(x, y).also { it.ix = x2; it.iy = y2 }); cx = x; cy = y; lcx = x2; lcy = y2
        }
        fun quad(qx: Float, qy: Float, x: Float, y: Float) {
            val c1x = cx + 2f / 3f * (qx - cx); val c1y = cy + 2f / 3f * (qy - cy); val c2x = x + 2f / 3f * (qx - x); val c2y = y + 2f / 3f * (qy - y)
            cubic(c1x, c1y, c2x, c2y, x, y)
        }
        fun flush() { cur?.let { if (it.size >= 2) subs.add(it to false) }; cur = null }
        fun close() {
            val c = cur ?: return
            if (c.size >= 2) {
                val f = c.first(); val l = c.last()
                if (c.size > 2 && hypot(f.x - l.x, f.y - l.y) < 1e-4f) { f.ix = l.ix; f.iy = l.iy; c.removeAt(c.size - 1) }
                subs.add(c to true)
            }
            cur = null; cx = sx; cy = sy
        }
        var cmd = ' '
        while (i < toks.size) {
            val t = toks[i]
            if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { close(); prev = 'Z'; continue } }
            else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') { i++; continue }
            val rel = cmd.isLowerCase(); val ox = if (rel) cx else 0f; val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { val x = num() + ox; val y = num() + oy; flush(); cur = mutableListOf(Node(x, y)); cx = x; cy = y; sx = x; sy = y; cmd = if (rel) 'l' else 'L'; prev = 'M' }
                'L' -> { val x = num() + ox; val y = num() + oy; line(x, y); prev = 'L' }
                'H' -> { val x = num() + ox; line(x, cy); prev = 'H' }
                'V' -> { val y = num() + oy; line(cx, y); prev = 'V' }
                'C' -> { val x1 = num() + ox; val y1 = num() + oy; val x2 = num() + ox; val y2 = num() + oy; val x = num() + ox; val y = num() + oy; cubic(x1, y1, x2, y2, x, y); prev = 'C' }
                'S' -> {
                    val x2 = num() + ox; val y2 = num() + oy; val x = num() + ox; val y = num() + oy
                    val x1 = if (prev == 'C' || prev == 'S') 2 * cx - lcx else cx; val y1 = if (prev == 'C' || prev == 'S') 2 * cy - lcy else cy
                    cubic(x1, y1, x2, y2, x, y); prev = 'S'
                }
                'Q' -> { val qx = num() + ox; val qy = num() + oy; val x = num() + ox; val y = num() + oy; quad(qx, qy, x, y); lqx = qx; lqy = qy; prev = 'Q' }
                'T' -> {
                    val x = num() + ox; val y = num() + oy
                    val qx = if (prev == 'Q' || prev == 'T') 2 * cx - lqx else cx; val qy = if (prev == 'Q' || prev == 'T') 2 * cy - lqy else cy
                    quad(qx, qy, x, y); lqx = qx; lqy = qy; prev = 'T'
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val fa = num() != 0f; val fs = num() != 0f; val x = num() + ox; val y = num() + oy
                    arc(cx, cy, rx, ry, rot, fa, fs, x, y, { a, b, c, d, e, f -> cubic(a, b, c, d, e, f) }, { a, b -> line(a, b) }); prev = 'A'
                }
                else -> i++
            }
        }
        flush(); return subs
    }

    /** SVG elliptical arc (endpoint form) -> cubic bezier segments. */
    private fun arc(x1: Float, y1: Float, rx0: Float, ry0: Float, phiDeg: Float, fa: Boolean, fs: Boolean, x2: Float, y2: Float,
                    cubic: (Float, Float, Float, Float, Float, Float) -> Unit, line: (Float, Float) -> Unit) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0).toDouble(); var ry = abs(ry0).toDouble()
        if (rx == 0.0 || ry == 0.0) { line(x2, y2); return }
        val phi = Math.toRadians(phiDeg.toDouble()); val cosP = cos(phi); val sinP = sin(phi)
        val dx2 = (x1 - x2) / 2.0; val dy2 = (y1 - y2) / 2.0
        val x1p = cosP * dx2 + sinP * dy2; val y1p = -sinP * dx2 + cosP * dy2
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1.0) { val s = sqrt(lam); rx *= s; ry *= s }
        val rx2 = rx * rx; val ry2 = ry * ry
        val num = max(0.0, rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p); val den = rx2 * y1p * y1p + ry2 * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(num / den); if (fa == fs) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0; val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!fs && dth > 0) dth -= 2 * PI else if (fs && dth < 0) dth += 2 * PI
        val segs = max(1, ceil(abs(dth) / (PI / 2)).toInt()); val delta = dth / segs; val t = 4.0 / 3.0 * tan(delta / 4)
        fun mx(ux: Double, uy: Double) = (cosP * rx * ux - sinP * ry * uy + cx).toFloat()
        fun my(ux: Double, uy: Double) = (sinP * rx * ux + cosP * ry * uy + cy).toFloat()
        var th = th1
        for (k in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th); val c2 = cos(th + delta); val s2 = sin(th + delta)
            val e1x = c1 - t * s1; val e1y = s1 + t * c1; val e2x = c2 + t * s2; val e2y = s2 - t * c2
            cubic(mx(e1x, e1y), my(e1x, e1y), mx(e2x, e2y), my(e2x, e2y), if (k == segs - 1) x2 else mx(c2, s2), if (k == segs - 1) y2 else my(c2, s2))
            th += delta
        }
    }
}
