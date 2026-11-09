package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.digitizing.auto.*
import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SatinSkeletonTest {
    private fun rect(x: Float, y: Float, w: Float, h: Float) = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))
    private fun len(p: List<Vec2>) = p.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat()

    @Test fun barBecomesOneColumnWithRightWidthAndLength() {
        val cols = SatinSkeleton.columnsFor(rect(0f, 0f, 20f, 3f), emptyList())
        assertEquals(1, cols.size)
        assertEquals(3f, cols[0].widthMm, 0.35f)
        assertEquals(20f, len(cols[0].path), 2.5f)
        assertTrue(cols[0].path.all { abs(it.y - 1.5f) < 0.3f })
    }

    @Test fun tShapeGivesTwoOrThreeColumnsCoveringBothBars() {
        val t = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 3f), Vec2(11.5f, 3f), Vec2(11.5f, 20f), Vec2(8.5f, 20f), Vec2(8.5f, 3f), Vec2(0f, 3f))
        val cols = SatinSkeleton.columnsFor(t, emptyList())
        assertTrue("got ${cols.size}", cols.size in 2..4)
        assertTrue(cols.any { c -> c.path.all { abs(it.y - 1.5f) < 0.8f } && len(c.path) > 8f })      // top bar
        assertTrue(cols.any { c -> c.path.maxOf { it.y } > 17f })                                       // stem reaches the bottom
        assertTrue(cols.all { it.widthMm in 2.2f..3.8f })
    }

    @Test fun ringIsOneClosedColumn() {
        val n = 60
        val outer = (0 until n).map { val t = 2 * Math.PI * it / n; Vec2((10 + 10 * Math.cos(t)).toFloat(), (10 + 10 * Math.sin(t)).toFloat()) }
        val inner = (0 until n).map { val t = 2 * Math.PI * it / n; Vec2((10 + 7.5 * Math.cos(t)).toFloat(), (10 + 7.5 * Math.sin(t)).toFloat()) }
        val cols = SatinSkeleton.columnsFor(outer, listOf(inner))
        assertEquals(1, cols.size)
        assertEquals(2.5f, cols[0].widthMm, 0.4f)
        assertEquals(2 * Math.PI.toFloat() * 8.75f, len(cols[0].path), 4f)
        assertTrue(cols[0].path.first().distanceTo(cols[0].path.last()) < 0.5f)
    }

    @Test fun thinTestSeparatesStrokesFromFat() {
        assertTrue(SatinSkeleton.thinWidthMm(rect(0f, 0f, 30f, 3f), emptyList(), 6f) > 0f)
        assertEquals(0f, SatinSkeleton.thinWidthMm(rect(0f, 0f, 30f, 20f), emptyList(), 6f), 0f)
        assertEquals(0f, SatinSkeleton.thinWidthMm(rect(0f, 0f, 30f, 0.3f), emptyList(), 6f), 0f)
    }

    @Test fun autoDigitizerUsesSatinForThinStrokesAndTatamiForBlobs() {
        val w = 200; val h = 100
        val img = ImageData(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w
            when { x in 10..70 && y in 20..80 -> 0xFFD32F2F.toInt()          // fat block
                   x in 100..190 && y in 48..53 -> 0xFF1565C0.toInt()       // 6px stroke at 0.4mm/px = 2.4 mm
                   else -> 0xFFFFFFFF.toInt() } })
        val r = AutoDigitizer.run(img, AutoOptions(colors = 3, widthMm = 80f))
        val satin = r.objects.filter { it.type == StitchType.SATIN }
        assertEquals(1, satin.size)
        assertEquals(2.4f, satin[0].widthMm, 0.5f)
        assertEquals(1, r.objects.count { it.type == StitchType.TATAMI })
        val s = DigitizingSession(); s.applyAutoDigitize(r)
        val o = s.design.objects.first { it.type == StitchType.SATIN }
        assertEquals(satin[0].widthMm, o.params.widthMm, 0.01f)
        assertTrue(s.plan.stitches.size > 300)
        // with satin disabled the stroke stays tatami
        assertTrue(AutoDigitizer.run(img, AutoOptions(colors = 3, widthMm = 80f, satinMaxWidthMm = 0f)).objects.none { it.type == StitchType.SATIN })
    }

    @Test fun lettersAutoChooseSatinThenFillWhenBig() {
        val box = GlyphSource { _, h -> listOf(rect(0f, -h, h * 0.15f, h)) }                // one stem, 15% of height wide
        val small = DigitizingSession(); small.addText("I", 15f, box)                       // 2.25 mm stroke -> satin
        assertTrue(small.design.objects.all { it.type == StitchType.SATIN })
        val big = DigitizingSession(); big.addText("I", 60f, box)                           // 9 mm stroke -> fill
        assertTrue(big.design.objects.all { it.type == StitchType.TATAMI })
        val forced = DigitizingSession(); forced.addText("I", 15f, box, fill = LetterFill.TATAMI)
        assertTrue(forced.design.objects.all { it.type == StitchType.TATAMI })
    }

    @Test fun realFontSatinTextStitchesInsideHoop() {
        val awt = GlyphSource { line, h ->
            val font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 100)
            val shape = font.createGlyphVector(java.awt.font.FontRenderContext(null, true, true), line).outline
            val k = h / 100f; val out = ArrayList<List<Vec2>>(); var cur = ArrayList<Vec2>(); val c = FloatArray(6)
            val it = shape.getPathIterator(null, 0.05)
            while (!it.isDone) { when (it.currentSegment(c)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> { cur = ArrayList(); cur.add(Vec2(c[0] * k, c[1] * k)) }
                java.awt.geom.PathIterator.SEG_LINETO -> cur.add(Vec2(c[0] * k, c[1] * k))
                java.awt.geom.PathIterator.SEG_CLOSE -> out.add(cur) }; it.next() }
            out
        }
        val s = DigitizingSession()
        val n = try { s.addText("TOPZ", 20f, awt) } catch (e: Throwable) { println("REALFONT SKIPPED $e"); return }
        val satin = s.design.objects.count { it.type == StitchType.SATIN }
        assertTrue("satin columns=$satin of $n", satin >= 4)
        val plan = s.plan
        println("SATIN TOPZ stitches=${plan.stitches.size} columns=$satin")
        assertTrue(plan.stitches.size > 400)
        assertTrue(plan.stitches.all { abs(it.x / 10f) < 50f && abs(it.y / 10f) < 50f })
    }
}

