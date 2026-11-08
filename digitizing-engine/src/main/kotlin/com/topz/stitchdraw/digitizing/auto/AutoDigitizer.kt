package com.topz.stitchdraw.digitizing.auto

import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Raster input: ARGB pixels, row-major. */
class ImageData(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) { "bad image size" } }
}

data class AutoOptions(
    /** Maximum number of thread colours (background excluded when detected). */
    val colors: Int = 6,
    /** Width of the finished design in mm; height follows the image aspect ratio. */
    val widthMm: Float = 80f,
    /** Images larger than this (longest side) are averaged down first, which also keeps RAM use small. */
    val maxSidePx: Int = 400,
    /** Treat the colour that dominates the image border as fabric and do not stitch it. */
    val skipBackground: Boolean = true,
    /** Regions smaller than this are dropped as noise. */
    val minAreaMm2: Float = 2f,
    /** Outline simplification tolerance. */
    val simplifyMm: Float = 0.25f,
    val smoothOutlines: Boolean = true,
    /** Regions whose mean width is between 0.8 mm and this become satin columns instead of tatami fill. 0 = never use satin. */
    val satinMaxWidthMm: Float = 6f,
    val params: StitchParams = StitchParams(),
)

class AutoObject(
    val colorIndex: Int,          // index into AutoResult.threadRgb
    val outline: List<Vec2>,      // mm, centred on (0,0), +Y down
    val holes: List<List<Vec2>>,
    val areaMm2: Float,
    val type: StitchType = StitchType.TATAMI,
    /** Satin column width (only for SATIN). */
    val widthMm: Float = 0f,
)

class AutoResult(
    val threadRgb: List<Int>,
    val objects: List<AutoObject>,
    val widthMm: Float,
    val heightMm: Float,
    val backgroundRgb: Int?,
)

/**
 * Automatic digitizing: colour separation (k-means), region detection (connected components),
 * outline tracing with holes, simplification and sequencing. Pure Kotlin, no Android dependencies.
 */
object AutoDigitizer {

