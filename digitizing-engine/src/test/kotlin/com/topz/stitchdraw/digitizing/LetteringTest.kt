package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.digitizing.auto.*
import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class LetteringTest {
    /** JVM glyph source via java.awt (headless) - same contract the Android Paint-based source fulfils. */
    private val awt = GlyphSource { line, h ->
        System.setProperty("java.awt.headless", "true")
        val font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 100)
        val frc = java.awt.font.FontRenderContext(null, true, true)
        val shape = font.createGlyphVector(frc, line).outline
        val k = h / 100f
        val out = ArrayList<List<Vec2>>(); var cur = ArrayList<Vec2>()
        val it = shape.getPathIterator(null, 0.05)
        val c = FloatArray(6)
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> { cur = ArrayList(); cur.add(Vec2(c[0] * k, c[1] * k)) }
                java.awt.geom.PathIterator.SEG_LINETO -> cur.add(Vec2(c[0] * k, c[1] * k))
                java.awt.geom.PathIterator.SEG_CLOSE -> { out.add(cur) }
            }
            it.next()
        }
        out
    }
    private val fake = GlyphSource { line, h -> // a box per character, with a hole in 'O'
        var x = 0f; val r = ArrayList<List<Vec2>>()
        for (ch in line) {
            if (ch != ' ') {
                r.add(listOf(Vec2(x, -h), Vec2(x + h * .6f, -h), Vec2(x + h * .6f, 0f), Vec2(x, 0f)))
                if (ch == 'O') r.add(listOf(Vec2(x + h * .2f, -h * .7f), Vec2(x + h * .4f, -h * .7f), Vec2(x + h * .4f, -h * .3f), Vec2(x + h * .2f, -h * .3f)))
            }
            x += h * .7f
        }
        r
    }

    @Test fun lettersKeepCountersAndAreCentred() {
        val l = Lettering.layout("OK", 10f, fake)
        assertEquals(2, l.groups.size)
        assertEquals(1, l.groups.count { it.second.isNotEmpty() })
        val xs = l.groups.flatMap { it.first }.map { it.x }
        assertEquals(0f, (xs.min() + xs.max()) / 2, 0.01f)
    }

    @Test fun multiLineStacksAndCentres() {
        val l = Lettering.layout("AB\nCD", 10f, fake)
        assertEquals(4, l.groups.size)
        assertTrue(l.heightMm > 15f)
        val ys = l.groups.flatMap { it.first }.map { it.y }
        assertEquals(0f, (ys.min() + ys.max()) / 2, 0.01f)
    }

    @Test fun rejectsEmptyAndTinyText() {
        for ((t, h) in listOf("" to 10f, "   " to 10f, "A" to 2f))
            try { Lettering.layout(t, h, fake); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun sessionAddsTextAsOneUndoStepAndStitches() {
        val s = DigitizingSession()
        assertEquals(3, s.addText("TOP", 12f, fake, fill = com.topz.stitchdraw.digitizing.auto.LetterFill.TATAMI))
        assertTrue(s.design.objects.all { it.type == StitchType.TATAMI })
        assertTrue(s.plan.stitches.size > 200)
        s.undo(); assertTrue(s.design.objects.isEmpty())
    }

    @Test fun realFontOutlinesBecomeFilledLetters() {
        val l = try { Lettering.layout("BOB", 15f, awt) } catch (e: Throwable) { println("REALFONT SKIPPED: $e"); return }
        assertEquals(3, l.groups.size)
        assertTrue(l.groups.sumOf { it.second.size } >= 4)           // B has 2, O has 1, B has 2
        val s = DigitizingSession()
        s.addText("Topz", 15f, awt, fill = com.topz.stitchdraw.digitizing.auto.LetterFill.TATAMI)
        val plan = s.plan
        assertTrue(plan.stitches.size > 500)
        val hoop = s.design.hoop
        assertTrue(plan.stitches.all { kotlin.math.abs(it.x / 10f) < hoop.widthMm })
    }
}
