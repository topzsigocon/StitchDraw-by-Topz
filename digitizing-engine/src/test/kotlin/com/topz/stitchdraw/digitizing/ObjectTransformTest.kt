package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class ObjectTransformTest {
    private fun session(): DigitizingSession {
        val s = DigitizingSession()
        s.selectTool(DigitizingTool.TATAMI)
        for (p in listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 10f), Vec2(0f, 10f))) s.addPoint(p)
        s.finishObject()
        s.selectTool(DigitizingTool.SELECT); s.select(s.design.objects.single().id)
        return s
    }
    private fun o(s: DigitizingSession) = s.design.objects.single()

    @Test fun moveShiftsEveryPointAndIsOneUndoStep() {
        val s = session()
        assertTrue(s.transformSelected(ObjectTransform(dxMm = 5f, dyMm = -3f)))
        assertEquals(5f, o(s).path.minOf { it.x }, 1e-4f); assertEquals(-3f, o(s).path.minOf { it.y }, 1e-4f)
        s.undo(); assertEquals(0f, o(s).path.minOf { it.x }, 1e-4f)
        s.redo(); assertEquals(5f, o(s).path.minOf { it.x }, 1e-4f)
    }

    @Test fun scaleAboutCentre() {
        val s = session(); s.transformSelected(ObjectTransform(scale = 2f))
        val xs = o(s).path.map { it.x }; val ys = o(s).path.map { it.y }
        assertEquals(40f, xs.max() - xs.min(), 1e-3f); assertEquals(20f, ys.max() - ys.min(), 1e-3f)
        assertEquals(10f, (xs.max() + xs.min()) / 2, 1e-3f)       // centre unchanged
    }

    @Test fun rotate90SwapsExtentsAndTurnsFillAngle() {
        val s = session(); val a0 = o(s).params.angleDeg
        s.transformSelected(ObjectTransform(rotateDeg = 90f))
        val xs = o(s).path.map { it.x }; val ys = o(s).path.map { it.y }
        assertEquals(10f, xs.max() - xs.min(), 1e-3f); assertEquals(20f, ys.max() - ys.min(), 1e-3f)
        assertEquals((a0 + 90f) % 180f, o(s).params.angleDeg, 1e-3f)
    }

    @Test fun mirrorKeepsCentreAndHolesFollow() {
        val s = DigitizingSession()
        val outer = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 30f), Vec2(0f, 30f))
        val hole = listOf(Vec2(2f, 2f), Vec2(8f, 2f), Vec2(8f, 8f), Vec2(2f, 8f))
        s.load(EmbroideryDesign().addObject(DesignObject(1, "x", StitchType.TATAMI, outer, 0, 1, StitchParams(), listOf(hole))))
        s.select(1); s.transformSelected(ObjectTransform(flipH = true))
        val h = s.design.obj(1)!!.holes.single()
        assertEquals(22f, h.minOf { it.x }, 1e-3f); assertEquals(28f, h.maxOf { it.x }, 1e-3f)
        assertEquals(2f, h.minOf { it.y }, 1e-3f)
    }

    @Test fun livePreviewDoesNotAccumulateAndCancelRestores() {
        val s = session()
        assertTrue(s.beginTransform())
        for (i in 1..50) s.previewTransform(ObjectTransform(dxMm = i.toFloat()))
        assertEquals(50f, o(s).path.minOf { it.x }, 1e-3f)              // 50, not 1275
        s.cancelTransform()
        assertEquals(0f, o(s).path.minOf { it.x }, 1e-4f); assertFalse(s.canUndo.and(s.design.objects.size != 1))
    }

    @Test fun identityRecordsNoHistoryAndScaleIsClamped() {
        val s = session(); val before = s.design
        s.beginTransform(); s.previewTransform(ObjectTransform()); s.endTransform()
        assertEquals(before, s.design)
        s.undo()                                                         // undoes the object creation only
        assertTrue(s.design.objects.isEmpty())
        val s2 = session(); s2.transformSelected(ObjectTransform(scale = 1000f))
        assertEquals(20f * 20f, o(s2).path.maxOf { it.x } - o(s2).path.minOf { it.x }, 1e-2f)
    }

    @Test fun lockedLayerAndNoSelectionRefuse() {
        val s = DigitizingSession()
        assertFalse(s.transformSelected(ObjectTransform(dxMm = 1f)))
        val s2 = session(); s2.toggleLayerLocked(1)
        assertFalse(s2.transformSelected(ObjectTransform(dxMm = 1f)))
    }

    @Test fun transformedObjectStillStitches() {
        val s = session(); s.transformSelected(ObjectTransform(scale = 1.5f, rotateDeg = 33f, dxMm = 4f))
        assertTrue(s.plan.stitches.size > 200)
    }
}

class OptimizeSequenceSessionTest {
    @Test fun optimiseIsOneUndoStepAndReportsNumbers() {
        val s = DigitizingSession()
        val sq = { x: Float -> listOf(Vec2(x, 0f), Vec2(x + 8f, 0f), Vec2(x + 8f, 8f), Vec2(x, 8f)) }
        var d = EmbroideryDesign()
        for ((i, p) in listOf(Triple(-40f, 0, 1), Triple(-28f, 1, 2), Triple(28f, 0, 3), Triple(40f, 1, 4)).withIndex())
            d = d.addObject(DesignObject(p.third, "o$i", StitchType.TATAMI, sq(p.first), p.second, 1, StitchParams()))
        s.load(d)
        val order0 = s.design.objects.map { it.id }
        val r = s.optimizeSequence()
        assertTrue(r.changed)
        assertTrue(r.after.colorChanges < r.before.colorChanges)
        assertNotEquals(order0, s.design.objects.map { it.id })
        s.undo(); assertEquals(order0, s.design.objects.map { it.id })
        s.redo(); s.optimizeSequence().let { assertFalse(it.changed) }          // already optimal: no new history step
    }
}
