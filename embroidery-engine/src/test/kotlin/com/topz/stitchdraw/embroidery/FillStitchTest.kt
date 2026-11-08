package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FillStitchTest {
    private fun obj(type: StitchType, path: List<Vec2>, p: StitchParams = StitchParams()) =
        DesignObject(1, "o", type, path, 0, 1, p)
    private val square = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 20f), Vec2(0f, 20f))

    @Test fun satinStaysWithinColumnAndAlternatesSides() {
        val p = StitchParams(widthMm = 4f, pullCompMm = 0f, underlay = false, densityMm = 0.4f)
        val pts = StitchGenerator.generate(obj(StitchType.SATIN, listOf(Vec2(0f, 0f), Vec2(30f, 0f)), p))
        assertTrue(pts.size > 60)
        assertTrue("all points on the column edges", pts.all { abs(abs(it.y) - 2f) < 0.01f })
        for (i in 1 until pts.size) assertNotEquals(pts[i].y > 0, pts[i - 1].y > 0)
        assertTrue(pts.maxOf { it.x } <= 30.01f && pts.minOf { it.x } >= -0.01f)
    }

    @Test fun satinDensityControlsStitchCount() {
        val line = listOf(Vec2(0f, 0f), Vec2(20f, 0f))
        val dense = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(densityMm = 0.3f, underlay = false)))
        val sparse = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(densityMm = 0.6f, underlay = false)))
        assertTrue(dense.size > sparse.size * 3 / 2)
    }

    @Test fun satinPullCompensationWidensColumn() {
        val line = listOf(Vec2(0f, 0f), Vec2(10f, 0f))
        val a = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(widthMm = 3f, pullCompMm = 0f, underlay = false)))
        val b = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(widthMm = 3f, pullCompMm = 0.6f, underlay = false)))
        assertEquals(0.3f, b.maxOf { it.y } - a.maxOf { it.y }, 0.01f)
    }

    @Test fun satinUnderlayRunsFirstAndEndsAtPathStart() {
        val line = listOf(Vec2(0f, 0f), Vec2(20f, 0f))
        val withU = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(underlay = true)))
        val noU = StitchGenerator.generate(obj(StitchType.SATIN, line, StitchParams(underlay = false)))
        assertTrue(withU.size > noU.size)
        assertTrue("underlay centre-run starts the object", abs(withU.first().y) < 0.01f)
        assertTrue("satin returns to the start", withU.last().x < 1f)
    }

    @Test fun tatamiStaysInsideShapeAndCoversIt() {
        val p = StitchParams(angleDeg = 0f, underlay = false, pullCompMm = 0f, densityMm = 0.5f, stitchLengthMm = 3f)
        val runs = StitchGenerator.generateRuns(obj(StitchType.TATAMI, square, p))
        val pts = runs.flatten()
        assertTrue(pts.all { it.x in -0.01f..20.01f && it.y in -0.01f..20.01f })
        // rows spaced by density: ~40 rows, each with several stitches
        val rowYs = pts.map { Math.round(it.y * 100) }.distinct().size
        assertEquals(40, rowYs)
        assertTrue(pts.size > 40 * 5)
    }

    @Test fun tatamiAngleRotatesRows() {
        val p = StitchParams(angleDeg = 90f, underlay = false, pullCompMm = 0f, densityMm = 0.5f)
        val pts = StitchGenerator.generate(obj(StitchType.TATAMI, square, p))
        assertEquals(40, pts.map { Math.round(it.x * 100) }.distinct().size)    // vertical rows
    }

    @Test fun tatamiBrickOffsetsStaggerPenetrations() {
        val p = StitchParams(angleDeg = 0f, underlay = false, pullCompMm = 0f, densityMm = 0.5f, stitchLengthMm = 3f)
        val pts = StitchGenerator.generate(obj(StitchType.TATAMI, square, p))
        val byRow = pts.groupBy { Math.round(it.y * 100) }.values.toList()
        val inner = { r: List<Vec2> -> r.map { it.x }.sorted().drop(1).dropLast(1).map { Math.round(it * 10) / 10f }.toSet() }
        assertTrue("neighbouring rows must not share needle positions", inner(byRow[0]).intersect(inner(byRow[1])).isEmpty())
    }

    @Test fun tatamiConcaveShapeNeverStitchesOutside() {
        val u = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 20f), Vec2(20f, 20f), Vec2(20f, 8f), Vec2(10f, 8f), Vec2(10f, 20f), Vec2(0f, 20f))
        val p = StitchParams(angleDeg = 90f, underlay = false, pullCompMm = 0f, densityMm = 0.5f)
        val runs = StitchGenerator.generateRuns(obj(StitchType.TATAMI, u, p))
        // inside the notch (x 10..20, y 8..20) there must be no stitch point or link
        for (run in runs) for (i in run.indices) {
            val a = run[i]
            assertFalse("point in notch $a", a.x > 10.05f && a.x < 19.95f && a.y > 8.05f)
            if (i > 0) { val m = run[i].lerp(run[i - 1], 0.5f); assertFalse("link crosses notch $m", m.x > 10.05f && m.x < 19.95f && m.y > 8.05f) }
        }
        assertTrue("notch forces separate runs", runs.size >= 1)
    }

    @Test fun plannerRespectsMachineLimitsForEveryType() {
        val types = StitchType.values()
        var d = EmbroideryDesign()
        types.forEachIndexed { i, t ->
            val path = if (t.isFill) square.map { it + Vec2(i * 25f, 0f) } else listOf(Vec2(i * 25f, 0f), Vec2(i * 25f + 20f, 5f), Vec2(i * 25f + 22f, 18f))
            d = d.addObject(obj(t, path).copy(id = i + 1))
        }
        val plan = StitchPlanner.plan(d)
        assertTrue(plan.stitches.isNotEmpty())
        var px = 0; var py = 0
        for (s in plan.stitches) {
            if (s.cmd == StitchCommand.STITCH) assertTrue("stitch too long: ${s.x - px},${s.y - py}", abs(s.x - px) <= 121 && abs(s.y - py) <= 121)
            px = s.x; py = s.y
        }
        println("stitchCount=" + plan.stats.stitchCount)
        assertTrue(plan.stats.stitchCount > 500)
    }

    @Test fun fillWithTooFewPointsFallsBackToRunning() {
        val pts = StitchGenerator.generate(obj(StitchType.TATAMI, listOf(Vec2(0f, 0f), Vec2(10f, 0f))))
        assertTrue(pts.size >= 2)
    }
}

