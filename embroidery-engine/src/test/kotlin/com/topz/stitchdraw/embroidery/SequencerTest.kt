package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class SequencerTest {
    private fun sq(x: Float, y: Float, s: Float = 8f) = listOf(Vec2(x, y), Vec2(x + s, y), Vec2(x + s, y + s), Vec2(x, y + s))
    private fun fill(id: Int, x: Float, y: Float, thread: Int, s: Float = 8f) =
        DesignObject(id, "f$id", StitchType.TATAMI, sq(x, y, s), thread, 1, StitchParams())
    private fun line(id: Int, a: Vec2, b: Vec2, thread: Int = 0) =
        DesignObject(id, "l$id", StitchType.RUNNING, listOf(a, b), thread, 1, StitchParams())
    private fun design(vararg o: DesignObject) = EmbroideryDesign(objects = o.toList(), nextObjectId = 100)

    @Test fun nearbyObjectsOfOneColourAreVisitedInSensibleOrder() {
        val d = design(fill(1, -40f, 0f, 0), fill(2, 40f, 0f, 0), fill(3, -28f, 0f, 0), fill(4, 28f, 0f, 0))
        val r = Sequencer.optimize(d)
        assertTrue(r.changed)
        assertTrue("${r.before.travelMm} -> ${r.after.travelMm}", r.after.travelMm < r.before.travelMm * 0.6f)
        assertEquals(setOf(1, 2, 3, 4), r.objects.map { it.id }.toSet())
    }

    @Test fun alternatingColoursAreGrouped() {
        val d = design(fill(1, -40f, 0f, 0), fill(2, -28f, 0f, 1), fill(3, 28f, 0f, 0), fill(4, 40f, 0f, 1))
        val r = Sequencer.optimize(d)
        assertEquals(3, r.before.colorChanges)
        assertEquals(1, r.after.colorChanges)
    }

    @Test fun overlappingDifferentColoursKeepTheirOrder() {
        // red big square, blue small one on top of it, another red far away: blue must stay after the big red
        val d = design(fill(1, 0f, 0f, 0, 20f), fill(2, 5f, 5f, 1, 6f), fill(3, 60f, 0f, 0))
        val r = Sequencer.optimize(d)
        val ids = r.objects.map { it.id }
        assertTrue(ids.indexOf(2) > ids.indexOf(1))
        assertEquals(1, r.after.colorChanges)                       // R, R, B
        // and the mirror case: blue underneath, red on top, blue far away
        val d2 = design(fill(1, 0f, 0f, 1, 20f), fill(2, 5f, 5f, 0, 6f), fill(3, 60f, 0f, 1))
        val ids2 = Sequencer.optimize(d2).objects.map { it.id }
        assertTrue(ids2.indexOf(2) > ids2.indexOf(1))
    }

    @Test fun linesAreReversedWhenThatShortensTheJump() {
        val d = design(line(1, Vec2(-60f, 0f), Vec2(-40f, 0f)), line(2, Vec2(40f, 0f), Vec2(-38f, 3f)), line(3, Vec2(-36f, 6f), Vec2(30f, 6f)))
        val r = Sequencer.optimize(d)
        assertTrue(r.changed)
        assertTrue(r.after.travelMm < r.before.travelMm)
        assertEquals(setOf(Vec2(40f, 0f), Vec2(-38f, 3f)), r.objects.first { it.id == 2 }.path.toSet())     // still the same line
    }

    @Test fun fillsAreNeverReversedOrAltered() {
        val d = design(fill(1, -40f, 0f, 0), fill(2, 40f, 0f, 0), fill(3, -28f, 0f, 0))
        val r = Sequencer.optimize(d)
        for (o in r.objects) assertEquals(d.obj(o.id), o)
    }

    @Test fun neverWorseAndIdempotentOnShuffledDesigns() {
        val rnd = java.util.Random(7)
        repeat(12) {
            val objs = (1..12).map { i -> fill(i, rnd.nextInt(100) - 50f, rnd.nextInt(80) - 40f, rnd.nextInt(3), 6f) }
            val d = EmbroideryDesign(objects = objs, nextObjectId = 100)
            val r = Sequencer.optimize(d)
            assertTrue(r.after.colorChanges <= r.before.colorChanges)
            assertTrue(r.after.travelMm + 200f * r.after.colorChanges <= r.before.travelMm + 200f * r.before.colorChanges + 0.1f)
            assertEquals(objs.map { it.id }.toSet(), r.objects.map { it.id }.toSet())
            val again = Sequencer.optimize(d.copy(objects = r.objects))
            assertFalse(again.changed && again.after.travelMm + 200f * again.after.colorChanges > r.after.travelMm + 200f * r.after.colorChanges)
            val inPlan = StitchPlanner.plan(d.copy(objects = r.objects)).objectOf.filter { it >= 0 }.toSet()
            assertEquals(StitchPlanner.plan(d).objectOf.filter { it >= 0 }.toSet(), inPlan)   // every object is still sewn
        }
    }

    @Test fun hiddenLayerObjectsKeepTheirSlotAndTinyDesignsAreLeftAlone() {
        val d = EmbroideryDesign(layers = listOf(Layer(1, "a"), Layer(2, "hidden", visible = false)),
            objects = listOf(fill(1, -40f, 0f, 0), fill(5, 0f, 0f, 0).copy(layerId = 2), fill(2, 40f, 0f, 0), fill(3, -28f, 0f, 0)), nextObjectId = 100)
        val r = Sequencer.optimize(d)
        assertEquals(5, r.objects[1].id)
        assertFalse(Sequencer.optimize(design(fill(1, 0f, 0f, 0), fill(2, 30f, 0f, 1))).changed)
    }
}

class SequencerScaleTest {
    @Test fun threeHundredObjectsStayFastAndImprove() {
        val rnd = java.util.Random(3)
        val objs = (1..300).map { i ->
            val x = rnd.nextInt(180) - 90f; val y = rnd.nextInt(180) - 90f
            DesignObject(i, "o$i", StitchType.TATAMI, listOf(Vec2(x, y), Vec2(x + 5f, y), Vec2(x + 5f, y + 5f), Vec2(x, y + 5f)), rnd.nextInt(4), 1, StitchParams())
        }
        val d = EmbroideryDesign(hoop = Hoop.HOOP_100, objects = objs, nextObjectId = 1000)
        val t0 = System.nanoTime()
        val r = Sequencer.optimize(d)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("SEQ300: ${r.before} -> ${r.after} in $ms ms")
        assertTrue("took $ms ms", ms < 15000)
        assertTrue(r.changed)
        assertTrue(r.after.colorChanges < r.before.colorChanges)
    }
}
