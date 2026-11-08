package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class CurveModeTest {
    private fun session(): DigitizingSession = DigitizingSession().also { it.selectTool(DigitizingTool.SATIN) }

    @Test fun curveModeSmoothsFinishedPathButKeepsNodesEditableInUndo() {
        val s = session(); s.curveMode = true
        listOf(Vec2(0f, 0f), Vec2(10f, 10f), Vec2(20f, 0f), Vec2(30f, 10f)).forEach { s.addPoint(it) }
        assertEquals(4, s.pending.size)
        assertTrue(s.pendingPath.size > 12)
        val o = s.finishObject()!!
        assertTrue(o.path.size > 12)
        assertEquals(Vec2(0f, 0f), o.path.first()); assertEquals(Vec2(30f, 10f), o.path.last())
        s.undo()
        assertTrue(s.design.objects.isEmpty())
    }

    @Test fun straightModeIsUnchanged() {
        val s = session()
        listOf(Vec2(0f, 0f), Vec2(10f, 10f), Vec2(20f, 0f)).forEach { s.addPoint(it) }
        assertEquals(3, s.finishObject()!!.path.size)
    }

    @Test fun tatamiCurveIsClosedSmoothOutline() {
        val s = DigitizingSession().also { it.selectTool(DigitizingTool.TATAMI) }; s.curveMode = true
        listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 30f), Vec2(0f, 30f)).forEach { s.addPoint(it) }
        val o = s.finishObject()!!
        assertTrue(o.path.size > 20)
        assertTrue(s.plan.stitches.isNotEmpty())
    }
}
