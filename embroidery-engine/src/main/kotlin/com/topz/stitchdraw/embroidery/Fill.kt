package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Polyline
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

internal object FillGeometry {
    fun dedupe(p: List<Vec2>, eps: Float = 0.01f): List<Vec2> {
        val out = ArrayList<Vec2>(p.size)
        for (v in p) if (out.isEmpty() || out.last().distanceTo(v) > eps) out.add(v)
        return out
    }

    /** Even-odd point in polygon test (polygon is implicitly closed). */
    fun inside(p: Vec2, poly: List<Vec2>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    fun distanceToBoundary(p: Vec2, poly: List<Vec2>): Float {
        var best = Float.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) { best = minOf(best, Polyline.distanceToSegment(p, poly[j], poly[i])); j = i }
        return best
    }

    /** Even-odd test over several contours (outline + holes). */
    fun insideAll(p: Vec2, contours: List<List<Vec2>>): Boolean {
        var c = false
        for (poly in contours) if (inside(p, poly)) c = !c
        return c
    }

    fun distanceToBoundaryAll(p: Vec2, contours: List<List<Vec2>>): Float =
        contours.minOf { distanceToBoundary(p, it) }

    fun rotate(p: Vec2, rad: Double): Vec2 {
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        return Vec2(p.x * c - p.y * s, p.x * s + p.y * c)
    }
}

/** Satin column (and zigzag) generator. */
internal object Satin {
    private class Sample(val pos: Vec2, val tangent: Vec2)

    private fun along(path: List<Vec2>, step: Float): List<Sample> {
        val seg = ArrayList<Float>(); var total = 0f
        for (i in 1 until path.size) { val d = path[i].distanceTo(path[i - 1]); seg.add(d); total += d }
        if (total <= 0f) return emptyList()
        fun at(s: Float): Vec2 {
            var rem = s.coerceIn(0f, total)
            for (i in seg.indices) { if (rem <= seg[i] || i == seg.lastIndex) return path[i].lerp(path[i + 1], if (seg[i] == 0f) 0f else (rem / seg[i]).coerceIn(0f, 1f)); rem -= seg[i] }
            return path.last()
        }
        val n = max(1, Math.ceil((total / step).toDouble()).toInt())
        val out = ArrayList<Sample>(n + 1)
        val look = max(step, 0.5f)
        for (k in 0..n) {
            val s = total * k / n
            val a = at(s - look); val b = at(s + look)          // smoothed tangent across corners
            val d = b - a; val len = d.length()
            out.add(Sample(at(s), if (len == 0f) Vec2(1f, 0f) else d * (1f / len)))
        }
        return out
    }

    fun generate(path: List<Vec2>, p: StitchParams, underlay: Boolean, pullMm: Float): List<List<Vec2>> {
        val half = (p.widthMm + pullMm).coerceAtMost(StitchParams.MAX_WIDTH) / 2f
        val samples = along(path, p.densityMm)
        if (samples.size < 2) return emptyList()

        fun edge(side: Float, inset: Float): List<Vec2> =
            along(path, p.stitchLengthMm).map { it.pos + Vec2(-it.tangent.y, it.tangent.x) * ((half - inset) * side) }

        val runs = ArrayList<List<Vec2>>()
        var atEnd = false                        // where the last pass finished: false = path start
        if (underlay) {
            val centre = along(path, p.stitchLengthMm).map { it.pos }
            runs.add(centre); atEnd = true
            if (half * 2f > 4f) {                // wide column: add edge walks (right edge back, left edge out)
                runs.add(edge(+1f, 0.4f).reversed()); atEnd = false
                runs.add(edge(-1f, 0.4f)); atEnd = true
            }
        }
        val zig = samples.mapIndexed { i, s ->
            s.pos + Vec2(-s.tangent.y, s.tangent.x) * (if (i % 2 == 0) half else -half)
        }
        val top = if (atEnd) zig.reversed() else zig
        // pieces of one object are connected by stitches that sit under the satin; start of top = end of last pass
        if (runs.isEmpty()) return listOf(top)
        runs[runs.lastIndex] = runs.last() + top
        return merge(runs)
    }

    /** Underlay passes join end to end, so a single continuous run is produced. */
    private fun merge(runs: List<List<Vec2>>): List<List<Vec2>> = listOf(runs.flatten())
}

/** Tatami (scanline fill) generator with brick-offset rows. Supports holes (even-odd rule). */
internal object Tatami {
    private const val LINK_STEP = 0.25f
    private const val LINK_TOL = 0.15f

