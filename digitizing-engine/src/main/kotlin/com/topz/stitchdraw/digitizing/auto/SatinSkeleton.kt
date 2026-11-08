package com.topz.stitchdraw.digitizing.auto

import com.topz.stitchdraw.vector.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One satin column: its centre line (mm) and the width of the shape it must cover (mm). */
class SatinColumn(val path: List<Vec2>, val widthMm: Float)

/** A binary picture of a shape. Pixel (x,y) covers mm x in [ox + x*mmPerPx, ox + (x+1)*mmPerPx). */
class Raster(val mask: BooleanArray, val w: Int, val h: Int, val mmPerPx: Float, val ox: Float, val oy: Float)

/**
 * Turns thin shapes (letter strokes, outlines, borders) into satin columns: thin the mask to a one pixel skeleton,
 * split it into branches at the junctions, drop little spurs, smooth every branch and measure the local width
 * from a distance transform. Pure Kotlin.
 */
object SatinSkeleton {
    const val MIN_WIDTH_MM = 0.8f

    /** Mean width of a long thin shape: 2 * area / perimeter. */
    fun estimateWidthMm(areaMm2: Float, perimeterMm: Float) = if (perimeterMm <= 0f) 0f else 2f * areaMm2 / perimeterMm

    fun perimeter(loop: List<Vec2>): Float { var s = 0f; for (i in loop.indices) s += loop[i].distanceTo(loop[(i + 1) % loop.size]); return s }

    private fun area(p: List<Vec2>): Float { var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return kotlin.math.abs(s / 2).toFloat() }

    /** Thin-shape test for an outline with holes (mm). Returns the estimated width, or 0 when not thin enough/too thin. */
    fun thinWidthMm(outer: List<Vec2>, holes: List<List<Vec2>>, maxWidthMm: Float): Float {
        val a = area(outer) - holes.sumOf { area(it).toDouble() }.toFloat()
        val p = perimeter(outer) + holes.sumOf { perimeter(it).toDouble() }.toFloat()
        val w = estimateWidthMm(a, p)
        return if (w in MIN_WIDTH_MM..maxWidthMm) w else 0f
    }

    /** Satin columns for a vector outline with holes (even-odd), e.g. one letter. Empty when no usable skeleton. */
    fun columnsFor(outer: List<Vec2>, holes: List<List<Vec2>>): List<SatinColumn> {
        val all = listOf(outer) + holes
        val ext = max(outer.maxOf { it.x } - outer.minOf { it.x }, outer.maxOf { it.y } - outer.minOf { it.y })
        val r = rasterize(all, max(0.08f, ext / 500f)) ?: return emptyList()
        return columns(r)
    }

    fun rasterize(contours: List<List<Vec2>>, mmPerPx: Float, pad: Int = 3): Raster? {
        val cs = contours.filter { it.size >= 3 }
        if (cs.isEmpty()) return null
        val minX = cs.minOf { c -> c.minOf { it.x } }; val maxX = cs.maxOf { c -> c.maxOf { it.x } }
        val minY = cs.minOf { c -> c.minOf { it.y } }; val maxY = cs.maxOf { c -> c.maxOf { it.y } }
        val w = ceil((maxX - minX) / mmPerPx).toInt() + 2 * pad + 1
        val h = ceil((maxY - minY) / mmPerPx).toInt() + 2 * pad + 1
        if (w.toLong() * h > 4_000_000L) return null
        val ox = minX - pad * mmPerPx; val oy = minY - pad * mmPerPx
        val mask = BooleanArray(w * h)
        val xs = ArrayList<Float>()
        for (y in 0 until h) {
            val cy = oy + (y + 0.5f) * mmPerPx
            xs.clear()
            for (c in cs) for (i in c.indices) {
                val a = c[i]; val b = c[(i + 1) % c.size]
                if ((a.y <= cy) != (b.y <= cy)) xs.add(a.x + (cy - a.y) / (b.y - a.y) * (b.x - a.x))
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val x0 = max(0, ceil((xs[k] - ox) / mmPerPx - 0.5f).toInt()); val x1 = min(w - 1, floor((xs[k + 1] - ox) / mmPerPx - 0.5f).toInt())
                for (x in x0..x1) mask[y * w + x] = true
                k += 2
            }
        }
        return Raster(mask, w, h, mmPerPx, ox, oy)
    }

