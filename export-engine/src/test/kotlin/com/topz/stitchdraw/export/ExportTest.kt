package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class ExportTest {
    private fun design(): EmbroideryDesign {
        var d = EmbroideryDesign()
        d = d.addObject(DesignObject(1, "a", StitchType.RUNNING, listOf(Vec2(0f, 0f), Vec2(40f, 10f), Vec2(40f, 30f)), 0, 1))
        // far away second object with another colour => colour change, trim and a long jump (>12.1mm)
        d = d.addObject(DesignObject(2, "b", StitchType.TRIPLE_RUNNING, listOf(Vec2(90f, 80f), Vec2(95f, 85f)), 1, 1))
        return d
    }
    private fun absolute(recs: List<Rec>): List<Pair<Int, Int>> {
        var x = 0; var y = 0
        return recs.filter { it.cmd == StitchCommand.STITCH || it.cmd == StitchCommand.JUMP }.map { x += it.dx; y += it.dy; x to y }
    }

    @Test fun dstRecordRoundTripAllValues() {
        for (dx in -121..121) for (dy in listOf(-121, -81, -40, -13, -4, -1, 0, 1, 4, 13, 40, 81, 121)) {
            val b = DstEncoder.record(Rec(dx, dy, StitchCommand.STITCH))
            val bytes = ByteArray(512) { 0x20 }.also { "LA:x".toByteArray().copyInto(it) } + b + DstEncoder.record(Rec(0, 0, StitchCommand.END))
            val r = DstEncoder.decode(bytes)[0]
            assertEquals("dx=$dx dy=$dy", Rec(dx, dy, StitchCommand.STITCH), r)
        }
    }

    @Test fun dstKnownBytes() {
        // X uses the low bits, Y the high bits (checked against the independent pyembroidery reader).
        assertArrayEquals(byteArrayOf(0x01, 0, 3), DstEncoder.record(Rec(1, 0, StitchCommand.STITCH)))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 3), DstEncoder.record(Rec(0, 1, StitchCommand.STITCH)))
        assertArrayEquals(byteArrayOf(0, 0, 0xF3.toByte()), DstEncoder.record(Rec(0, 0, StitchCommand.END)))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0, 0x83.toByte()), DstEncoder.record(Rec(1, 1, StitchCommand.JUMP)))
        assertArrayEquals(byteArrayOf(0, 0, 0xC3.toByte()), DstEncoder.record(Rec(0, 0, StitchCommand.COLOR_CHANGE)))
    }

    @Test fun dstFileStructure() {
        val plan = StitchPlanner.plan(design())
        val bytes = DstEncoder.encode(plan, "Test")
        assertEquals(0, (bytes.size - 512) % 3)
        val header = String(bytes, 0, 512, Charsets.ISO_8859_1)
        assertTrue(header.startsWith("LA:Test"))
        assertEquals(0x1A, header.indexOf(0x1A.toChar()).let { header[it].code })
        val recs = DstEncoder.decode(bytes)
        val st = Regex("ST:\\s*(\\d+)").find(header)!!.groupValues[1].toInt()
        assertEquals(recs.size, st)
        val co = Regex("CO:\\s*(\\d+)").find(header)!!.groupValues[1].toInt()
        assertEquals(1, co)
        assertEquals(1, recs.count { it.cmd == StitchCommand.COLOR_CHANGE })
        assertTrue(recs.all { kotlin.math.abs(it.dx) <= 121 && kotlin.math.abs(it.dy) <= 121 })
    }

    @Test fun geometryIsPreservedCentredAndYFlipped() {
        val plan = StitchPlanner.plan(design())
        val stitches = plan.stitches.filter { it.cmd == StitchCommand.STITCH || it.cmd == StitchCommand.JUMP }
        val cx = (stitches.minOf { it.x } + stitches.maxOf { it.x }) / 2
        val cy = (stitches.minOf { it.y } + stitches.maxOf { it.y }) / 2
        val expected = stitches.map { (it.x - cx) to -(it.y - cy) }
        for (enc in listOf<Pair<MachineEncoder, (ByteArray) -> List<Rec>>>(
            DstEncoder to DstEncoder::decode, ExpEncoder to ExpEncoder::decode)) {
            val got = absolute(enc.second(enc.first.encode(plan)))
            // long moves are split, so every expected point must appear on the path in order
            var gi = 0
            for (e in expected) { while (gi < got.size && got[gi] != e) gi++; assertTrue("${enc.first.formatName} lost point $e", gi < got.size); gi++ }
            assertEquals(expected.last(), got.last())
        }
    }

    @Test fun expRoundTripAndLimits() {
        val bytes = ExpEncoder.encode(StitchPlanner.plan(design()))
        val recs = ExpEncoder.decode(bytes)
        assertTrue(recs.all { kotlin.math.abs(it.dx) <= 127 && kotlin.math.abs(it.dy) <= 127 })
        assertEquals(1, recs.count { it.cmd == StitchCommand.COLOR_CHANGE })
        assertTrue(recs.any { it.cmd == StitchCommand.TRIM })
        // 0x80 must never be produced as a coordinate byte for a stitch (it is the escape code)
        var i = 0
        while (i < bytes.size) { if (bytes[i].toInt() and 0xFF == 0x80) i += 4 else { i += 2 } }
        assertEquals(bytes.size, i)
    }

    @Test fun emptyDesignRejected() {
        assertThrows(ExportException::class.java) { DstEncoder.encode(StitchPlan.EMPTY) }
    }

    @Test fun jefHeaderAndRecordsAreConsistent() {
        val plan = StitchPlanner.plan(design())
        val bytes = JefEncoder.encode(plan, "T", java.util.Date(0))
        val (recs, palette) = JefEncoder.decode(bytes)   // checks offset, record count, end marker
        assertEquals(2, palette.size)
        assertTrue(palette.all { it in 1 until Charts.JEF.size })
        assertEquals(1, recs.count { it.cmd == StitchCommand.COLOR_CHANGE })
        assertTrue(recs.all { kotlin.math.abs(it.dx) <= 127 && kotlin.math.abs(it.dy) <= 127 })
    }

    @Test fun pesContainerLayout() {
        val plan = StitchPlanner.plan(design())
        val b = PesEncoder.encode(plan, "Test")
        assertEquals("#PES0001", String(b, 0, 8))
        val pec = java.nio.ByteBuffer.wrap(b, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals("LA:Test", String(b, pec, 7))
        val colors = 2
        val block = pec + 512
        val len = (b[block + 2].toInt() and 0xFF) or ((b[block + 3].toInt() and 0xFF) shl 8) or ((b[block + 4].toInt() and 0xFF) shl 16)
        assertEquals(0xFF, b[block + len - 1].toInt() and 0xFF)               // stitch block ends with END
        assertEquals(b.size, block + len + (colors + 1) * 228)                 // then exactly one icon per colour + overview
        assertEquals(colors - 1, b[pec + 48].toInt() and 0xFF)                 // colour count - 1 in the PEC header
    }
}