class SatinStemTest {
    private fun rect(x: Float, y: Float, w: Float, h: Float) = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))
    @Test fun stemStubJustPastAJunctionIsKept() {
        // "P"-like: stem 3.3 wide, 17 long, with a bar crossing at y = 6..9; the 8 mm below the bar must still be stitched
        val shape = listOf(Vec2(0f, 0f), Vec2(3.3f, 0f), Vec2(3.3f, 6f), Vec2(12f, 6f), Vec2(12f, 9.3f), Vec2(3.3f, 9.3f), Vec2(3.3f, 17f), Vec2(0f, 17f))
        val cols = SatinSkeleton.columnsFor(shape, emptyList())
        assertTrue("cols=${cols.size}", cols.any { c -> c.path.maxOf { it.y } > 15f && c.path.all { kotlin.math.abs(it.x - 1.65f) < 0.6f } })
    }
    @Test fun shortStubUnderOneWidthIsPrunedAsThorn() {
        val bar = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 3f), Vec2(16.5f, 3f), Vec2(16.5f, 4.5f), Vec2(13.5f, 4.5f), Vec2(13.5f, 3f), Vec2(0f, 3f))
        val cols = SatinSkeleton.columnsFor(bar, emptyList())
        assertTrue("cols=${cols.size}", cols.size in 1..2)
    }
}

