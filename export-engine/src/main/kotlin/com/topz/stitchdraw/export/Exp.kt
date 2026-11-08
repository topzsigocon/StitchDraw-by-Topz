package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan

/**
 * Melco EXP. No header. Stitch = two signed bytes (dx, dy), Y up, +/-127 max.
 * Escape 0x80 introduces a 4-byte control record: 0x80 0x01 colour change, 0x80 0x04 dx dy jump,
 * 0x80 0x80 trim. There is no end marker; the file simply ends after the last record.
 */
object ExpEncoder : MachineEncoder {
    override val formatName = "Melco EXP"
    override val extension = "exp"
    const val MAX = 127

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, nativeTrim = true)
        val o = java.io.ByteArrayOutputStream()
        for (r in recs) when (r.cmd) {
            StitchCommand.STITCH -> { o.write(r.dx and 0xFF); o.write(r.dy and 0xFF) }
            StitchCommand.JUMP -> { o.write(0x80); o.write(0x04); o.write(r.dx and 0xFF); o.write(r.dy and 0xFF) }
            StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> { o.write(0x80); o.write(0x01); o.write(0); o.write(0) }
            StitchCommand.TRIM -> { o.write(0x80); o.write(0x80); o.write(0); o.write(0) }
            StitchCommand.END -> {}
        }
        return o.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x80) {
                if (i + 3 >= bytes.size) throw ExportException("EXP: truncated control record")
                val c = bytes[i + 1].toInt() and 0xFF
                when (c) {
                    0x01 -> out.add(Rec(0, 0, StitchCommand.COLOR_CHANGE))
                    0x04 -> out.add(Rec(bytes[i + 2].toInt(), bytes[i + 3].toInt(), StitchCommand.JUMP))
                    0x80 -> out.add(Rec(0, 0, StitchCommand.TRIM))
                    else -> throw ExportException("EXP: unknown control code $c")
                }
                i += 4
            } else {
                if (i + 1 >= bytes.size) throw ExportException("EXP: truncated stitch")
                out.add(Rec(bytes[i].toInt(), bytes[i + 1].toInt(), StitchCommand.STITCH)); i += 2
            }
        }
        out.add(Rec(0, 0, StitchCommand.END))
        return out
    }
}
