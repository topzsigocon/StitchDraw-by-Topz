package com.topz.stitchdraw.digitizing

import com.topz.stitchdraw.digitizing.auto.*
import com.topz.stitchdraw.embroidery.*
import org.junit.Assert.*
import org.junit.Test

class SvgImporterTest {
    private fun svg(body: String, attrs: String = "viewBox=\"0 0 100 50\"") = SvgImporter.parse("<svg xmlns=\"http://www.w3.org/2000/svg\" $attrs>$body</svg>".toByteArray())

    @Test fun rectAndCircleAndColours() {
        val r = svg("<rect x='0' y='0' width='40' height='20' fill='#ff0000'/><circle cx='70' cy='25' r='10' style='fill:rgb(0,0,255)'/>")
        assertEquals(2, r.shapes.size)
        assertEquals(0xFF0000, r.shapes[0].fillRgb); assertEquals(0x0000FF, r.shapes[1].fillRgb)
    }

    @Test fun pathWithCurvesRelativeAndArcs() {
        val r = svg("<path d='M10 10 h20 v20 h-20 z M50,10 c10,0 20,10 20,20 s-10,10 -20,0 q5,-10 0,-20 a10 10 0 0 1 10 10 Z' fill='green'/>")
        val sh = r.shapes.single()
        assertEquals(2, sh.subpaths.size)
        assertTrue(sh.subpaths[1].size > 10)
        assertEquals(10f, sh.subpaths[0][0].x, 0.01f); assertEquals(30f, sh.subpaths[0][2].x, 0.01f)
    }

    @Test fun transformsApply() {
        val r = svg("<g transform='translate(10,5) scale(2)'><rect width='5' height='5'/></g>")
        val p = r.shapes.single().subpaths.single()
        assertEquals(10f, p.minOf { it.x }, 0.01f); assertEquals(20f, p.maxOf { it.x }, 0.01f); assertEquals(15f, p.maxOf { it.y }, 0.01f)
    }

    @Test fun toObjectsScalesCentresAndMakesHoles() {
        val r = svg("<path fill-rule='evenodd' fill='#000' d='M0 0H100V50H0Z M30 10H70V40H30Z'/>")
        val s = DigitizingSession()
        assertEquals(1, s.applySvg(r, 80f))
        val o = s.design.objects.single()
        assertEquals(StitchType.TATAMI, o.type)
        assertEquals(1, o.holes.size)
        assertEquals(40f, o.path.maxOf { it.x }, 0.1f); assertEquals(-40f, o.path.minOf { it.x }, 0.1f)
        assertTrue(s.plan.stitches.size > 300)
        s.undo(); assertTrue(s.design.objects.isEmpty())
    }

    @Test fun strokeOnlyBecomesRunning() {
        val s = DigitizingSession()
        s.applySvg(svg("<polyline points='0,0 50,25 100,0' fill='none' stroke='#00f'/><line x1='0' y1='50' x2='100' y2='50' stroke='red'/>"), 60f)
        assertEquals(2, s.design.objects.size)
        assertTrue(s.design.objects.all { it.type == StitchType.RUNNING })
    }

    @Test fun badInputIsRejectedCleanly() {
        for (bad in listOf("hello", "<html/>", "<svg><path d='M 1 2 L'/></svg>")) {
            try { SvgImporter.parse(bad.toByteArray()) } catch (e: SvgException) { }
        }
        assertEquals(0, DigitizingSession().applySvg(svg("<defs><rect width='5' height='5'/></defs>"), 50f))
        try { SvgImporter.parse("<!DOCTYPE svg [<!ENTITY x 'y'>]><svg/>".toByteArray()); fail() } catch (e: SvgException) { }
    }
}