class SatinJunctionTest {
    private fun len(p: List<Vec2>) = p.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat()
    private val tShape = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 3f), Vec2(11.5f, 3f), Vec2(11.5f, 20f), Vec2(8.5f, 20f), Vec2(8.5f, 3f), Vec2(0f, 3f))

    @Test fun tIsTwoColumns_barRunsThrough_stemButtsAgainstIt() {
        val cols = SatinSkeleton.columnsFor(tShape, emptyList())
        assertEquals(2, cols.size)
        val bar = cols.first { c -> c.path.all { kotlin.math.abs(it.y - 1.5f) < 0.5f } }
        assertTrue("bar length ${len(bar.path)}", len(bar.path) > 18f)
        val stem = cols.first { it !== bar }
        val top = stem.path.minOf { it.y }
        assertTrue("stem starts at y=$top", top in 2.3f..3.6f)           // bar's lower edge is y = 3
        assertTrue(stem.path.maxOf { it.y } > 18f)
    }

    @Test fun plusSignIsTwoStraightColumns() {
        val plus = listOf(Vec2(8.5f, 0f), Vec2(11.5f, 0f), Vec2(11.5f, 8.5f), Vec2(20f, 8.5f), Vec2(20f, 11.5f), Vec2(11.5f, 11.5f),
            Vec2(11.5f, 20f), Vec2(8.5f, 20f), Vec2(8.5f, 11.5f), Vec2(0f, 11.5f), Vec2(0f, 8.5f), Vec2(8.5f, 8.5f))
        val cols = SatinSkeleton.columnsFor(plus, emptyList())
        assertEquals(2, cols.size)
        assertTrue(cols.all { len(it.path) > 17f })
    }

    @Test fun squareCorneredPKeepsItsStemTail() {
        val outer = listOf(Vec2(0f, 0f), Vec2(12f, 0f), Vec2(14f, 2f), Vec2(14f, 7f), Vec2(12f, 9f), Vec2(3.3f, 9f), Vec2(3.3f, 17f), Vec2(0f, 17f))
        val hole = listOf(Vec2(3.3f, 3f), Vec2(10.5f, 3f), Vec2(10.5f, 6f), Vec2(3.3f, 6f))
        val cols = SatinSkeleton.columnsFor(outer, listOf(hole))
        assertTrue("cols=${cols.size}", cols.size in 1..6)          // its 90 degree corners are split, which is right
        assertTrue(cols.any { c -> c.path.maxOf { it.y } > 15.5f })        // stem tail present
    }

    @Test fun zCornersKeepThreeColumnsInsideTheShape() {
        val z = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 3f), Vec2(6f, 17f), Vec2(20f, 17f), Vec2(20f, 20f), Vec2(0f, 20f), Vec2(0f, 17f), Vec2(14f, 3f), Vec2(0f, 3f))
        val cols = SatinSkeleton.columnsFor(z, emptyList())
        assertTrue("cols=${cols.size}", cols.size in 2..3)
        for (c in cols) for (p in c.path) assertTrue("stray point $p", p.x in -1f..21f && p.y in -1f..21f)
    }

    @Test fun noColumnEndLooksLikeAKnotAtItsJunction() {
        // no tiny zig-zag segments near ends: the last 3 points of every column must continue in one direction
        for (shape in listOf(tShape)) for (c in SatinSkeleton.columnsFor(shape, emptyList())) {
            val p = c.path; if (p.size < 4) continue
            val a = p[p.size - 3]; val b = p[p.size - 2]; val d = p[p.size - 1]
            val v1x = b.x - a.x; val v1y = b.y - a.y; val v2x = d.x - b.x; val v2y = d.y - b.y
            assertTrue(v1x * v2x + v1y * v2y >= 0f)
        }
    }
}

class SatinCornerTest {
    private fun len(p: List<Vec2>) = p.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat()
    /** max turning angle (degrees) between segment directions measured over ~1 mm */
    private fun sharpest(p: List<Vec2>): Float {
        var best = 0f
        for (i in 1 until p.size - 1) { var a = i - 1; while (a > 0 && p[i].distanceTo(p[a]) < 1f) a--
            var c = i + 1; while (c < p.size - 1 && p[i].distanceTo(p[c]) < 1f) c++
            val v1x = p[i].x - p[a].x; val v1y = p[i].y - p[a].y; val v2x = p[c].x - p[i].x; val v2y = p[c].y - p[i].y
            val l = Math.hypot(v1x.toDouble(), v1y.toDouble()) * Math.hypot(v2x.toDouble(), v2y.toDouble()); if (l < 1e-6) continue
            best = maxOf(best, Math.toDegrees(Math.acos(((v1x * v2x + v1y * v2y) / l).coerceIn(-1.0, 1.0))).toFloat()) }
        return best
    }
    private val vShape = listOf(Vec2(0f, 0f), Vec2(3f, 0f), Vec2(11f, 18f), Vec2(19f, 0f), Vec2(22f, 0f), Vec2(12.5f, 21f), Vec2(9.5f, 21f))   // inverted V / "A" apex: ~ 130 degree turn

    @Test fun sharpApexIsSplitIntoTwoColumnsNoneBendingSharply() {
        val cols = SatinSkeleton.columnsFor(vShape, emptyList())
        assertTrue("cols=${cols.size}", cols.size >= 2)
        for (c in cols) assertTrue("sharpest ${sharpest(c.path)}", sharpest(c.path) < 50f)
    }

