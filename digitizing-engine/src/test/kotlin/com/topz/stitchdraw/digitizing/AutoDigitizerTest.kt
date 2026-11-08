package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.digitizing.auto.*
import com.topz.stitchdraw.embroidery.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class AutoDigitizerTest {
    private val WHITE = 0xFFFFFFFF.toInt(); private val RED = 0xFFD32F2F.toInt(); private val BLUE = 0xFF1565C0.toInt()

    private fun make(w: Int, h: Int, f: (Int, Int) -> Int) = ImageData(w, h, IntArray(w * h) { f(it % w, it / w) })

    /** White canvas, red disc with a white counter (hole), blue disc further right. */
    private fun ringAndDot() = make(160, 100) { x, y ->
        val d1 = hypot(x - 50.0, y - 50.0); val d2 = hypot(x - 120.0, y - 50.0)
        when { d1 < 38 && d1 > 16 -> RED; d2 < 20 -> BLUE; else -> WHITE }
    }

    @Test fun separatesColoursSkipsBackgroundAndFindsHole() {
        val r = AutoDigitizer.run(ringAndDot(), AutoOptions(colors = 4, widthMm = 80f))
        assertEquals(0xFFFFFF, r.backgroundRgb)
        assertEquals(2, r.threadRgb.size)
        assertEquals(2, r.objects.size)
        val ring = r.objects.first { it.holes.isNotEmpty() }
        assertEquals(1, ring.holes.size)
        // ring area: pi*(38^2-16^2) px^2 at 0.5 mm/px  => ~ 0.25 * 3.14159 * 1188 = ~933 mm^2
        assertEquals(933f, ring.areaMm2, 60f)
        val dot = r.objects.first { it.holes.isEmpty() }
        assertEquals(314f, dot.areaMm2, 30f)                        // pi*20^2*0.25
    }

    @Test fun designIsCentredAndSizedInMm() {
        val r = AutoDigitizer.run(ringAndDot(), AutoOptions(widthMm = 80f))
        assertEquals(80f, r.widthMm, 0.01f); assertEquals(50f, r.heightMm, 0.01f)
        val xs = r.objects.flatMap { it.outline }.map { it.x }
        assertTrue(xs.min() > -40f && xs.max() < 40f)
        // ring centre at pixel 50 => (50-80)*0.5 = -15 mm
        val ring = r.objects.first { it.holes.isNotEmpty() }
        assertEquals(-15f, ring.outline.map { it.x }.average().toFloat(), 1.5f)
    }

    @Test fun largestColourIsSewnFirstAndSpecklesAreDropped() {
        val img = make(100, 100) { x, y ->
            when {
                x in 40..60 && y in 40..60 -> BLUE
                x in 10..90 && y in 10..90 -> RED
                x == 5 && y == 5 -> BLUE            // 1px speck
                else -> WHITE
            }
        }
        val r = AutoDigitizer.run(img, AutoOptions(colors = 3, widthMm = 50f, minAreaMm2 = 2f))
        assertEquals(RED and 0xFFFFFF, r.threadRgb[0])   // largest colour first
        assertTrue("rgb=${r.threadRgb.map { Integer.toHexString(it) }} objs=${r.objects.map { it.colorIndex to it.areaMm2 }}", r.threadRgb.size == 2)
        assertTrue(r.objects.none { it.areaMm2 < 2f })
    }

    @Test fun transparentPngBackgroundIsIgnored() {
        val img = make(80, 80) { x, y -> if (hypot(x - 40.0, y - 40.0) < 25) RED else 0x00000000 }
        val r = AutoDigitizer.run(img, AutoOptions(colors = 3, widthMm = 40f))
        assertEquals(1, r.objects.size)
    }

    @Test fun degenerateInputsDoNotCrash() {
        assertTrue(AutoDigitizer.run(make(1, 1) { _, _ -> RED }).objects.isEmpty())
        assertTrue(AutoDigitizer.run(make(50, 50) { _, _ -> WHITE }).objects.isEmpty())          // all background
        assertTrue(AutoDigitizer.run(make(50, 50) { _, _ -> 0 }).objects.isEmpty())              // fully transparent
        val one = AutoDigitizer.run(make(60, 60) { x, _ -> if (x < 30) RED else BLUE }, AutoOptions(colors = 8, skipBackground = false))
        assertEquals(2, one.objects.size)
    }

    @Test fun largeImageIsDownscaledAndStaysFast() {
        val big = make(2000, 1500) { x, y -> if (hypot(x - 1000.0, y - 750.0) < 500) RED else WHITE }
        val t0 = System.nanoTime()
        val r = AutoDigitizer.run(big, AutoOptions(widthMm = 100f))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, r.objects.size)
        assertTrue("took $ms ms", ms < 5000)
    }

    @Test fun sessionAppliesResultAsOneUndoableStepAndStitchesWithoutFillingHoles() {
        val s = DigitizingSession()
        val before = s.design.threads.size
        val r = AutoDigitizer.run(ringAndDot(), AutoOptions(widthMm = 80f))
        val added = s.applyAutoDigitize(r)
        assertEquals(2, added)
        assertEquals(2, s.design.objects.size)
        assertTrue(s.design.objects.all { it.type == StitchType.TATAMI })
        assertEquals(before + 2, s.design.threads.size)
        assertTrue(s.design.layers.any { it.name == "Auto" })
        s.undo()
        assertEquals(0, s.design.objects.size)
        s.redo()
        assertEquals(2, s.design.objects.size)
        val plan = StitchPlanner.plan(s.design)
        assertTrue(plan.stitches.size > 500)
    }
}
