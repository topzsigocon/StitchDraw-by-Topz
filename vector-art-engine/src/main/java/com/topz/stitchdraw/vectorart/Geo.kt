package com.topz.stitchdraw.vectorart

import android.graphics.*
import kotlin.math.*

object Geo {
    private val CS = floatArrayOf(1f, 0f, -1f, 0f)
    private val SN = floatArrayOf(0f, 1f, 0f, -1f)
    val BASIC = listOf("Triangle", "Diamond", "Right arrow", "Cross", "Heart")
    private const val TAU = 6.2831855f

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = mutableListOf(Node(x0, y0), Node(x1, y0), Node(x1, y1), Node(x0, y1))

    /** General (rotated) ellipse from centre and two conjugate semi-axis vectors a and b. */
    fun ellipse(cx: Float, cy: Float, ax: Float, ay: Float, bx: Float, by: Float): MutableList<Node> {
        val k = 0.5523f
        return MutableList(4) { i ->
            val c = CS[i]; val s = SN[i]
            val px = cx + ax * c + bx * s; val py = cy + ay * c + by * s
            val tx = -ax * s + bx * c; val ty = -ay * s + by * c
            Node(px, py).also { it.ox = px + k * tx; it.oy = py + k * ty; it.ix = px - k * tx; it.iy = py - k * ty; it.smooth = true }
        }
    }

    fun polygon(cx: Float, cy: Float, r: Float, a0: Float, n: Int) =
        MutableList(n) { i -> val a = a0 + TAU * i / n; Node(cx + r * cos(a), cy + r * sin(a)) }

    fun star(cx: Float, cy: Float, r: Float, a0: Float, n: Int, inner: Float = 0.45f) =
        MutableList(n * 2) { i -> val rr = if (i % 2 == 0) r else r * inner; val a = a0 + TAU / 2 * i / n; Node(cx + rr * cos(a), cy + rr * sin(a)) }

    fun spiral(cx: Float, cy: Float, r: Float, a0: Float, turns: Float = 4f): MutableList<Node> {
        val pts = List(81) { i -> val t = i / 80f; val a = a0 + turns * TAU * t; PointF(cx + r * t * cos(a), cy + r * t * sin(a)) }
        return smooth(pts, false)
    }

