package com.topz.stitchdraw.vector

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun resampleKeepsCornersAndMaxSpacing() {
        val pts = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 7f))
        val r = Polyline.resample(pts, 3f)
        assertTrue(r.contains(Vec2(10f, 0f)))
        assertEquals(pts.first(), r.first()); assertEquals(pts.last(), r.last())
        for (i in 1 until r.size) assertTrue(r[i - 1].distanceTo(r[i]) <= 3f + 1e-3f)
        assertEquals(Polyline.length(pts), Polyline.length(r), 1e-3f)
    }
    @Test fun resampleShortSegmentNotSubdivided() =
        assertEquals(2, Polyline.resample(listOf(Vec2(0f, 0f), Vec2(1f, 0f)), 3f).size)
    @Test fun boundsOfPoints() {
        val b = Bounds.of(listOf(Vec2(-2f, 1f), Vec2(4f, 5f)))
        assertEquals(6f, b.width, 0f); assertEquals(4f, b.height, 0f)
        assertTrue(Bounds.EMPTY.isEmpty); assertEquals(0f, Bounds.EMPTY.width, 0f)
    }
    @Test fun bezierFlattenStaysOnCurve() {
        val p = arrayOf(Vec2(0f, 0f), Vec2(0f, 20f), Vec2(20f, 20f), Vec2(20f, 0f))
        val poly = Bezier.flattenCubic(p[0], p[1], p[2], p[3], 0.05f)
        assertTrue(poly.size > 8)
        for (i in 0..20) {
            val c = Bezier.pointAt(p[0], p[1], p[2], p[3], i / 20f)
            assertTrue(Polyline.distanceToPath(c, poly) < 0.1f)
        }
    }
    @Test fun distanceToPath() =
        assertEquals(2f, Polyline.distanceToPath(Vec2(5f, 2f), listOf(Vec2(0f, 0f), Vec2(10f, 0f))), 1e-4f)
}
