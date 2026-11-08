package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan

/**
 * Singer XXX. 0x100-byte header (stitch count, colour count, extents, last position), a 32-bit end-of-stitches
 * offset, then stitches (signed byte pairs, Y up; 7D = 16-bit long stitch; 7F 01 jump, 7F 03 trim, 7F 08 colour
 * change), the end marker 7F 7F 02 14 and a colour table (00 RR GG BB, padded to 21 entries,
 * FFFFFF00, 00 01).
 */
object XxxEncoder : MachineEncoder {
    override val formatName = "Singer XXX"
    override val extension = "xxx"
    const val MAX = 123

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, nativeTrim = true)
        val seq = colorSequence(plan)
        var x = 0; var y = 0; var minX = 0; var maxX = 0; var minY = 0; var maxY = 0
        for (r in recs) { x += r.dx; y += r.dy; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        val w = LeWriter()
        repeat(0x17) { w.u8(0) }
        w.u32(recs.size - 1)
        repeat(0x0C) { w.u8(0) }
        w.u32(seq.size)
        w.u16(0)
        w.u16(maxX - minX).u16(maxY - minY)
        w.u16(x).u16(-y)                      // last position (machine axes, Y up -> stored negated like the reference)
        w.u16(-minX).u16(maxY)
        repeat(0x42) { w.u8(0) }
        w.u16(0).u16(0)
        repeat(0x73) { w.u8(0) }
        w.u16(0x20)
        repeat(8) { w.u8(0) }
        check(w.size == 0xFC)
        val endSlot = w.size
        w.u32(0)
        for (r in recs) when (r.cmd) {
            StitchCommand.STITCH ->
                if (r.dx > -124 && r.dx < 124 && r.dy > -124 && r.dy < 124) { w.u8(r.dx).u8(r.dy) }
                else { w.u8(0x7D).u16(r.dx).u16(r.dy) }
            StitchCommand.JUMP -> w.u8(0x7F).u8(0x01).u8(r.dx).u8(r.dy)
            StitchCommand.TRIM -> w.u8(0x7F).u8(0x03).u8(0).u8(0)
            StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> w.u8(0x7F).u8(0x08).u8(0).u8(0)
            StitchCommand.END -> {}
        }
        w.patchU32(endSlot, w.size)
        w.u8(0x7F).u8(0x7F).u8(0x02).u8(0x14)
        w.u8(0).u8(0)
        for (t in seq.take(21)) w.u8(0).u8(t.rgb shr 16).u8(t.rgb shr 8).u8(t.rgb)
        repeat(21 - minOf(21, seq.size)) { w.u32(0) }
        w.u32(0xFFFFFF00.toInt()).u8(0).u8(1)
        return w.toByteArray()
    }
}