    fun basic(i: Int, x0: Float, y0: Float, x1: Float, y1: Float): MutableList<Node> {
        val l = min(x0, x1); val t = min(y0, y1); val w = abs(x1 - x0); val h = abs(y1 - y0)
        fun p(u: Float, v: Float) = Node(l + u * w, t + v * h)
        return when (i) {
            0 -> mutableListOf(p(.5f, 0f), p(1f, 1f), p(0f, 1f))
            1 -> mutableListOf(p(.5f, 0f), p(1f, .5f), p(.5f, 1f), p(0f, .5f))
            2 -> mutableListOf(p(0f, .35f), p(.6f, .35f), p(.6f, .1f), p(1f, .5f), p(.6f, .9f), p(.6f, .65f), p(0f, .65f))
            3 -> mutableListOf(p(.35f, 0f), p(.65f, 0f), p(.65f, .35f), p(1f, .35f), p(1f, .65f), p(.65f, .65f), p(.65f, 1f), p(.35f, 1f), p(.35f, .65f), p(0f, .65f), p(0f, .35f), p(.35f, .35f))
            else -> {
                val pts = List(48) { k ->
                    val a = TAU * k / 48
                    val x = 16 * sin(a).pow(3); val y = 13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a)
                    PointF(l + (x + 16f) / 32f * w, t + (1f - (y + 17f) / 29f) * h)
                }
                smooth(pts, true)
            }
        }
    }

    /** Catmull-Rom -> cubic bezier nodes. */
    fun smooth(p: List<PointF>, closed: Boolean): MutableList<Node> {
        val n = p.size; val out = mutableListOf<Node>()
        for (i in 0 until n) {
            val a = if (i > 0) p[i - 1] else if (closed) p[n - 1] else p[i]
            val b = if (i < n - 1) p[i + 1] else if (closed) p[0] else p[i]
            val tx = (b.x - a.x) / 6f; val ty = (b.y - a.y) / 6f
            out.add(Node(p[i].x, p[i].y).also { it.ix = it.x - tx; it.iy = it.y - ty; it.ox = it.x + tx; it.oy = it.y + ty; it.smooth = true })
        }
        return out
    }

    fun segDist(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
        if (l2 == 0f) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    fun rdp(p: List<PointF>, eps: Float): List<PointF> {
        if (p.size < 3) return p
        var idx = 0; var dmax = 0f; val a = p.first(); val b = p.last()
        for (i in 1 until p.size - 1) { val d = segDist(p[i].x, p[i].y, a, b); if (d > dmax) { dmax = d; idx = i } }
        return if (dmax > eps) rdp(p.subList(0, idx + 1), eps).dropLast(1) + rdp(p.subList(idx, p.size), eps) else listOf(a, b)
    }

    fun flatten(p: Path, step: Float): List<PointF> {
        val pm = PathMeasure(p, false); val len = pm.length
        if (len <= 0f) return emptyList()
        val n = max(8, min(1500, (len / step).toInt())); val pos = FloatArray(2)
        return List(n + 1) { pm.getPosTan(len * it / n, pos, null); PointF(pos[0], pos[1]) }
    }

    fun inPoly(pts: List<PointF>, x: Float, y: Float): Boolean {
        var inside = false; var j = pts.size - 1
        for (i in pts.indices) {
            if ((pts[i].y > y) != (pts[j].y > y) && x < (pts[j].x - pts[i].x) * (y - pts[i].y) / (pts[j].y - pts[i].y) + pts[i].x) inside = !inside
            j = i
        }
        return inside
    }

    /** Split cubic segment a->b at t; returns the new node (handles of a and b are updated). */
    fun splitSeg(a: Node, b: Node, t: Float): Node {
        if (a.outLine() && b.inLine()) return Node(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        fun l(p: Float, q: Float) = p + (q - p) * t
        val q0x = l(a.x, a.ox); val q0y = l(a.y, a.oy); val q1x = l(a.ox, b.ix); val q1y = l(a.oy, b.iy)
        val q2x = l(b.ix, b.x); val q2y = l(b.iy, b.y)
        val r0x = l(q0x, q1x); val r0y = l(q0y, q1y); val r1x = l(q1x, q2x); val r1y = l(q1y, q2y)
        val n = Node(l(r0x, r1x), l(r0y, r1y)).also { it.ix = r0x; it.iy = r0y; it.ox = r1x; it.oy = r1y; it.smooth = true }
        a.ox = q0x; a.oy = q0y; b.ix = q2x; b.iy = q2y
        return n
    }

    fun segPoint(a: Node, b: Node, t: Float): PointF {
        if (a.outLine() && b.inLine()) return PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        val u = 1 - t
        return PointF(u * u * u * a.x + 3 * u * u * t * a.ox + 3 * u * t * t * b.ix + t * t * t * b.x,
            u * u * u * a.y + 3 * u * u * t * a.oy + 3 * u * t * t * b.iy + t * t * t * b.y)
    }

    /** Intersection of segments p1-p2 and p3-p4: returns (t on p1p2, u on p3p4) or null. */
    fun segInter(p1: PointF, p2: PointF, p3: PointF, p4: PointF): FloatArray? {
        val d1x = p2.x - p1.x; val d1y = p2.y - p1.y; val d2x = p4.x - p3.x; val d2y = p4.y - p3.y
        val den = d1x * d2y - d1y * d2x; if (abs(den) < 1e-9f) return null
        val t = ((p3.x - p1.x) * d2y - (p3.y - p1.y) * d2x) / den
        val u = ((p3.x - p1.x) * d1y - (p3.y - p1.y) * d1x) / den
        return if (t in 0f..1f && u in 0f..1f) floatArrayOf(t, u) else null
    }
}