class AppliqueExportTest {
    @Test fun stopsExportAsMachineStopsInAllFormats() {
        val outline = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 20f), Vec2(0f, 20f))
        val plan = StitchPlanner.plan(EmbroideryDesign().addObject(DesignObject(1, "a", StitchType.APPLIQUE, outline, 0, 1)))
        val dst = String(DstEncoder.encode(plan), 0, 512, Charsets.ISO_8859_1)
        assertEquals(2, Regex("CO:\\s*(\\d+)").find(dst)!!.groupValues[1].toInt())
        assertEquals(2, DstEncoder.decode(DstEncoder.encode(plan)).count { it.cmd == StitchCommand.COLOR_CHANGE })
        val (jef, pal) = JefEncoder.decode(JefEncoder.encode(plan, "a", java.util.Date(0)))
        assertEquals(3, pal.size)
        assertEquals(2, jef.count { it.cmd == StitchCommand.COLOR_CHANGE })
        val pes = PesEncoder.encode(plan, "a")
        assertEquals(2, pes[java.nio.ByteBuffer.wrap(pes, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int + 48].toInt() and 0xFF)
    }
}

class ExtraFormatsTest {
    private fun design(): EmbroideryDesign {
        var d = EmbroideryDesign()
        d = d.addObject(DesignObject(1, "a", StitchType.SATIN, listOf(Vec2(0f, 0f), Vec2(30f, 10f)), 0, 1))
        d = d.addObject(DesignObject(2, "b", StitchType.TATAMI, listOf(Vec2(60f, 50f), Vec2(100f, 50f), Vec2(100f, 80f), Vec2(60f, 80f)), 1, 1))
        return d
    }

    @Test fun vp3Structure() {
        val b = Vp3Encoder.encode(StitchPlanner.plan(design()), "x")
        assertEquals("%vsm%", String(b, 0, 5))
        val buf = java.nio.ByteBuffer.wrap(b)           // big endian
        // file block length covers everything after its own 4-byte field
        val producerLen = buf.getShort(6).toInt()
        val fileBlock = 6 + 2 + producerLen + 3
        assertEquals(b.size - fileBlock - 4, buf.getInt(fileBlock))
    }

    @Test fun xxxHeaderAndTrailer() {
        val b = XxxEncoder.encode(StitchPlanner.plan(design()), "x")
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val endOfStitches = buf.getInt(0xFC)
        assertEquals(0x7F, b[endOfStitches].toInt() and 0xFF)
        assertEquals(0x14, b[endOfStitches + 3].toInt() and 0xFF)
        assertEquals(2, buf.getInt(0x27))               // colour count
        assertEquals(0x01, b[b.size - 1].toInt())
    }

    @Test fun husHeaderAndLongStreamsUseMultipleBlocks() {
        val plan = StitchPlanner.plan(design())
        val b = HusEncoder.encode(plan, "x")
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x00C8AF5B, buf.getInt(0))
        assertEquals(2, buf.getInt(8))
        val cmdOff = buf.getInt(0x14); val xOff = buf.getInt(0x18); val yOff = buf.getInt(0x1C)
        assertEquals(0x2A + 2 * 2, cmdOff)
        assertTrue(cmdOff < xOff && xOff < yOff && yOff < b.size)
        // > 65535 tokens must split into several blocks and still end with exactly one END token
        val big = ByteArray(70000) { (it * 31).toByte() }
        val c = HusCompress.compress(big)
        assertTrue(c.size > 70000 * 9 / 8)
        assertTrue(c.size < 70000 * 9 / 8 + 200)
    }
}