    fun run(image: ImageData, options: AutoOptions = AutoOptions()): AutoResult {
        val img = downscale(image, options.maxSidePx)
        val w = img.width; val h = img.height
        val mmPerPx = options.widthMm / w
        val heightMm = h * mmPerPx

        // ---- colour separation ----
        val n = w * h
        val transparent = BooleanArray(n)
        val rgb = IntArray(n)
        for (i in 0 until n) {
            val p = img.argb[i]; val a = p ushr 24
            if (a < 32) { transparent[i] = true; rgb[i] = 0xFFFFFF; continue }
            val r = blend((p shr 16) and 0xFF, a); val g = blend((p shr 8) and 0xFF, a); val b = blend(p and 0xFF, a)
            rgb[i] = (r shl 16) or (g shl 8) or b
        }
        val (centers, labels) = kmeans(rgb, transparent, options.colors.coerceIn(2, 16))
        if (centers.isEmpty()) return AutoResult(emptyList(), emptyList(), options.widthMm, heightMm, null)
        smoothLabels(labels, w, h)

        // ---- background ----
        var bg = -1
        if (options.skipBackground) {
            val count = IntArray(centers.size); var border = 0
            fun tally(x: Int, y: Int) { val l = labels[y * w + x]; if (l >= 0) count[l]++; border++ }
            for (x in 0 until w) { tally(x, 0); tally(x, h - 1) }
            for (y in 1 until h - 1) { tally(0, y); tally(w - 1, y) }
            val top = count.indices.maxByOrNull { count[it] } ?: -1
            if (top >= 0 && count[top] * 2 >= border) bg = top
        }

        // ---- regions ----
        val comp = IntArray(n) { -1 }
        val compLabel = ArrayList<Int>(); val compArea = ArrayList<Int>()
        val stack = IntArray(n)
        for (start in 0 until n) {
            val lab = labels[start]
            if (lab < 0 || lab == bg || comp[start] >= 0) continue
            val id = compLabel.size
            var sp = 0; var area = 0
            stack[sp++] = start; comp[start] = id
            while (sp > 0) {
                val p = stack[--sp]; area++
                val x = p % w; val y = p / w
                if (x > 0 && comp[p - 1] < 0 && labels[p - 1] == lab) { comp[p - 1] = id; stack[sp++] = p - 1 }
                if (x < w - 1 && comp[p + 1] < 0 && labels[p + 1] == lab) { comp[p + 1] = id; stack[sp++] = p + 1 }
                if (y > 0 && comp[p - w] < 0 && labels[p - w] == lab) { comp[p - w] = id; stack[sp++] = p - w }
                if (y < h - 1 && comp[p + w] < 0 && labels[p + w] == lab) { comp[p + w] = id; stack[sp++] = p + w }
            }
            compLabel.add(lab); compArea.add(area)
        }

        // ---- bounding boxes per component ----
        val k = compLabel.size
        val minX = IntArray(k) { Int.MAX_VALUE }; val maxX = IntArray(k) { -1 }
        val minY = IntArray(k) { Int.MAX_VALUE }; val maxY = IntArray(k) { -1 }
        for (p in 0 until n) { val c = comp[p]; if (c < 0) continue
            val x = p % w; val y = p / w
            if (x < minX[c]) minX[c] = x; if (x > maxX[c]) maxX[c] = x
            if (y < minY[c]) minY[c] = y; if (y > maxY[c]) maxY[c] = y }

        val tolPx = max(0.5f, options.simplifyMm / mmPerPx)
        val objects = ArrayList<AutoObject>()
        for (c in 0 until k) {
            val areaMm2 = compArea[c] * mmPerPx * mmPerPx
            if (areaMm2 < options.minAreaMm2) continue
            val loops = traceLoops(comp, w, c, minX[c], minY[c], maxX[c], maxY[c])
            val outers = ArrayList<Pair<List<Vec2>, Float>>(); val holes = ArrayList<List<Vec2>>()
            for (l in loops) {
                val a = signedArea(l)
                val s = simplify(l, tolPx).let { if (options.smoothOutlines) chaikin(it) else it }
                if (s.size < 3) continue
                if (a > 0f) outers.add(s to a) else holes.add(s)
            }
            if (options.satinMaxWidthMm > 0f && outers.isNotEmpty()) {
                var area = 0f; var per = 0f
                for ((o, a) in outers) { area += a; per += SatinSkeleton.perimeter(o) }
                for (hl in holes) { area -= abs(signedArea(hl)); per += SatinSkeleton.perimeter(hl) }
                val wMm = SatinSkeleton.estimateWidthMm(area * mmPerPx * mmPerPx, per * mmPerPx)
                if (wMm in SatinSkeleton.MIN_WIDTH_MM..options.satinMaxWidthMm) {
                    val pad = 3; val cw = maxX[c] - minX[c] + 1 + 2 * pad; val ch = maxY[c] - minY[c] + 1 + 2 * pad
                    val mask = BooleanArray(cw * ch)
                    for (yy in minY[c]..maxY[c]) for (xx in minX[c]..maxX[c]) if (comp[yy * w + xx] == c) mask[(yy - minY[c] + pad) * cw + (xx - minX[c] + pad)] = true
                    val raster = Raster(mask, cw, ch, mmPerPx, (minX[c] - pad - w / 2f) * mmPerPx, (minY[c] - pad - h / 2f) * mmPerPx)
                    val cols = SatinSkeleton.columns(raster)
                    if (cols.isNotEmpty()) {
                        val each = compArea[c] * mmPerPx * mmPerPx / cols.size
                        for (col in cols) objects.add(AutoObject(compLabel[c], col.path, emptyList(), each, StitchType.SATIN, col.widthMm))
                        continue
                    }
                }
            }
            for ((outer, a) in outers) {
                val mine = holes.filter { hole ->
                    val probe = hole.first()
                    inPoly(probe, outer) && outers.filter { it.first !== outer && inPoly(probe, it.first) && it.second < a }.isEmpty()
                }
                val mm = { p: Vec2 -> Vec2((p.x - w / 2f) * mmPerPx, (p.y - h / 2f) * mmPerPx) }
                val holeArea = mine.sumOf { abs(signedArea(it)).toDouble() }.toFloat()
                val netArea = (a - holeArea) * mmPerPx * mmPerPx
                if (netArea < options.minAreaMm2) continue
                objects.add(AutoObject(compLabel[c], outer.map(mm), mine.map { it.map(mm) }, netArea))
            }
        }

        // ---- sequencing: biggest colour areas first (they lie underneath), then nearest-neighbour inside a colour ----
        val colourArea = FloatArray(centers.size)
        for (o in objects) colourArea[o.colorIndex] += o.areaMm2
        val order = centers.indices.filter { colourArea[it] > 0f }.sortedByDescending { colourArea[it] }
        val remap = IntArray(centers.size) { -1 }
        order.forEachIndexed { i, c -> remap[c] = i }
        val sequenced = ArrayList<AutoObject>()
        var cur = Vec2.ZERO
        for (c in order) {
            val pool = objects.filter { it.colorIndex == c }.toMutableList()
            while (pool.isNotEmpty()) {
                val next = pool.minByOrNull { it.outline.first().distanceTo(cur) }!!
                pool.remove(next)
                sequenced.add(AutoObject(remap[c], next.outline, next.holes, next.areaMm2, next.type, next.widthMm))
                cur = next.outline.first()
            }
        }
        return AutoResult(order.map { centers[it] }, sequenced, options.widthMm, heightMm, if (bg >= 0) centers[bg] else null)
    }

