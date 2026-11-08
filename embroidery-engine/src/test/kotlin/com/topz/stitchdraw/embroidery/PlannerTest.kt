package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PlannerTest {
    private fun obj(id: Int, type: StitchType, thread: Int, vararg p: Pair<Float, Float>, len: Float = 2.5f, lock: Boolean = false) =
        DesignObject(id, "o$id", type, p.map { Vec2(it.first, it.second) }, thread, 1, StitchParams(len, lock, lock))

    private fun design(vararg o: DesignObject) = o.fold(EmbroideryDesign()) { d, x -> d.addObject(x) }

    @Test fun runningStitchNeverExceedsLengthAndHitsEnds() {
        val plan = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 0f to 0f, 20f to 0f, len = 3f)))
        val st = plan.stitches.filter { it.cmd == StitchCommand.STITCH || it.cmd == StitchCommand.JUMP }
        assertEquals(StitchCommand.STITCH, st[1].cmd)
        for (i in 2 until st.size) assertTrue(hypot((st[i].x - st[i - 1].x).toDouble(), (st[i].y - st[i - 1].y).toDouble()) <= 30.5)
        assertEquals(200, st.last().x)
        assertEquals(StitchCommand.END, plan.stitches.last().cmd)
    }

    @Test fun noRoundingDriftOnManySegments() {
        val pts = (0..50).map { it * 1.37f to 0f }.toTypedArray()
        val plan = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, *pts, len = 0.9f)))
        assertEquals(50 * 1.37f * 10, plan.stitches.filter { it.cmd == StitchCommand.STITCH }.last().x.toFloat(), 1f)
    }

    @Test fun tripleRunCoversLineThreeTimes() {
        val single = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 0f to 0f, 10f to 0f)))
        val triple = StitchPlanner.plan(design(obj(1, StitchType.TRIPLE_RUNNING, 0, 0f to 0f, 10f to 0f)))
        assertEquals(single.stats.threadLengthMm * 3, triple.stats.threadLengthMm, 0.5f)
        val xs = triple.stitches.filter { it.cmd == StitchCommand.STITCH }.map { it.x }
        assertTrue(xs.contains(0))            // goes back to the start...
        assertEquals(100, xs.last())          // ...and ends at the far end after out-back-out
    }

    @Test fun colorChangeBetweenDifferentThreadsOnly() {
        val d = design(
            obj(1, StitchType.RUNNING, 0, 0f to 0f, 5f to 0f),
            obj(2, StitchType.RUNNING, 0, 5f to 1f, 8f to 1f),
            obj(3, StitchType.RUNNING, 1, 0f to 5f, 5f to 5f),
        )
        val plan = StitchPlanner.plan(d)
        assertEquals(1, plan.stats.colorChanges)
        assertEquals(1, plan.stitches.count { it.cmd == StitchCommand.COLOR_CHANGE })
        val cc = plan.stitches.indexOfFirst { it.cmd == StitchCommand.COLOR_CHANGE }
        assertEquals(0, plan.threadIndexOf[cc])          // change is issued while the old thread is current
        assertEquals(1, plan.threadIndexOf[cc + 1])      // everything after uses the new thread
    }

    @Test fun longMoveGetsTrimThenJump_shortMoveOnlyJump() {
        val far = StitchPlanner.plan(design(
            obj(1, StitchType.RUNNING, 0, 0f to 0f, 5f to 0f),
            obj(2, StitchType.RUNNING, 0, 30f to 0f, 35f to 0f)))
        assertEquals(1, far.stats.trimCount)
        val near = StitchPlanner.plan(design(
            obj(1, StitchType.RUNNING, 0, 0f to 0f, 5f to 0f),
            obj(2, StitchType.RUNNING, 0, 6f to 0f, 9f to 0f)))
        assertEquals(0, near.stats.trimCount)
        // the very first move from the machine origin is a jump but never a trim
        val first = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 50f to 50f, 55f to 50f)))
        assertEquals(0, first.stats.trimCount); assertEquals(1, first.stats.jumpCount)
    }

    @Test fun lockStitchesAddedWhenEnabled() {
        val no = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 0f to 0f, 10f to 0f, lock = false)))
        val yes = StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 0f to 0f, 10f to 0f, lock = true)))
        assertTrue(yes.stats.stitchCount > no.stats.stitchCount)
        assertEquals(StitchCommand.STITCH, yes.stitches.first().cmd) // origin == start, so no jump needed
    }

    @Test fun hiddenLayerAndDegenerateObjectsSkipped() {
        var d = design(obj(1, StitchType.RUNNING, 0, 0f to 0f, 5f to 0f))
        d = d.replaceLayer(d.layers[0].copy(visible = false))
        assertEquals(StitchPlan.EMPTY, StitchPlanner.plan(d))
        assertEquals(StitchPlan.EMPTY, StitchPlanner.plan(design(obj(1, StitchType.RUNNING, 0, 1f to 1f))))
    }

    @Test fun hexParsing() {
        assertEquals(0xFF8800, ThreadColor.parseHex("#f80")!!.rgb)
        assertEquals(0x123456, ThreadColor.parseHex("123456")!!.rgb)
        assertNull(ThreadColor.parseHex("#12345")); assertNull(ThreadColor.parseHex("zzzzzz"))
        assertEquals("#00FF7F", ThreadColor(0x00FF7F).hex)
    }
}

class PlanSequenceTest {
    @Test fun stitchCountsFollowObjectOrderEvenWithoutJumps() {
        // Two touching objects: no JUMP between them, boundaries must still be exact.
        fun o(id: Int, vararg p: Pair<Float, Float>) =
            DesignObject(id, "o$id", StitchType.RUNNING, p.map { Vec2(it.first, it.second) }, 0, 1, StitchParams(2f, false, false))
        val d = EmbroideryDesign().addObject(o(7, 0f to 0f, 10f to 0f)).addObject(o(9, 10f to 0f, 10f to 6f))
        val plan = StitchPlanner.plan(d)
        assertEquals(listOf(7 to 5, 9 to 3), plan.stitchCountsInOrder())
        assertEquals(plan.stats.stitchCount, plan.stitchCountsInOrder().sumOf { it.second })
    }
}
