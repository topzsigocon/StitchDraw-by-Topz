package com.topz.stitchdraw.vectorart

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline bitmap tracing: threshold (optionally several tonal levels) -> pixel-edge contours -> simplified polygons with holes.
 * The result is already in document coordinates (millimetres), following the image's rectangle and rotation.
 */
object Trace {
    class Shp(val outer: List<PointF>, val holes: List<List<PointF>>)
    class Level(val color: Int, val shapes: List<Shp>)

    fun run(src: Bitmap, rect: RectF, rot: Float, thresholdPct: Int, levels: Int, eps: Float, minArea0: Float, invert: Boolean, detail: Int): List<Level> {
        val sc = min(1f, detail.coerceIn(100, 1200).toFloat() / max(src.width, src.height))
        val w = max(4, (src.width * sc).toInt()); val h = max(4, (src.height * sc).toInt())
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h) { i ->
            val p = px[i]; val a = Color.alpha(p) / 255f
            val l = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val v = l * a + (1f - a)
            if (invert) 1f - v else v
        }
        val n = levels.coerceIn(1, 6); val top = thresholdPct.coerceIn(1, 99) / 100f
        val thr = FloatArray(n) { top * (it + 1) / n }
        val sumR = LongArray(n); val sumG = LongArray(n); val sumB = LongArray(n); val cnt = IntArray(n)
        for (i in px.indices) {
            val l = lum[i]
            for (k in 0 until n) if (l < thr[k]) { sumR[k] += Color.red(px[i]).toLong(); sumG[k] += Color.green(px[i]).toLong(); sumB[k] += Color.blue(px[i]).toLong(); cnt[k]++; break }
        }
        val m = Matrix().apply {
            setRectToRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), rect, Matrix.ScaleToFit.FILL)
            if (rot != 0f) postRotate(rot, rect.centerX(), rect.centerY())
        }
        val out = ArrayList<Level>()
        for (k in n - 1 downTo 0) {            // lightest band first so darker bands end up on top
            if (cnt[k] == 0) continue
            val mask = BooleanArray(w * h) { lum[it] < thr[k] }
            val col = if (n == 1) Color.BLACK else Color.rgb((sumR[k] / cnt[k]).toInt(), (sumG[k] / cnt[k]).toInt(), (sumB[k] / cnt[k]).toInt())
            val shapes = shapesOf(mask, w, h, eps, minArea0, m)
            if (shapes.isNotEmpty()) out.add(Level(col, shapes))
        }
        return out
    }

    class Line(val pts: List<PointF>)
    class Center(val lines: List<Line>, val widthMm: Float)

    /** Centerline trace: thins the dark strokes to one-pixel skeletons and follows them as open polylines (for lines, signatures, line art). */
    fun centerline(src: Bitmap, rect: RectF, rot: Float, thresholdPct: Int, eps: Float, minLen: Float, invert: Boolean, detail: Int): Center {
        val sc = min(1f, detail.coerceIn(100, 1200).toFloat() / max(src.width, src.height))
        val w = max(6, (src.width * sc).toInt()); val h = max(6, (src.height * sc).toInt())
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val thr = thresholdPct.coerceIn(1, 99) / 100f
        val sk = BooleanArray(w * h) { i ->
            val p = px[i]; val a = Color.alpha(p) / 255f
            val l = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val v = l * a + (1f - a)
            (if (invert) 1f - v else v) < thr
        }
        for (x in 0 until w) { sk[x] = false; sk[(h - 1) * w + x] = false }
        for (y in 0 until h) { sk[y * w] = false; sk[y * w + w - 1] = false }
        val area = sk.count { it }
        thin(sk, w, h)
        val skelCount = sk.count { it }
        val m = Matrix().apply {
            setRectToRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), rect, Matrix.ScaleToFit.FILL)
            if (rot != 0f) postRotate(rot, rect.centerX(), rect.centerY())
        }
        val widthMm = ((area.toFloat() / max(1, skelCount)) * rect.width() / w).coerceIn(0.1f, 40f)
        if (skelCount == 0) return Center(emptyList(), widthMm)
        val offs = intArrayOf(-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
        val deg = IntArray(w * h)
        for (i in 0 until w * h) if (sk[i]) { var d = 0; for (o in offs) if (sk[i + o]) d++; deg[i] = d }
        val seen = BooleanArray(w * h); val done = HashSet<Long>(); val chains = ArrayList<List<Int>>(); val big = (w * h).toLong()
        fun key(a: Int, b: Int) = min(a, b).toLong() * big + max(a, b)
        fun walk(start: Int, first: Int): List<Int> {
            val ch = ArrayList<Int>(); ch.add(start); var prev = start; var cur = first
            while (true) {
                ch.add(cur)
                if (deg[cur] != 2 || seen[cur]) break
                seen[cur] = true
                var next = -1
                for (o in offs) { val q = cur + o; if (sk[q] && q != prev) { next = q; break } }
                if (next < 0) break
                prev = cur; cur = next
            }
            return ch
        }
        for (i in 0 until w * h) {
            if (!sk[i] || deg[i] == 2) continue
            for (o in offs) {
                val q = i + o
                if (!sk[q] || key(i, q) in done || (deg[q] == 2 && seen[q])) continue
                val ch = walk(i, q); done.add(key(i, q)); if (ch.size >= 2) done.add(key(ch[ch.size - 2], ch[ch.size - 1]))
                chains.add(ch)
            }
        }
        for (i in 0 until w * h) {                       // closed rings without any end or junction
            if (!sk[i] || deg[i] != 2 || seen[i]) continue
            var first = -1; for (o in offs) if (sk[i + o]) { first = i + o; break }
            if (first < 0) continue
            seen[i] = true; chains.add(walk(i, first))
        }
        fun map(p: List<PointF>): List<PointF> {
            val a = FloatArray(p.size * 2); for (i in p.indices) { a[i * 2] = p[i].x; a[i * 2 + 1] = p[i].y }
            m.mapPoints(a); return List(p.size) { PointF(a[it * 2], a[it * 2 + 1]) }
        }
        val lines = ArrayList<Line>()
        for (ch in chains) {
            val a = ch.first(); val b = ch.last()
            val spur = (deg[a] == 1 && deg[b] >= 3) || (deg[b] == 1 && deg[a] >= 3)
            if (ch.size < minLen || (spur && ch.size < max(minLen, 6f))) continue
            val s = Geo.rdp(ch.map { PointF((it % w) + 0.5f, (it / w) + 0.5f) }, eps); if (s.size < 2) continue
            lines.add(Line(map(s)))
        }
        return Center(lines, widthMm)
    }

    /** Zhang-Suen thinning (in place). The outer pixel border must be false. */
    private fun thin(m: BooleanArray, w: Int, h: Int) {
        val del = ArrayList<Int>(); var changed = true; var guard = 0
        while (changed && guard < 80) {
            changed = false; guard++
            for (pass in 0..1) {
                del.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x; if (!m[i]) continue
                    val p2 = m[i - w]; val p3 = m[i - w + 1]; val p4 = m[i + 1]; val p5 = m[i + w + 1]; val p6 = m[i + w]; val p7 = m[i + w - 1]; val p8 = m[i - 1]; val p9 = m[i - w - 1]
                    var b = 0; if (p2) b++; if (p3) b++; if (p4) b++; if (p5) b++; if (p6) b++; if (p7) b++; if (p8) b++; if (p9) b++
                    if (b < 2 || b > 6) continue
                    var a = 0
                    if (!p2 && p3) a++; if (!p3 && p4) a++; if (!p4 && p5) a++; if (!p5 && p6) a++
                    if (!p6 && p7) a++; if (!p7 && p8) a++; if (!p8 && p9) a++; if (!p9 && p2) a++
                    if (a != 1) continue
                    if (pass == 0) { if (p2 && p4 && p6) continue; if (p4 && p6 && p8) continue }
                    else { if (p2 && p4 && p8) continue; if (p2 && p6 && p8) continue }
                    del.add(i)
                }
                if (del.isNotEmpty()) { changed = true; for (i in del) m[i] = false }
            }
        }
    }

    /** Closed loops of vertex indices around the true pixels (foreground kept on the right-hand side of every edge). */
    private fun contours(mk: BooleanArray, w: Int, h: Int): List<IntArray> {
        val w1 = w + 1; val nv = w1 * (h + 1)
        val to = IntArray(nv * 2) { -1 }; val used = BooleanArray(nv * 2)
        fun add(a: Int, b: Int) { if (to[a * 2] < 0) to[a * 2] = b else to[a * 2 + 1] = b }
        for (y in 0 until h) for (x in 0 until w) {
            if (!mk[y * w + x]) continue
            val v00 = y * w1 + x; val v10 = v00 + 1; val v01 = v00 + w1; val v11 = v01 + 1
            if (y == 0 || !mk[(y - 1) * w + x]) add(v00, v10)
            if (x == w - 1 || !mk[y * w + x + 1]) add(v10, v11)
            if (y == h - 1 || !mk[(y + 1) * w + x]) add(v11, v01)
            if (x == 0 || !mk[y * w + x - 1]) add(v01, v00)
        }
        fun turn(cur: Int, nxt: Int, a: Int, b: Int): Int {
            val dx = (nxt % w1) - (cur % w1); val dy = (nxt / w1) - (cur / w1)
            fun cross(t: Int): Int { val ex = (t % w1) - (nxt % w1); val ey = (t / w1) - (nxt / w1); return dx * ey - dy * ex }
            return if (cross(a) >= cross(b)) 0 else 1
        }
        val loops = ArrayList<IntArray>()
        for (v in 0 until nv) for (s in 0..1) {
            if (to[v * 2 + s] < 0 || used[v * 2 + s]) continue
            val pts = ArrayList<Int>(); var cur = v; var slot = s
            while (true) {
                used[cur * 2 + slot] = true; pts.add(cur)
                val nxt = to[cur * 2 + slot]
                val a = to[nxt * 2]; val b = to[nxt * 2 + 1]
                val ua = a >= 0 && !used[nxt * 2]; val ub = b >= 0 && !used[nxt * 2 + 1]
                val ns = when { ua && ub -> turn(cur, nxt, a, b); ua -> 0; ub -> 1; else -> -1 }
                if (ns < 0) break
                cur = nxt; slot = ns
            }
            if (pts.size >= 4) loops.add(pts.toIntArray())
        }
        return loops
    }

    private class Poly(val pts: List<PointF>, val area: Float) {
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        fun mayContain(x: Float, y: Float) = x in minX..maxX && y in minY..maxY
    }

    private fun shapesOf(mk: BooleanArray, w: Int, h: Int, eps: Float, minArea0: Float, m: Matrix): List<Shp> {
        val w1 = w + 1
        val polys = ArrayList<Poly>()
        for (loop in contours(mk, w, h)) {
            val pts = loop.map { PointF((it % w1).toFloat(), (it / w1).toFloat()) }
            val c = ArrayList<PointF>()
            for (i in pts.indices) {
                val a = pts[(i + pts.size - 1) % pts.size]; val b = pts[i]; val d = pts[(i + 1) % pts.size]
                if ((b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x) != 0f) c.add(b)
            }
            val s = Geo.rdp(c, eps); if (s.size < 3) continue
            var ar = 0f; for (i in s.indices) { val q = s[(i + 1) % s.size]; ar += s[i].x * q.y - q.x * s[i].y }
            polys.add(Poly(s, ar / 2f))
        }
        if (polys.isEmpty()) return emptyList()
        var minArea = minArea0; var keep = polys.filter { abs(it.area) >= minArea }
        while (keep.size > 1500 && minArea < 1e6f) { minArea = max(minArea * 2f, 4f); keep = polys.filter { abs(it.area) >= minArea } }
        if (keep.isEmpty()) return emptyList()
        val outerSign = if (keep.maxByOrNull { abs(it.area) }!!.area >= 0f) 1f else -1f   // the biggest loop is always an outer boundary
        val outers = keep.filter { it.area * outerSign > 0f }; val holes = keep.filter { it.area * outerSign < 0f }
        val owned = HashMap<Poly, MutableList<Poly>>()
        for (hp in holes) {
            val p0 = hp.pts[0]
            val owner = outers.filter { it.mayContain(p0.x, p0.y) && Geo.inPoly(it.pts, p0.x, p0.y) }.minByOrNull { abs(it.area) } ?: continue
            owned.getOrPut(owner) { ArrayList() }.add(hp)
        }
        fun map(p: List<PointF>): List<PointF> {
            val a = FloatArray(p.size * 2); for (i in p.indices) { a[i * 2] = p[i].x; a[i * 2 + 1] = p[i].y }
            m.mapPoints(a); return List(p.size) { PointF(a[it * 2], a[it * 2 + 1]) }
        }
        return outers.map { o -> Shp(map(o.pts), (owned[o] ?: emptyList<Poly>()).map { map(it.pts) }) }
    }
}