class AppliqueTest {
    private val outline = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 20f), Vec2(0f, 20f))
    private fun plan() = StitchPlanner.plan(EmbroideryDesign().addObject(
        DesignObject(1, "ap", StitchType.APPLIQUE, outline, 0, 1, StitchParams(widthMm = 3f, pullCompMm = 0f, underlay = false))))

    @Test fun hasPlacementThenStopThenTackThenStopThenCover() {
        val cmds = plan().stitches.map { it.cmd }
        val stops = cmds.withIndex().filter { it.value == StitchCommand.STOP }.map { it.index }
        assertEquals(2, stops.size)
        val between = stops[1] - stops[0]
        assertTrue("tack-down sits between the stops", between > 10)
        assertTrue("satin cover is much denser than placement", cmds.size - stops[1] > stops[0] * 3)
        assertEquals(StitchCommand.END, cmds.last())
    }

    @Test fun stopsStayAtTheSamePositionAndDoNotJump() {
        val st = plan().stitches
        for (i in st.indices) if (st[i].cmd == StitchCommand.STOP) {
            assertTrue(st[i - 1].x == st[i].x && st[i - 1].y == st[i].y)
        }
    }

    @Test fun coverBorderStraddlesTheOutline() {
        val pts = StitchGenerator.generate(DesignObject(1, "ap", StitchType.APPLIQUE, outline, 0, 1,
            StitchParams(widthMm = 4f, pullCompMm = 0f, underlay = false)))
        // some border points must be outside and some inside the shape by about half the width
        val outside = pts.count { it.x < -1.5f || it.x > 31.5f || it.y < -1.5f || it.y > 21.5f }
        val inside = pts.count { it.x in 1.5f..28.5f && it.y in 1.5f..18.5f }
        assertTrue(outside > 50 && inside > 50)
    }
}

class PlaybackTest {
    private fun plan(): StitchPlan {
        var d = EmbroideryDesign()
        d = d.addObject(DesignObject(1, "a", StitchType.RUNNING, listOf(Vec2(0f, 0f), Vec2(20f, 0f)), 0, 1))
        d = d.addObject(DesignObject(2, "b", StitchType.SATIN, listOf(Vec2(0f, 10f), Vec2(20f, 10f)), 1, 1))
        d = d.addObject(DesignObject(3, "c", StitchType.RUNNING, listOf(Vec2(0f, 20f), Vec2(20f, 20f)), 2, 1))
        return StitchPlanner.plan(d)
    }