    // ---- image helpers -------------------------------------------------------------------------

    private fun blend(c: Int, a: Int) = (c * a + 255 * (255 - a)) / 255    // composite over white

    fun downscale(src: ImageData, maxSide: Int): ImageData {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val f = longest.toFloat() / maxSide
        val nw = max(1, (src.width / f).toInt()); val nh = max(1, (src.height / f).toInt())
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = (y * f).toInt(); val y1 = min(src.height, max(y0 + 1, ((y + 1) * f).toInt()))
            for (x in 0 until nw) {
                val x0 = (x * f).toInt(); val x1 = min(src.width, max(x0 + 1, ((x + 1) * f).toInt()))
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var cnt = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = src.argb[yy * src.width + xx]; val al = p ushr 24
                    a += al; r += ((p shr 16) and 0xFF) * al; g += ((p shr 8) and 0xFF) * al; b += (p and 0xFF) * al; cnt++
                }
                out[y * nw + x] = if (a == 0L) 0 else
                    ((a / cnt).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            }
        }
        return ImageData(nw, nh, out)
    }

    /** Deterministic k-means (k-means++ style seeding with a fixed pick rule). Returns (centres as RGB, label per pixel; -1 = transparent). */
    private fun kmeans(rgb: IntArray, transparent: BooleanArray, kWanted: Int): Pair<List<Int>, IntArray> {
        val n = rgb.size
        val idx = (0 until n).filter { !transparent[it] }
        if (idx.isEmpty()) return emptyList<Int>() to IntArray(n) { -1 }
        // sample at most ~20000 pixels for fitting
        val step = max(1, idx.size / 20000)
        val sample = IntArray((idx.size + step - 1) / step) { rgb[idx[it * step]] }
        val centers = ArrayList<IntArray>()
        fun rgbOf(p: Int) = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        centers.add(rgbOf(sample[sample.size / 2]))
        val dist = DoubleArray(sample.size) { Double.MAX_VALUE }
        while (centers.size < kWanted) {
            val last = centers.last()
            var best = -1; var bestD = 0.0
            for (i in sample.indices) {
                val c = rgbOf(sample[i])
                val d = (sq(c[0] - last[0]) + sq(c[1] - last[1]) + sq(c[2] - last[2])).toDouble()
                if (d < dist[i]) dist[i] = d
                if (dist[i] > bestD) { bestD = dist[i]; best = i }
            }
            if (best < 0 || bestD < 12.0 * 12.0) break           // no clearly different colour left
            centers.add(rgbOf(sample[best]))
        }
        val kk = centers.size
        val assign = IntArray(sample.size)
        repeat(10) {
            val sum = Array(kk) { LongArray(3) }; val cnt = IntArray(kk)
            for (i in sample.indices) {
                val c = rgbOf(sample[i]); var b = 0; var bd = Int.MAX_VALUE
                for (j in 0 until kk) { val d = sq(c[0] - centers[j][0]) + sq(c[1] - centers[j][1]) + sq(c[2] - centers[j][2]); if (d < bd) { bd = d; b = j } }
                assign[i] = b; sum[b][0] += c[0].toLong(); sum[b][1] += c[1].toLong(); sum[b][2] += c[2].toLong(); cnt[b]++
            }
            for (j in 0 until kk) if (cnt[j] > 0) for (ch in 0..2) centers[j][ch] = (sum[j][ch] / cnt[j]).toInt()
        }
        val labels = IntArray(n) { -1 }
        for (p in idx) {
            val c = rgbOf(rgb[p]); var b = 0; var bd = Int.MAX_VALUE
            for (j in 0 until kk) { val d = sq(c[0] - centers[j][0]) + sq(c[1] - centers[j][1]) + sq(c[2] - centers[j][2]); if (d < bd) { bd = d; b = j } }
            labels[p] = b
        }
        return centers.map { (it[0] shl 16) or (it[1] shl 8) or it[2] } to labels
    }

    private fun sq(v: Int) = v * v

    /** One 3x3 majority pass removes isolated speckle without moving real edges. */
    private fun smoothLabels(l: IntArray, w: Int, h: Int) {
        val out = l.copyOf()
        val counts = IntArray(32)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val me = l[y * w + x]; if (me < 0) continue
            java.util.Arrays.fill(counts, 0)
            for (dy in -1..1) for (dx in -1..1) { val v = l[(y + dy) * w + x + dx]; if (v in 0..31) counts[v]++ }
            var best = me
            for (v in counts.indices) if (counts[v] > counts[best]) best = v
            if (counts[best] >= 6) out[y * w + x] = best
        }
        System.arraycopy(out, 0, l, 0, l.size)
    }

    // ---- outline tracing -----------------------------------------------------------------------

    /**
     * Boundary loops (pixel-corner coordinates) of component [id]. Outer loops come out with positive signed
     * area, holes with negative, because every pixel edge is walked with the region on its right.
     */
    private fun traceLoops(comp: IntArray, w: Int, id: Int, x0: Int, y0: Int, x1: Int, y1: Int): List<List<Vec2>> {
        val bw = x1 - x0 + 2; val bh = y1 - y0 + 2            // vertex grid
        val out1 = IntArray(bw * bh) { -1 }; val out2 = IntArray(bw * bh) { -1 }
        fun add(ax: Int, ay: Int, bx: Int, by: Int) {
            val a = (ay - y0) * bw + (ax - x0); val b = (by - y0) * bw + (bx - x0)
            if (out1[a] < 0) out1[a] = b else out2[a] = b
        }
        fun inside(x: Int, y: Int) = x in x0..x1 && y in y0..y1 && comp[y * w + x] == id
        for (y in y0..y1) for (x in x0..x1) {
            if (comp[y * w + x] != id) continue
            if (!inside(x, y - 1)) add(x, y, x + 1, y)
            if (!inside(x + 1, y)) add(x + 1, y, x + 1, y + 1)
            if (!inside(x, y + 1)) add(x + 1, y + 1, x, y + 1)
            if (!inside(x - 1, y)) add(x, y + 1, x, y)
        }
        val loops = ArrayList<List<Vec2>>()
        for (s in out1.indices) {
            while (out1[s] >= 0 || out2[s] >= 0) {
                val pts = ArrayList<Vec2>()
                var cur = s; var prevDx = 0; var prevDy = 0; var first = true
                while (true) {
                    val vx = cur % bw + x0; val vy = cur / bw + y0
                    pts.add(Vec2(vx.toFloat(), vy.toFloat()))
                    val cand1 = out1[cur]; val cand2 = out2[cur]
                    if (cand1 < 0 && cand2 < 0) break
                    var pick = 1
                    if (cand1 >= 0 && cand2 >= 0 && !first) {
                        // two ways out (diagonal pixels touching): prefer the sharper right turn so loops stay separate
                        fun turn(c: Int): Int { val dx = c % bw + x0 - vx; val dy = c / bw + y0 - vy; return prevDx * dy - prevDy * dx }
                        pick = if (turn(cand2) > turn(cand1)) 2 else 1
                    } else if (cand1 < 0) pick = 2
                    val next = if (pick == 1) cand1 else cand2
                    if (pick == 1) { out1[cur] = out2[cur]; out2[cur] = -1 } else out2[cur] = -1
                    prevDx = next % bw + x0 - vx; prevDy = next / bw + y0 - vy
                    first = false
                    cur = next
                    if (cur == s) break
                }
                if (pts.size >= 4) loops.add(pts)
            }
        }
        return loops
    }

    private fun signedArea(p: List<Vec2>): Float {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x.toDouble() * b.y - b.x.toDouble() * a.y }
        return (s / 2.0).toFloat()
    }

    private fun inPoly(p: Vec2, poly: List<Vec2>): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    /** Douglas-Peucker on a closed loop. */
    private fun simplify(loop: List<Vec2>, tol: Float): List<Vec2> {
        if (loop.size < 4) return loop
        // split at the point farthest from the start so both halves are open polylines
        var far = 0; var fd = -1f
        for (i in loop.indices) { val d = loop[i].distanceTo(loop[0]); if (d > fd) { fd = d; far = i } }
        val a = loop.subList(0, far + 1); val b = loop.subList(far, loop.size) + loop[0]
        val sa = dp(a, tol); val sb = dp(b, tol)
        return sa + sb.drop(1).dropLast(1)
    }

    internal fun dp(p: List<Vec2>, tol: Float): List<Vec2> {
        if (p.size < 3) return p
        val keep = BooleanArray(p.size); keep[0] = true; keep[p.size - 1] = true
        val st = ArrayDeque<IntArray>(); st.add(intArrayOf(0, p.size - 1))
        while (st.isNotEmpty()) {
            val (s, e) = st.removeLast()
            var md = 0f; var mi = -1
            for (i in s + 1 until e) { val d = com.topz.stitchdraw.vector.Polyline.distanceToSegment(p[i], p[s], p[e]); if (d > md) { md = d; mi = i } }
            if (mi >= 0 && md > tol) { keep[mi] = true; st.add(intArrayOf(s, mi)); st.add(intArrayOf(mi, e)) }
        }
        return p.filterIndexed { i, _ -> keep[i] }
    }

    /** One Chaikin corner-cutting pass on a closed loop: removes the pixel staircase look. */
    private fun chaikin(p: List<Vec2>): List<Vec2> {
        if (p.size < 3) return p
        val out = ArrayList<Vec2>(p.size * 2)
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            out.add(a.lerp(b, 0.25f)); out.add(a.lerp(b, 0.75f))
        }
        return out
    }
}
