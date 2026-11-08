package com.topz.stitchdraw.vector

import org.junit.Assert.*
import org.junit.Test

class CurvesTest {
    private fun maxDeviation(poly: List<Vec2>, p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): Float {
        var worst = 0f
        for (i in 0..400) {
            val q = Curves.cubicAt(p0, p1, p2, p3, i / 400f)
            worst = maxOf(worst, Polyline.distanceToPath(q, poly))
        }
        return worst
    }

    @Test fun cubicIsWithinToleranceAndKeepsEndpoints() {
        val a = Vec2(0f, 0f); val b = Vec2(0f, 40f); val c = Vec2(60f, 40f); val d = Vec2(60f, 0f)
        for (tol in listOf(0.2f, 0.05f, 0.01f)) {
            val poly = Curves.cubic(a, b, c, d, tol)
            assertEquals(a, poly.first()); assertEquals(d, poly.last())
            assertTrue("tol=$tol dev=${maxDeviation(poly, a, b, c, d)}", maxDeviation(poly, a, b, c, d) <= tol)
        }
    }

    @Test fun straightCubicStaysShort() {
        val poly = Curves.cubic(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(20f, 0f), Vec2(30f, 0f))
        assertTrue(poly.size <= 3)
    }

    @Test fun degenerateCubicDoesNotCrash() {
        assertEquals(2, Curves.cubic(Vec2(5f, 5f), Vec2(5f, 5f), Vec2(5f, 5f), Vec2(5f, 5f)).size)
    }

    @Test fun smoothPassesThroughEveryPoint() {
        val pts = listOf(Vec2(0f, 0f), Vec2(10f, 15f), Vec2(25f, 5f), Vec2(40f, 20f), Vec2(55f, 0f))
        val s = Curves.smooth(pts)
        assertEquals(pts.first(), s.first()); assertEquals(pts.last(), s.last())
        for (p in pts) assertTrue("misses $p", Polyline.distanceToPath(p, s) < 0.01f)
        assertTrue(s.size > pts.size * 3)
    }

    @Test fun smoothClosedWrapsWithoutDuplicatingStart() {
        val sq = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 20f), Vec2(0f, 20f))
        val s = Curves.smooth(sq, closed = true)
        assertTrue(s.first().distanceTo(s.last()) > 0.05f)
        for (p in sq) assertTrue(Polyline.distanceToPath(p, s + s.first()) < 0.01f)
    }

    @Test fun smoothWithTwoPointsIsUnchanged() {
        val two = listOf(Vec2(0f, 0f), Vec2(5f, 5f))
        assertEquals(two, Curves.smooth(two))
    }

    @Test fun quadraticMatchesEquivalentCubicEndpoints() {
        val q = Curves.quadratic(Vec2(0f, 0f), Vec2(10f, 20f), Vec2(20f, 0f))
        assertEquals(Vec2(0f, 0f), q.first()); assertEquals(Vec2(20f, 0f), q.last())
        assertTrue(q.maxOf { it.y } in 9.5f..10.1f)       // apex of this parabola is at y = 10
    }
}