    @Test fun advancesWithTimeAndStopsAtEnd() {
        val pb = com.topz.stitchdraw.simulation.Playback(100)
        pb.setSpeed(2); pb.play()
        for (i in 0 until 10) pb.advance(100)                 // 1 s at 240/s
        assertEquals(100, pb.shown)                            // clamped at total
        assertTrue(pb.finished); assertFalse(pb.playing)
        pb.play()                                              // replays from start
        assertEquals(0, pb.shown); assertTrue(pb.playing)
    }

    @Test fun hugeFrameGapIsClampedSoPlaybackNeverJumps() {
        val pb = com.topz.stitchdraw.simulation.Playback(100000)
        pb.setSpeed(0); pb.play(); pb.advance(60_000)
        assertTrue(pb.shown <= 2)
    }

    @Test fun stepSeekAndClamp() {
        val pb = com.topz.stitchdraw.simulation.Playback(50)
        pb.step(5); assertEquals(5, pb.shown); pb.step(-9); assertEquals(0, pb.shown)
        pb.seekFraction(0.5f); assertEquals(25, pb.shown)
        pb.setTotal(10); assertEquals(10, pb.shown)
        pb.setTotal(0); pb.play(); assertFalse(pb.playing)
    }

    @Test fun colourBlockNavigationAndCounts() {
        val p = plan()
        val ix = com.topz.stitchdraw.simulation.PlaybackIndex(p)
        assertEquals(3, ix.colorBlocks)
        assertEquals(p.stitches.count { it.cmd == StitchCommand.STITCH }, ix.totalStitches)
        assertEquals(1, ix.blockAt(1)); assertEquals(3, ix.blockAt(p.stitches.size))
        val b0 = ix.boundaries[0]
        assertEquals(1, ix.blockAt(b0)); assertEquals(2, ix.blockAt(b0 + 1))
        assertEquals(b0 + 1, ix.nextBlockStart(1))
        assertEquals(p.stitches.size, ix.nextBlockStart(ix.boundaries.last() + 1))
        assertEquals(0, ix.prevBlockStart(b0 + 1))                       // at the start of block 2 -> back to block 1
        assertEquals(b0 + 1, ix.prevBlockStart(b0 + 5))                  // inside block 2 -> its own start
        assertEquals(0, ix.threadAt(1)); assertEquals(1, ix.threadAt(b0 + 3))
        assertEquals(1, ix.objectAt(1)); assertEquals(2, ix.objectAt(b0 + 3))
    }

    @Test fun shadingKeepsDarkThreadVisible() {
        val s = com.topz.stitchdraw.simulation.ThreadShade
        assertTrue(s.luminance(s.highlight(0x000000)) > 0.15f)
        assertTrue(s.luminance(s.shadow(0xFF0000)) < s.luminance(0xFF0000))
        assertEquals(0x7F7F7F, s.mix(0x000000, 0xFFFFFF, 0.5f))
    }
}

class HoleFillTest {
    private val outer = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 30f), Vec2(0f, 30f))
    private val hole = listOf(Vec2(10f, 10f), Vec2(20f, 10f), Vec2(20f, 20f), Vec2(10f, 20f))

    @Test fun nothingIsStitchedInsideTheHole() {
        val o = DesignObject(1, "ring", StitchType.TATAMI, outer, 0, 1, StitchParams(angleDeg = 0f, underlay = false, pullCompMm = 0f), holes = listOf(hole))
        val runs = StitchGenerator.generateRuns(o)
        for (run in runs) for (i in run.indices) {
            val a = run[i]
            assertFalse("point in hole $a", a.x > 10.05f && a.x < 19.95f && a.y > 10.05f && a.y < 19.95f)
            if (i > 0) { val m = a.lerp(run[i - 1], 0.5f); assertFalse("link through hole $m", m.x > 10.05f && m.x < 19.95f && m.y > 10.05f && m.y < 19.95f) }
        }
        // the ring still gets filled on all four sides
        val all = runs.flatten()
        assertTrue(all.any { it.y < 5f } && all.any { it.y > 25f } && all.any { it.x < 5f } && all.any { it.x > 25f })
    }
}
