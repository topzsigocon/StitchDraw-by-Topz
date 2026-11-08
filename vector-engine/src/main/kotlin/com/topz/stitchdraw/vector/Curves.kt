package com.topz.stitchdraw.vector

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bézier and spline helpers. Output is always a polyline in millimetres, ready for the stitch generators. */
object Curves {

    /** Flattened cubic Bézier including both end points. [tolMm] is the allowed deviation from the true curve. */
    fun cubic(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, tolMm: Float = 0.05f): List<Vec2> {
        require(tolMm > 0f) { "tolerance must be positive" }
        val d = max(distToChord(p1, p0, p3), distToChord(p2, p0, p3))
        val chord = p0.distanceTo(p3)
        val n = if (d < 1e-4f && chord < 1e-4f) 1 else min(MAX_SEGMENTS, max(1, ceil(sqrt(d / tolMm) * 1.2).toInt() + if (chord > 0f) 1 else 0))
        val out = ArrayList<Vec2>(n + 1)
        for (i in 0..n) out.add(cubicAt(p0, p1, p2, p3, i / n.toFloat()))
        return out
    }

    fun cubicAt(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: Float): Vec2 {
        val u = 1f - t
        val a = u * u * u; val b = 3f * u * u * t; val c = 3f * u * t * t; val d = t * t * t
        return Vec2(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }

    /** Quadratic Bézier, flattened. */
    fun quadratic(p0: Vec2, p1: Vec2, p2: Vec2, tolMm: Float = 0.05f): List<Vec2> =
        cubic(p0, p0 + (p1 - p0) * (2f / 3f), p2 + (p1 - p2) * (2f / 3f), p2, tolMm)

    /**
     * Smooth curve THROUGH the given points (Catmull-Rom converted to cubic Béziers), so a touch user only
     * taps the points the curve must pass through. Open curves keep their end points; closed curves wrap
     * around and do not repeat the first point.
     */
    fun smooth(points: List<Vec2>, closed: Boolean = false, tolMm: Float = 0.05f): List<Vec2> {
        val p = dedupe(points)
        if (p.size < 3) return p
        val n = p.size
        fun at(i: Int): Vec2 = if (closed) p[((i % n) + n) % n] else p[i.coerceIn(0, n - 1)]
        val segs = if (closed) n else n - 1
        val out = ArrayList<Vec2>()
        for (i in 0 until segs) {
            val c1 = at(i) + (at(i + 1) - at(i - 1)) * (1f / 6f)
            val c2 = at(i + 1) - (at(i + 2) - at(i)) * (1f / 6f)
            val seg = cubic(at(i), c1, c2, at(i + 1), tolMm)
            if (i == 0) out.addAll(seg) else out.addAll(seg.drop(1))
        }
        if (closed && out.size > 1 && out.last().distanceTo(out.first()) < 1e-3f) out.removeAt(out.lastIndex)
        return out
    }

    private fun dedupe(p: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>(p.size)
        for (v in p) if (out.isEmpty() || out.last().distanceTo(v) > 1e-3f) out.add(v)
        return out
    }

    private fun distToChord(p: Vec2, a: Vec2, b: Vec2) = Polyline.distanceToSegment(p, a, b)

    private const val MAX_SEGMENTS = 96
}
