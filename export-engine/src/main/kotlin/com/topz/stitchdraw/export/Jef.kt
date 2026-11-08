package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Janome JEF.
 *
 * Header (little endian): u32 stitch-data offset, u32 0x14, 14 ASCII date "yyyyMMddHHmmss" + 2 zero bytes,
 * u32 colour count, u32 record count, u32 hoop code, 4 x u32 design extents (half width/height from centre),
 * 4 hoop-edge blocks of 4 x i32, then colour count x u32 Janome chart indices and colour count x u32 0x0D.
 * Stitch data: i8 dx, i8 dy (Y up). Escape 0x80: 0x01 colour change, 0x02 jump (followed by dx dy),
 * 0x10 end. Trim is written as three zero jumps (80 02 00 00).
 */
object JefEncoder : MachineEncoder {
    override val formatName = "Janome JEF"
    override val extension = "jef"
    const val MAX = 127
    private const val TRIM_REPEAT = 3

    override fun encode(plan: StitchPlan, label: String): ByteArray = encode(plan, label, Date())

    fun encode(plan: StitchPlan, label: String, date: Date): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, nativeTrim = true)
        val seq = colorSequence(plan)
        // chart index per colour block, avoiding two consecutive blocks mapping to the same chart colour
        val palette = ArrayList<Int>()
        var lastIdx = -1; var lastRgb = -1
        for (t in seq) {
            var idx = nearestChart(t.rgb, Charts.JEF)
            if (idx == lastIdx && t.rgb != lastRgb) idx = nearestChart(t.rgb, Charts.JEF, setOf(idx))
            palette.add(idx); lastIdx = idx; lastRgb = t.rgb
        }
        val colorCount = palette.size

        var points = 1
        for (r in recs) points += when (r.cmd) {
            StitchCommand.STITCH -> 1
            StitchCommand.JUMP, StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> 2
            StitchCommand.TRIM -> 2 * TRIM_REPEAT
            StitchCommand.END -> 0
        }
        var x = 0; var y = 0; var minX = 0; var maxX = 0; var minY = 0; var maxY = 0
        for (r in recs) { x += r.dx; y += r.dy; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        val w = maxX - minX; val h = maxY - minY
        val halfW = Math.round(w / 2.0).toInt(); val halfH = Math.round(h / 2.0).toInt()

        val offset = 0x74 + colorCount * 8
        val head = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(offset).putInt(0x14)
        head.put(SimpleDateFormat("yyyyMMddHHmmss").format(date).toByteArray(Charsets.US_ASCII)).put(0).put(0)
        head.putInt(colorCount).putInt(points).putInt(hoopCode(w, h))
        repeat(2) { head.putInt(halfW).putInt(halfH) }
        edge(head, 550 - halfW, 550 - halfH)
        edge(head, 250 - halfW, 250 - halfH)
        edge(head, 700 - halfW, 1000 - halfH)
        edge(head, 700 - halfW, 1000 - halfH)
        for (p in palette) head.putInt(p)
        repeat(colorCount) { head.putInt(0x0D) }
        check(!head.hasRemaining())

        val o = ByteArrayOutputStream()
        o.write(head.array())
        for (r in recs) when (r.cmd) {
            StitchCommand.STITCH -> { o.write(r.dx and 0xFF); o.write(r.dy and 0xFF) }
            StitchCommand.JUMP -> { o.write(0x80); o.write(0x02); o.write(r.dx and 0xFF); o.write(r.dy and 0xFF) }
            StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> { o.write(0x80); o.write(0x01); o.write(0); o.write(0) }
            StitchCommand.TRIM -> repeat(TRIM_REPEAT) { o.write(0x80); o.write(0x02); o.write(0); o.write(0) }
            StitchCommand.END -> {}
        }
        o.write(0x80); o.write(0x10)
        return o.toByteArray()
    }

    private fun edge(b: ByteBuffer, ex: Int, ey: Int) {
        if (minOf(ex, ey) >= 0) { b.putInt(ex).putInt(ey).putInt(ex).putInt(ey) }
        else { b.putInt(-1).putInt(-1).putInt(-1).putInt(-1) }
    }

    private fun hoopCode(w: Int, h: Int) = when {
        w < 500 && h < 500 -> 1       // 50 x 50
        w < 1260 && h < 1100 -> 3     // 126 x 110
        w < 1400 && h < 2000 -> 2     // 140 x 200
        w < 2000 && h < 2000 -> 4     // 200 x 200
        else -> 0                     // 110 x 110
    }

    /** Validation reader. Returns the records (Y up) and the Janome chart index of each colour block. */
    fun decode(bytes: ByteArray): Pair<List<Rec>, List<Int>> {
        if (bytes.size < 0x74 + 2) throw ExportException("JEF: too small")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = b.getInt(0); val colors = b.getInt(24); val points = b.getInt(28)
        if (offset != 0x74 + colors * 8 || offset > bytes.size) throw ExportException("JEF: bad data offset")
        val palette = (0 until colors).map { b.getInt(0x74 + it * 4) }
        val out = ArrayList<Rec>()
        var i = offset
        while (i < bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0x80) {
                if (i + 1 >= bytes.size) throw ExportException("JEF: truncated")
                when (bytes[i + 1].toInt() and 0xFF) {
                    0x10 -> { out.add(Rec(0, 0, StitchCommand.END)); i += 2; break }
                    0x01 -> { out.add(Rec(bytes[i + 2].toInt(), bytes[i + 3].toInt(), StitchCommand.COLOR_CHANGE)); i += 4 }
                    0x02 -> { out.add(Rec(bytes[i + 2].toInt(), bytes[i + 3].toInt(), StitchCommand.JUMP)); i += 4 }
                    else -> throw ExportException("JEF: unknown control")
                }
            } else { out.add(Rec(bytes[i].toInt(), bytes[i + 1].toInt(), StitchCommand.STITCH)); i += 2 }
        }
        if (out.lastOrNull()?.cmd != StitchCommand.END) throw ExportException("JEF: missing end")
        val counted = out.fold(0) { a, r -> a + when (r.cmd) { StitchCommand.STITCH -> 1; StitchCommand.END -> 1; else -> 2 } }
        if (counted != points) throw ExportException("JEF: record count mismatch ($counted != $points)")
        return out to palette
    }
}