    fun generate(poly: List<Vec2>, holes: List<List<Vec2>>, p: StitchParams): List<List<Vec2>> {
        val contours = listOf(poly) + holes
        val runs = ArrayList<List<Vec2>>()
        if (p.underlay) runs.addAll(fill(contours, angleDeg = p.angleDeg + 90f, spacing = 2.5f, length = 3.5f, ext = 0f, brick = false))
        runs.addAll(fill(contours, p.angleDeg, p.densityMm, p.stitchLengthMm.coerceAtLeast(1.0f), p.pullCompMm, brick = true))
        return joinRuns(runs, contours)
    }

    /** Adjacent runs whose connecting line stays inside the shape are fused (the link is hidden under the fill). */
    private fun joinRuns(runs: List<List<Vec2>>, contours: List<List<Vec2>>): List<List<Vec2>> {
        val out = ArrayList<MutableList<Vec2>>()
        for (r in runs) {
            if (r.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && linkInside(last.last(), r.first(), contours)) last.addAll(r) else out.add(r.toMutableList())
        }
        return out
    }

    private fun linkInside(a: Vec2, b: Vec2, contours: List<List<Vec2>>): Boolean {
        val d = a.distanceTo(b)
        if (d > 12f) return false
        val n = max(1, (d / LINK_STEP).toInt())
        for (i in 0..n) {
            val q = a.lerp(b, i / n.toFloat())
            if (!FillGeometry.insideAll(q, contours) && FillGeometry.distanceToBoundaryAll(q, contours) > LINK_TOL) return false
        }
        return true
    }

    private fun fill(contours: List<List<Vec2>>, angleDeg: Float, spacing: Float, length: Float, ext: Float, brick: Boolean): List<List<Vec2>> {
        val rad = -angleDeg.toDouble() * PI / 180.0
        val rotated = contours.map { c -> c.map { FillGeometry.rotate(it, rad) } }
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (v in rotated[0]) { minY = minOf(minY, v.y); maxY = maxOf(maxY, v.y) }
        val rows = floor(((maxY - minY) / spacing).toDouble()).toInt()
        val back = -rad
        val segments = ArrayList<List<Vec2>>()
        var leftToRight = true
        for (r in 0..rows) {
            val y = minY + spacing * (r + 0.5f)
            if (y > maxY) break
            val xs = ArrayList<Float>()
            for (rp in rotated) {
                var j = rp.size - 1
                for (i in rp.indices) {
                    val a = rp[j]; val b = rp[i]
                    if ((a.y > y) != (b.y > y)) xs.add(a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x))
                    j = i
                }
            }
            xs.sort()
            val intervals = ArrayList<Pair<Float, Float>>()
            var k = 0
            while (k + 1 < xs.size) { if (xs[k + 1] - xs[k] > 0.05f) intervals.add((xs[k] - ext / 2) to (xs[k + 1] + ext / 2)); k += 2 }
            if (intervals.isEmpty()) continue
            val ordered = if (leftToRight) intervals else intervals.reversed()
            val shift = if (brick) (r % 3) / 3f else 0f
            for ((x0, x1) in ordered) {
                val pts = ArrayList<Float>()
                var x = x0
                pts.add(x)
                val step = if (shift == 0f) length else length * shift
                x += step
                while (x < x1 - 0.2f) { pts.add(x); x += length }
                pts.add(x1)
                val line = pts.map { FillGeometry.rotate(Vec2(it, y), back) }
                segments.add(if (leftToRight) line else line.reversed())
            }
            leftToRight = !leftToRight
        }
        return segments
    }
}

/**
 * Appliqué: placement line -> STOP (lay fabric) -> tack-down -> STOP (trim fabric) -> satin cover border.
 * The two STOPs are exported as machine stops (colour-change code with the same thread), which is how
 * embroidery machines pause for fabric handling.
 */
internal object Applique {
    fun generate(outline: List<Vec2>, p: StitchParams): List<List<Vec2>> {
        val loop = outline + outline.first()
        val placement = Polyline.resample(loop, p.stitchLengthMm.coerceAtLeast(1.5f))
        val tack = Polyline.resample(loop, 2.0f)
        val border = Satin.generate(loop, p, underlay = p.underlay, pullMm = p.pullCompMm)
        return listOf(placement, emptyList(), tack, emptyList()) + border
    }
}
