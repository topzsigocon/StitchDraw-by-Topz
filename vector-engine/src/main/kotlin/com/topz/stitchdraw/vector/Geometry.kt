package com.topz.stitchdraw.vector

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 2D point/vector. Design space is millimetres, +Y down (screen convention). */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec2(x * k, y * k)
    fun length(): Float = hypot(x, y)
    fun distanceTo(o: Vec2): Float = hypot(x - o.x, y - o.y)
    fun lerp(o: Vec2, t: Float) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object { val ZERO = Vec2(0f, 0f) }
}

/** Axis aligned bounds. [isEmpty] until at least one point is included. */
data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val isEmpty: Boolean get() = minX > maxX || minY > maxY
    val width: Float get() = if (isEmpty) 0f else maxX - minX
    val height: Float get() = if (isEmpty) 0f else maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f

    fun include(x: Float, y: Float) =
        if (isEmpty) Bounds(x, y, x, y) else Bounds(min(minX, x), min(minY, y), max(maxX, x), max(maxY, y))

    fun include(p: Vec2) = include(p.x, p.y)

    companion object {
        val EMPTY = Bounds(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        fun of(points: Iterable<Vec2>): Bounds = points.fold(EMPTY) { b, p -> b.include(p) }
    }
}

object Polyline {
    fun length(points: List<Vec2>): Float {
        var s = 0f
        for (i in 1 until points.size) s += points[i - 1].distanceTo(points[i])
        return s
    }

    /**
     * Splits every segment into equal parts no longer than [maxSpacing]. Original vertices are
     * always kept, so corners of the digitized path are hit exactly. Returns at least the input points.
     */
    fun resample(points: List<Vec2>, maxSpacing: Float): List<Vec2> {
        require(maxSpacing > 0f) { "spacing must be positive" }
        if (points.size < 2) return points.toList()
        val out = ArrayList<Vec2>()
        out.add(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val n = max(1, ceil(a.distanceTo(b) / maxSpacing - 1e-4f).toInt())
            for (k in 1..n) out.add(a.lerp(b, k.toFloat() / n))
        }
        return out
    }

    /** Distance from [p] to the segment a-b. */
    fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return p.distanceTo(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return p.distanceTo(Vec2(a.x + t * dx, a.y + t * dy))
    }

    fun distanceToPath(p: Vec2, path: List<Vec2>): Float {
        if (path.isEmpty()) return Float.POSITIVE_INFINITY
        if (path.size == 1) return p.distanceTo(path[0])
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until path.size) best = min(best, distanceToSegment(p, path[i - 1], path[i]))
        return best
    }
}

object Bezier {
    /** Flattens a cubic Bézier into a polyline whose deviation from the curve is below [tolerance]. */
    fun flattenCubic(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, tolerance: Float = 0.05f): List<Vec2> {
        val out = ArrayList<Vec2>()
        out.add(p0)
        subdivide(p0, p1, p2, p3, tolerance, 0, out)
        return out
    }

    private fun subdivide(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, tol: Float, depth: Int, out: MutableList<Vec2>) {
        val flat = max(Polyline.distanceToSegment(p1, p0, p3), Polyline.distanceToSegment(p2, p0, p3)) <= tol
        if (flat || depth >= 16) { out.add(p3); return }
        val p01 = p0.lerp(p1, .5f); val p12 = p1.lerp(p2, .5f); val p23 = p2.lerp(p3, .5f)
        val a = p01.lerp(p12, .5f); val b = p12.lerp(p23, .5f)
        val m = a.lerp(b, .5f)
        subdivide(p0, p01, a, m, tol, depth + 1, out)
        subdivide(m, b, p23, p3, tol, depth + 1, out)
    }

    fun pointAt(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: Float): Vec2 {
        val u = 1 - t
        val x = u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x
        val y = u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y
        return Vec2(x, y)
    }
}

internal fun approx(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps
