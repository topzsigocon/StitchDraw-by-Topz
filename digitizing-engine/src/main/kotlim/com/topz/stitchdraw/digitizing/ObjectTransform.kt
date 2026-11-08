package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.embroidery.DesignObject
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Move / uniform scale / rotate / mirror of one object, applied about the centre of its bounding box:
 * p' = centre + move + R(rotate) * S(scale) * F(flip) * (p - centre). Rotation is clockwise on screen (+Y down).
 */
data class ObjectTransform(
    val dxMm: Float = 0f, val dyMm: Float = 0f,
    val scale: Float = 1f,
    val rotateDeg: Float = 0f,
    val flipH: Boolean = false, val flipV: Boolean = false,
) {
    val isIdentity get() = dxMm == 0f && dyMm == 0f && scale == 1f && rotateDeg == 0f && !flipH && !flipV

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f

        fun bounds(o: DesignObject): Pair<Vec2, Vec2>? {
            if (o.path.isEmpty()) return null
            return Vec2(o.path.minOf { it.x }, o.path.minOf { it.y }) to Vec2(o.path.maxOf { it.x }, o.path.maxOf { it.y })
        }

        fun apply(o: DesignObject, t: ObjectTransform): DesignObject {
            val (lo, hi) = bounds(o) ?: return o
            val cx = (lo.x + hi.x) / 2; val cy = (lo.y + hi.y) / 2
            val s = t.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            val r = Math.toRadians(t.rotateDeg.toDouble()); val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
            fun f(p: Vec2): Vec2 {
                var x = (p.x - cx) * s * (if (t.flipH) -1f else 1f)
                var y = (p.y - cy) * s * (if (t.flipV) -1f else 1f)
                val rx = x * cs - y * sn; val ry = x * sn + y * cs
                return Vec2(cx + t.dxMm + rx, cy + t.dyMm + ry)
            }
            // tatami row direction follows the shape: mirror, then rotate
            var a = o.params.angleDeg
            if (t.flipH) a = 180f - a
            if (t.flipV) a = -a
            a += t.rotateDeg
            a = ((a % 180f) + 180f) % 180f
            return o.copy(path = o.path.map(::f), holes = o.holes.map { h -> h.map(::f) }, params = o.params.copy(angleDeg = a))
        }
    }
}