    // ---- skeleton ----
    private val DX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val DY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    /** Zhang-Suen thinning. The mask must have an empty border (rasterize pads it). */
    fun thin(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val m = mask.copyOf()
        var changed = true
        val rm = ArrayList<Int>()
        while (changed) {
            changed = false
            for (step in 0..1) {
                rm.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x; if (!m[i]) continue
                    val p2 = m[i - w]; val p3 = m[i - w + 1]; val p4 = m[i + 1]; val p5 = m[i + w + 1]
                    val p6 = m[i + w]; val p7 = m[i + w - 1]; val p8 = m[i - 1]; val p9 = m[i - w - 1]
                    var b = 0
                    if (p2) b++; if (p3) b++; if (p4) b++; if (p5) b++; if (p6) b++; if (p7) b++; if (p8) b++; if (p9) b++
                    if (b < 2 || b > 6) continue
                    var a = 0
                    if (!p2 && p3) a++; if (!p3 && p4) a++; if (!p4 && p5) a++; if (!p5 && p6) a++
                    if (!p6 && p7) a++; if (!p7 && p8) a++; if (!p8 && p9) a++; if (!p9 && p2) a++
                    if (a != 1) continue
                    if (step == 0) { if (p2 && p4 && p6) continue; if (p4 && p6 && p8) continue }
                    else { if (p2 && p4 && p8) continue; if (p2 && p6 && p8) continue }
                    rm.add(i)
                }
                if (rm.isNotEmpty()) { for (i in rm) m[i] = false; changed = true }
            }
        }
        return m
    }

    /** Chamfer 3-4 distance (pixels) from every set pixel to the nearest unset pixel. */
    private fun distance(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1_000_000
        val d = IntArray(w * h) { if (mask[it]) inf else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (d[i] == 0) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 3)
            if (y > 0) { v = min(v, d[i - w] + 3); if (x > 0) v = min(v, d[i - w - 1] + 4); if (x < w - 1) v = min(v, d[i - w + 1] + 4) }
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) v = min(v, 3)
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; if (d[i] == 0) continue
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 3)
            if (y < h - 1) { v = min(v, d[i + w] + 3); if (x < w - 1) v = min(v, d[i + w + 1] + 4); if (x > 0) v = min(v, d[i + w - 1] + 4) }
            d[i] = v
        }
        return FloatArray(w * h) { d[it] / 3f }
    }

    fun columns(r: Raster, minLenMm: Float = 1.5f): List<SatinColumn> {
        val w = r.w; val h = r.h; val n = w * h
        val sk = thin(r.mask, w, h)
        val dt = distance(r.mask, w, h)

        // 0 none, 1 end, 2 regular, 3 junction
        val kind = IntArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; if (!sk[i]) continue
            val v = BooleanArray(8) { sk[i + DY[it] * w + DX[it]] }
            val cnt = v.count { it }
            if (cnt == 0) continue
            var a = 0
            for (k in 0 until 8) if (!v[k] && v[(k + 1) % 8]) a++
            kind[i] = when { a == 1 -> 1; a == 2 -> 2; else -> 3 }
        }
        fun nbrs(i: Int): IntArray {
            val out = ArrayList<Int>(3)
            val x = i % w; val y = i / w
            for (k in 0 until 8) { val xx = x + DX[k]; val yy = y + DY[k]
                if (xx in 0 until w && yy in 0 until h && sk[yy * w + xx]) out.add(yy * w + xx) }
            return out.toIntArray()
        }

        val visited = BooleanArray(n)
        val raw = ArrayList<IntArray>()
        fun follow(start: Int, first: Int): IntArray {
            val path = ArrayList<Int>(); path.add(start)
            var prev = start; var cur = first
            while (true) {
                path.add(cur)
                if (kind[cur] != 2) break
                visited[cur] = true
                var next = -1; var bestScore = -1
                val cx = cur % w; val cy = cur / w
                for (nb in nbrs(cur)) {
                    if (nb == prev) continue
                    if (kind[nb] == 2 && visited[nb]) continue
                    val score = (if (kind[nb] != 2) 4 else 0) + (if (nb % w == cx || nb / w == cy) 2 else 0)
                    if (score > bestScore) { bestScore = score; next = nb }
                }
                if (next < 0) break
                prev = cur; cur = next
            }
            return path.toIntArray()
        }
        for (i in 0 until n) {
            if (kind[i] != 1 && kind[i] != 3) continue
            for (j in nbrs(i)) {
                if (kind[j] == 2) { if (!visited[j]) raw.add(follow(i, j)) }
                else if (kind[i] == 1 || kind[j] == 1) { if (i < j) raw.add(intArrayOf(i, j)) }   // junction-junction pixels are one node
            }
        }
        // closed loops (no end or junction pixel on them)
        for (i in 0 until n) if (kind[i] == 2 && !visited[i]) {
            val nb = nbrs(i); if (nb.isEmpty()) continue
            visited[i] = true
            val p = follow(i, nb[0]).toMutableList()
            if (p.size > 6) { p.add(i); raw.add(p.toIntArray()) }
        }

        val mm = r.mmPerPx
        // junction clusters (8-connected junction pixels form one node)
        val node = IntArray(n) { -1 }; var nodes = 0
        for (i in 0 until n) if (kind[i] == 3 && node[i] < 0) {
            val st = ArrayList<Int>(); st.add(i); node[i] = nodes
            while (st.isNotEmpty()) { val c = st.removeAt(st.size - 1); for (nb in nbrs(c)) if (kind[nb] == 3 && node[nb] < 0) { node[nb] = nodes; st.add(nb) } }
            nodes++
        }
        fun lengthPx(p: IntArray): Float { var s = 0f
            for (k in 1 until p.size) s += hypot(((p[k] % w) - (p[k - 1] % w)).toFloat(), ((p[k] / w) - (p[k - 1] / w)).toFloat()); return s }
        fun widthMm(p: IntArray): Float {
            val v = p.map { dt[it] }.sorted()
            val a = (v.size * 0.2f).toInt(); val b = max(a + 1, (v.size * 0.8f).toInt())
            val mid = v.subList(a, min(b, v.size)); val med = mid[mid.size / 2]
            return (2f * med - 1f) * mm
        }

        class Br(val px: IntArray, val wm: Float, val lenMm: Float, val pure: Boolean)
        val brs = ArrayList<Br>()
        for (p in raw) {
            val len = lengthPx(p) * mm
            val wm = widthMm(p)
            if (wm < MIN_WIDTH_MM * 0.6f || len < minLenMm) continue
            val k0 = kind[p.first()]; val k1 = kind[p.last()]
            val spur = p.first() != p.last() && ((k0 == 1 && k1 == 3) || (k0 == 3 && k1 == 1))
            if (spur && len < 0.75f * wm + 0.3f) continue         // only genuine little thorns; a stroke end past the junction is real
            brs.add(Br(p, wm, len, p.first() == p.last() && k0 == 2))
        }

        // ---- junction handling: join strokes that run straight through a node, butt the others against them ----
        val ends = brs.size * 2
        fun endPixel(e: Int) = brs[e / 2].px.let { if (e % 2 == 0) it.first() else it.last() }
        val nodeOfEnd = IntArray(ends) { e -> if (brs[e / 2].pure) -1 else endPixel(e).let { if (kind[it] == 3) node[it] else -1 } }
        fun dirOut(e: Int): FloatArray {
            val b = brs[e / 2]; val o = if (e % 2 == 0) b.px else b.px.reversedArray()
            val d = max(4f, 0.9f * b.wm / mm)
            var acc = 0f; var j = 0
            while (j + 1 < o.size && acc < d) { acc += hypot(((o[j + 1] % w) - (o[j] % w)).toFloat(), ((o[j + 1] / w) - (o[j] / w)).toFloat()); j++ }
            val dx = ((o[j] % w) - (o[0] % w)).toFloat(); val dy = ((o[j] / w) - (o[0] / w)).toFloat()
            val l = hypot(dx, dy)
            return if (l < 1e-6f) floatArrayOf(0f, 0f) else floatArrayOf(dx / l, dy / l)
        }
        val byNode = HashMap<Int, MutableList<Int>>()
        for (e in 0 until ends) if (nodeOfEnd[e] >= 0) byNode.getOrPut(nodeOfEnd[e]) { ArrayList() }.add(e)
        class Cand(val a: Int, val b: Int, val s: Float)
        val cands = ArrayList<Cand>()
        for ((_, es) in byNode) {
            val thr = if (es.size == 2) 0.5f else 0.766f           // a lone bend may turn up to 60 degrees, a real junction only ~40
            val dirs = es.map { dirOut(it) }
            for (i in es.indices) for (j in i + 1 until es.size) {
                if (es[i] / 2 == es[j] / 2) continue
                val st = -(dirs[i][0] * dirs[j][0] + dirs[i][1] * dirs[j][1])
                if (st >= thr) cands.add(Cand(es[i], es[j], st))
            }
        }
        cands.sortByDescending { it.s }
        val link = IntArray(ends) { -1 }
        val through = FloatArray(max(nodes, 1))
        for (c in cands) if (link[c.a] < 0 && link[c.b] < 0) {
            link[c.a] = c.b; link[c.b] = c.a
            val nd = nodeOfEnd[c.a]; through[nd] = max(through[nd], (brs[c.a / 2].wm + brs[c.b / 2].wm) / 2f)
        }

        class Chain(val px: IntArray, val wm: Float, val firstEnd: Int, val lastEnd: Int, val pure: Boolean)
        val chains = ArrayList<Chain>()
        val used = BooleanArray(brs.size)
        fun walk(start: Int, rev0: Boolean) {
            val parts = ArrayList<Int>(); var b = start; var rev = rev0
            val firstEnd = b * 2 + (if (rev) 1 else 0)
            var wAcc = 0f; var lAcc = 0f; var exit: Int
            while (true) {
                used[b] = true
                parts.addAll((if (rev) brs[b].px.reversedArray() else brs[b].px).toList())
                wAcc += brs[b].wm * brs[b].lenMm; lAcc += brs[b].lenMm
                exit = b * 2 + (if (rev) 0 else 1)
                val nxt = link[exit]
                if (nxt < 0 || used[nxt / 2]) break
                b = nxt / 2; rev = nxt % 2 == 1
            }
            chains.add(Chain(parts.toIntArray(), if (lAcc > 0f) wAcc / lAcc else brs[start].wm, firstEnd, exit, brs[start].pure))
        }
        for (b in brs.indices) if (!used[b]) { if (link[b * 2] < 0) walk(b, false) else if (link[b * 2 + 1] < 0) walk(b, true) }
        for (b in brs.indices) if (!used[b]) walk(b, false)

        val out = ArrayList<SatinColumn>()
        for (ch in chains) {
            var pts: List<Vec2> = ch.px.map { Vec2(it % w + 0.5f, it / w + 0.5f) }
            pts = if (ch.pure) AutoDigitizer.dp(pts.dropLast(1), 0.7f).let { it + it[0] } else AutoDigitizer.dp(pts, 0.7f)
            repeat(2) { pts = chaikinOpen(pts, ch.pure) }
            var mmPts = pts.map { Vec2(r.ox + it.x * mm, r.oy + it.y * mm) }
            if (!ch.pure && mmPts.size >= 2) {
                val wm = ch.wm
                fun clean(e: Int): Boolean = nodeOfEnd[e] >= 0 && link[e] < 0
                fun freeEnd(e: Int): Boolean = kind[endPixel(e)] == 1
                // junction ends: drop the curled skeleton tip, then stop at the edge of the through stroke (or run past a lone corner)
                if (clean(ch.firstEnd)) mmPts = cleanEnd(mmPts, true, wm, through[nodeOfEnd[ch.firstEnd]])
                if (mmPts.size >= 2 && clean(ch.lastEnd)) mmPts = cleanEnd(mmPts, false, wm, through[nodeOfEnd[ch.lastEnd]])
                if (mmPts.size < 2) continue
                val ext = 0.9f * wm / 2f                              // free skeleton ends retract by about half a width: grow them back
                if (freeEnd(ch.firstEnd)) mmPts = listOf(extend(mmPts[1], mmPts[0], ext)) + mmPts
                if (freeEnd(ch.lastEnd)) mmPts = mmPts + extend(mmPts[mmPts.size - 2], mmPts.last(), ext)
            }
            for (piece in splitAtCorners(mmPts, ch.wm, ch.pure)) {
                var total = 0f; for (i in 1 until piece.size) total += piece[i].distanceTo(piece[i - 1])
                if (piece.size >= 2 && total >= 0.8f) out.add(SatinColumn(piece, ch.wm.coerceIn(0.5f, 12f)))
            }
        }
        return out
    }

    /**
     * A satin column cannot turn a sharp corner without fanning its stitches, so a path that bends by 55 degrees or more
     * within about one width on each side is cut there into separate columns; each is run straight past the corner (cleanEnd, no
     * through stroke) so the two overlap and the outer corner is covered. Smooth curves and closed loops without corners are untouched.
     */
    private fun splitAtCorners(pts0: List<Vec2>, wm: Float, closed: Boolean): List<List<Vec2>> {
        if (pts0.size < 4) return listOf(pts0)
        val pts = if (closed) pts0.dropLast(1) else pts0
        val m = pts.size
        val cum = FloatArray(m + 1)
        for (i in 1..m) cum[i] = cum[i - 1] + pts[i % m].distanceTo(pts[i - 1])     // for closed, cum[m] is the loop length
        val total = if (closed) cum[m] else cum[m - 1]
        val win = max(wm, 1.2f)
        fun at(sIn: Float): Vec2 {
            var t = sIn
            if (closed) { t %= total; if (t < 0f) t += total } else t = t.coerceIn(0f, total)
            var i = 1; while (i < m && cum[i] < t) i++
            val a = pts[i - 1]; val b = pts[i % m]; val seg = cum[i] - cum[i - 1]
            return if (seg < 1e-6f) b else a.lerp(b, (t - cum[i - 1]) / seg)
        }
        class C(val i: Int, val ang: Float)
        val cs = ArrayList<C>()
        val lo = if (closed) 0 else 1; val hi = if (closed) m else m - 1
        for (i in lo until hi) {
            if (!closed && (cum[i] < win * 0.9f || total - cum[i] < win * 0.9f)) continue
            val a = at(cum[i] - win); val b = pts[i]; val c = at(cum[i] + win)
            val v1x = b.x - a.x; val v1y = b.y - a.y; val v2x = c.x - b.x; val v2y = c.y - b.y
            val l = hypot(v1x, v1y) * hypot(v2x, v2y); if (l < 1e-6f) continue
            val ang = Math.acos(((v1x * v2x + v1y * v2y) / l).toDouble().coerceIn(-1.0, 1.0)).toFloat()
            if (ang >= 0.96f) cs.add(C(i, ang))
        }
        if (cs.isEmpty()) return listOf(pts0)
        val accepted = ArrayList<C>()
        for (c in cs.sortedByDescending { it.ang }) {
            val ok = accepted.all { a -> val d = kotlin.math.abs(cum[a.i] - cum[c.i]); (if (closed) min(d, total - d) else d) >= 2f * win }
            if (ok) accepted.add(c)
        }
        val idx = accepted.map { it.i }.sorted()
        val seq: List<Vec2>; val bounds: List<Int>
        if (closed) {
            val c0 = idx[0]
            seq = (0 until m).map { pts[(c0 + it) % m] } + pts[c0]
            bounds = listOf(0) + idx.drop(1).map { (it - c0 + m) % m } + (m)
        } else { seq = pts; bounds = listOf(0) + idx + (m - 1) }
        val out = ArrayList<List<Vec2>>()
        for (k in 0 until bounds.size - 1) {
            var piece: List<Vec2> = seq.subList(bounds[k], bounds[k + 1] + 1)
            val startIsCorner = closed || k > 0
            val endIsCorner = closed || k < bounds.size - 2
            if (piece.size < 2) continue
            if (startIsCorner) piece = cleanEnd(piece, true, wm, 0f)
            if (piece.size >= 2 && endIsCorner) piece = cleanEnd(piece, false, wm, 0f)
            if (piece.size >= 2) out.add(piece)
        }
        return out
    }

    /**
     * Cleans one junction end of a column. The skeleton curls into the junction, so the first 0.5 widths are cut away;
     * the end is then re-extended in a straight line to [keep] mm from the node: half the through stroke's width (so it
     * butts against it), or 0.45 widths PAST the node when nothing runs through (a corner), so the outer corner is covered.
     */
    private fun cleanEnd(pts: List<Vec2>, atStart: Boolean, wm: Float, throughW: Float): List<Vec2> {
        val keep = if (throughW > 0f) throughW / 2f - 0.2f else -0.45f * wm
        val cut = max(keep, 0.5f * wm)
        val o = if (atStart) pts else pts.reversed()
        var acc = 0f; var i = 0
        while (i + 1 < o.size && acc + o[i].distanceTo(o[i + 1]) < cut) { acc += o[i].distanceTo(o[i + 1]); i++ }
        if (i + 1 >= o.size) return emptyList()                     // the whole column lies inside the junction
        val seg = o[i].distanceTo(o[i + 1])
        val q = if (seg < 1e-6f) o[i] else o[i].lerp(o[i + 1], (cut - acc) / seg)
        val rest = ArrayList<Vec2>(); rest.add(q); for (k in i + 1 until o.size) rest.add(o[k])
        if (rest.size < 2) return emptyList()
        if (cut > keep) {
            var j = 1; while (j + 1 < rest.size && rest[j].distanceTo(q) < 0.6f) j++
            rest[0] = extend(rest[j], q, cut - keep)
        }
        return if (atStart) rest else rest.reversed()
    }

    private fun extend(from: Vec2, to: Vec2, by: Float): Vec2 {
        val d = from.distanceTo(to); if (d < 1e-6f) return to
        return Vec2(to.x + (to.x - from.x) / d * by, to.y + (to.y - from.y) / d * by)
    }

    /** Corner cutting that keeps the end points (and the join of a closed loop). */
    private fun chaikinOpen(p: List<Vec2>, closed: Boolean): List<Vec2> {
        if (p.size < 3) return p
        val o = ArrayList<Vec2>()
        o.add(p[0])
        for (i in 0 until p.size - 1) { o.add(p[i].lerp(p[i + 1], 0.25f)); o.add(p[i].lerp(p[i + 1], 0.75f)) }
        o.add(p.last())
        return o
    }
}
