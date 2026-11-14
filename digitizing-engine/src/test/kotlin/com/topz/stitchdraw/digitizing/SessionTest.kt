package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.embroidery.DesignObject
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.vector.Vec2
import com.topz.stitchdraw.workspace.*
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private fun drawing(): DigitizingSession {
        val s = DigitizingSession()
        s.selectTool(DigitizingTool.RUNNING)
        s.addPoint(Vec2(0f, 0f)); s.addPoint(Vec2(10f, 0f)); s.addPoint(Vec2(10f, 10f))
        return s
    }

    @Test fun finishCommitsObjectAndPlanUpdates() {
        val s = drawing()
        val o = s.finishObject()!!
        assertEquals(StitchType.RUNNING, o.type)
        assertEquals(3, o.path.size)
        assertTrue(s.pending.isEmpty())
        assertTrue(s.plan.stats.stitchCount > 5)
    }

    @Test fun needsTwoPointsAndIgnoresDuplicates() {
        val s = DigitizingSession(); s.selectTool(DigitizingTool.RUNNING)
        assertTrue(s.addPoint(Vec2(1f, 1f))); assertFalse(s.addPoint(Vec2(1.05f, 1f)))
        assertNull(s.finishObject())
        assertFalse(DigitizingSession().addPoint(Vec2(1f, 1f))) // SELECT tool does not draw
    }

    @Test fun undoRedoPointsThenObjects() {
        val s = drawing()
        s.undo(); assertEquals(2, s.pending.size)
        s.finishObject(); assertEquals(1, s.design.objects.size)
        s.undo(); assertEquals(0, s.design.objects.size)
        s.redo(); assertEquals(1, s.design.objects.size)
    }

    @Test fun externallyConvertedVectorObjectUsesNormalUndoRedoHistory() {
        val s = DigitizingSession()
        val converted = DesignObject(
            id = s.design.nextObjectId, name = "Vector ellipse", type = StitchType.RUNNING,
            path = listOf(Vec2(-10f, 0f), Vec2(10f, 0f)), threadIndex = 0,
            layerId = s.design.layers.first().id, params = StitchParams(),
        )
        val added = s.addExternalObject(converted)
        assertEquals("Vector ellipse", s.design.obj(added.id)?.name)
        assertEquals(1, s.design.objects.size)
        s.undo()
        assertTrue(s.design.objects.isEmpty())
        s.redo()
        assertEquals("Vector ellipse", s.design.objects.single().name)
    }

    @Test fun editOperations() {
        val s = drawing(); val id = s.finishObject()!!.id
        s.changeStitchType(id, StitchType.TRIPLE_RUNNING)
        assertEquals(StitchType.TRIPLE_RUNNING, s.design.obj(id)!!.type)
        val dup = s.duplicateObject(id)!!
        assertEquals(2, s.design.objects.size)
        s.moveObject(dup.id, -1); assertEquals(dup.id, s.design.objects.first().id)
        s.setObjectThread(id, 3); assertEquals(3, s.design.obj(id)!!.threadIndex)
        s.deleteObject(id); assertNull(s.design.obj(id))
        assertEquals(dup.id, s.hitTest(Vec2(12f, 2f), 1f)?.id)
    }

    /**
     * Core design requirement: rearranging the workspace must never affect a digitizing
     * operation that is in progress.
     */
    @Test fun workspaceChangesNeverTouchDigitizingState() {
        val s = drawing()
        s.finishObject()
        s.addPoint(Vec2(30f, 30f)); s.addPoint(Vec2(40f, 35f))   // object in progress

        val designBefore = s.design
        val pendingBefore = s.pending.toList()
        val toolBefore = s.tool
        val planBefore = s.plan
        var notified = 0
        s.addListener { notified++ }

        val ws = WorkspaceController()
        val rnd = java.util.Random(7)
        val ids = PanelId.values()
        repeat(3000) {
            val id = ids[rnd.nextInt(ids.size)]
            ws.dispatch(when (rnd.nextInt(8)) {
                0 -> WorkspaceAction.ToggleFocus
                1 -> WorkspaceAction.Undock(id)
                2 -> WorkspaceAction.Dock(id, DockZone.values()[rnd.nextInt(3)])
                3 -> WorkspaceAction.Close(id)
                4 -> WorkspaceAction.Open(id)
                5 -> WorkspaceAction.SetMaximized(id, rnd.nextBoolean())
                6 -> WorkspaceAction.SetZoneCollapsed(DockZone.values()[rnd.nextInt(3)], rnd.nextBoolean())
                else -> WorkspaceAction.Replace(WorkspacePresets.create(WorkspacePresets.builtInNames[rnd.nextInt(WorkspacePresets.builtInNames.size)])!!)
            })
        }

        assertSame(designBefore, s.design)
        assertEquals(pendingBefore, s.pending)
        assertEquals(toolBefore, s.tool)
        assertSame(planBefore, s.plan)
        assertEquals(0, notified)
        // ...and digitizing continues normally afterwards.
        assertTrue(s.finishObject() != null)
        assertEquals(2, s.design.objects.size)
    }
}