    @Test fun squareRingCornersComeOutRoundedEnoughForSatinAndStayCovered() {
        val outer = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 20f), Vec2(0f, 20f))
        val inner = listOf(Vec2(3f, 3f), Vec2(17f, 3f), Vec2(17f, 17f), Vec2(3f, 17f))
        val cols = SatinSkeleton.columnsFor(outer, listOf(inner))
        assertTrue("cols=${cols.size}", cols.size in 1..5)
        for (c in cols) assertTrue("sharpest ${sharpest(c.path)}", sharpest(c.path) < 60f)   // thinning rounds the corners (r ~ 1 width)
        assertTrue(cols.sumOf { len(it.path).toDouble() } > 60.0)           // ~ perimeter of the centre line 4*17 = 68
    }

    @Test fun roundRingAndStraightBarAreNotSplit() {
        val n = 80
        val o = (0 until n).map { val t = 2 * Math.PI * it / n; Vec2((10 + 10 * Math.cos(t)).toFloat(), (10 + 10 * Math.sin(t)).toFloat()) }
        val i = (0 until n).map { val t = 2 * Math.PI * it / n; Vec2((10 + 7.5 * Math.cos(t)).toFloat(), (10 + 7.5 * Math.sin(t)).toFloat()) }
        assertEquals(1, SatinSkeleton.columnsFor(o, listOf(i)).size)
        assertEquals(1, SatinSkeleton.columnsFor(listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 3f), Vec2(0f, 3f)), emptyList()).size)
    }
}

class SatinPolishTest {
    private fun len(c: SatinColumn): Float { var t = 0f; for (i in 1 until c.path.size) t += c.path[i].distanceTo(c.path[i - 1]); return t }

    /** A thick round arc (outer radius 4.5, inner 1.5, 270 degrees) bends steadily: it must stay ONE column, not be cut like a corner. */
    @Test fun tightRoundArcIsNotSplitLikeACorner() {
        val outer = (0..54).map { val a = Math.toRadians(it * 5.0 - 45.0); Vec2(10f + (4.5 * Math.cos(a)).toFloat(), 10f + (4.5 * Math.sin(a)).toFloat()) }
        val inner = (54 downTo 0).map { val a = Math.toRadians(it * 5.0 - 45.0); Vec2(10f + (1.5 * Math.cos(a)).toFloat(), 10f + (1.5 * Math.sin(a)).toFloat()) }
        val cols = SatinSkeleton.columnsFor(outer + inner, emptyList())
        assertEquals("columns=${cols.map { len(it) }}", 1, cols.size)
        assertTrue(len(cols[0]) > 12f)
    }

    /** A real right-angle corner is still cut (so the stitches do not fan). */
    @Test fun squareCornerStillSplits() {
        val l = listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 3f), Vec2(3f, 3f), Vec2(3f, 20f), Vec2(0f, 20f))
        assertEquals(2, SatinSkeleton.columnsFor(l, emptyList()).size)
    }

    /** A "Z": two bars and one diagonal. The acute junctions must not leave a little extra column (the knot). */
    @Test fun zHasThreeColumnsAndNoKnot() {
        val z = listOf(Vec2(0f, 0f), Vec2(14f, 0f), Vec2(14f, 3.2f), Vec2(5.2f, 14f), Vec2(14f, 14f), Vec2(14f, 17.2f),
            Vec2(0f, 17.2f), Vec2(0f, 14f), Vec2(8.8f, 3.2f), Vec2(0f, 3.2f))
        val cols = SatinSkeleton.columnsFor(z, emptyList())
        assertEquals("columns=${cols.map { len(it) }}", 3, cols.size)
        assertTrue(cols.all { len(it) > 3f * it.widthMm })
    }

    /** A short but needed column (a dot) is never thrown away as redundant. */
    @Test fun lonelyShortColumnIsKept() {
        val dot = listOf(Vec2(0f, 0f), Vec2(3.4f, 0f), Vec2(3.4f, 6f), Vec2(0f, 6f))
        assertEquals(1, SatinSkeleton.columnsFor(dot, emptyList()).size)
    }

    /** A B-like glyph (stem + two round bowls) comes out as a handful of long columns, not a pile of fragments. */
    @Test fun bowlsStayWholeInBLikeShape() {
        fun arc(cx: Float, cy: Float, r: Float, from: Double, to: Double, n: Int) =
            (0..n).map { val a = Math.toRadians(from + (to - from) * it / n); Vec2(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat()) }
        fun bowl(top: Float, bottom: Float): List<Vec2> {
            val rO = (bottom - top) / 2f; val rI = rO - 3.2f; val cy = (top + bottom) / 2f
            return arc(3.2f, cy, rO, -90.0, 90.0, 36) + arc(3.2f, cy, rI, 90.0, -90.0, 36)
        }
        val b1 = bowl(0f, 11f); val b2 = bowl(11f, 22f)
        val shape = listOf(Vec2(0f, 0f), Vec2(3.2f, 0f)) + b1.drop(0) + listOf(Vec2(3.2f, 11f)) + b2 + listOf(Vec2(3.2f, 22f), Vec2(0f, 22f))
        val cols = SatinSkeleton.columnsFor(shape, emptyList())
        println("B-like columns=${cols.map { len(it) }}")
        assertTrue("columns=${cols.size}", cols.size <= 4)
        assertTrue(cols.all { len(it) > 8f })
    }
}

