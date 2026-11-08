package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian growable writer with back-patching, shared by PES/PEC. */
internal class LeWriter {
    private val bos = ByteArrayOutputStream()
    private var data = ByteArray(0)
    private var buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    val size get() = bos.size()
    fun u8(v: Int) = apply { bos.write(v and 0xFF) }
    fun u16(v: Int) = apply { bos.write(v and 0xFF); bos.write((v shr 8) and 0xFF) }
    fun u24(v: Int) = apply { u16(v); u8(v shr 16) }
    fun u32(v: Int) = apply { u16(v); u16(v shr 16) }
    fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
    fun bytes(b: ByteArray) = apply { bos.write(b) }
    fun ascii(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
    fun spaces(n: Int) = apply { repeat(n) { bos.write(0x20) } }
    private val patches = ArrayList<Pair<Int, ByteArray>>()
    fun patchU16(pos: Int, v: Int) { patches.add(pos to byteArrayOf(v.toByte(), (v shr 8).toByte())) }
    fun patchU24(pos: Int, v: Int) { patches.add(pos to byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())) }
    fun patchU32(pos: Int, v: Int) { patches.add(pos to byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
    fun toByteArray(): ByteArray { val a = bos.toByteArray(); for ((p, b) in patches) b.copyInto(a, p); return a }
}

/**
 * Brother PEC stitch block + PES container (version 1, "#PES0001").
 *
 * PEC (Y grows downward, unlike DST/EXP/JEF):
 *   512-byte header (label, icon size, colour count and Brother chart indices), then a stitch block:
 *   00 00, u24 block length, 31 FF F0, u16 width, u16 height, 0x1E0, 0x1B0, stitches, 0xFF,
 *   followed by one 48x38 1-bit icon for the whole design and one per colour block.
 *   Stitch values: 7-bit two's complement when -64 < v < 63, otherwise 12-bit with the high bit set;
 *   bits 4/5 of the first byte of a 12-bit value flag jump (0x10) / trim (0x20).
 *   Colour change = FE B0 followed by 02/01 alternating.
 * PES v1 adds "#PES0001", the PEC offset, a small header and CEmbOne/CSewSeg blocks describing the
 * stitch segments per colour.
 */
object PesEncoder : MachineEncoder {
    override val formatName = "Brother PES"
    override val extension = "pes"
    const val MAX = 2047

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, nativeTrim = true, flipY = false)
        val seq = colorSequence(plan)
        val abs = absolute(recs)
        val ext = bounds(abs)          // minX, minY, maxX, maxY  (Y down)

        val w = LeWriter()
        w.ascii("#PES0001")
        val pecOffsetPos = w.size
        w.u32(0)
        // header v1
        w.u16(1).u16(1).u16(1)
        w.u16(0xFFFF).u16(0)
        writeBlocks(w, abs, seq, ext)
        w.patchU32(pecOffsetPos, w.size)
        writePec(w, recs, abs, seq, ext, label)
        return w.toByteArray()
    }

    // ---- shared geometry ----------------------------------------------------------------------

    internal class AbsRec(val x: Int, val y: Int, val cmd: StitchCommand, val trimmed: Boolean)

    private fun absolute(recs: List<Rec>): List<AbsRec> {
        val out = ArrayList<AbsRec>(recs.size)
        var x = 0; var y = 0; var trimPending = false
        for (r in recs) {
            x += r.dx; y += r.dy
            when (r.cmd) {
                StitchCommand.TRIM -> { trimPending = true }
                StitchCommand.JUMP -> { out.add(AbsRec(x, y, r.cmd, trimPending)); trimPending = false }
                else -> { out.add(AbsRec(x, y, r.cmd, false)); if (r.cmd == StitchCommand.STITCH) trimPending = false }
            }
        }
        return out
    }

    private fun bounds(a: List<AbsRec>): IntArray {
        var minX = 0; var minY = 0; var maxX = 0; var maxY = 0
        for (r in a) if (r.cmd == StitchCommand.STITCH || r.cmd == StitchCommand.JUMP) {
            minX = minOf(minX, r.x); minY = minOf(minY, r.y); maxX = maxOf(maxX, r.x); maxY = maxOf(maxY, r.y)
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    // ---- PES blocks -----------------------------------------------------------------------------

    private fun str16(w: LeWriter, s: String) { w.u16(s.length); w.ascii(s) }

    private class Seg(val points: List<IntArray>, val colorCode: Int, val flag: Int)

    private fun writeBlocks(w: LeWriter, abs: List<AbsRec>, seq: List<com.topz.stitchdraw.embroidery.ThreadColor>, ext: IntArray) {
        val width = ext[2] - ext[0]; val height = ext[3] - ext[1]
        val chartIdx = pecIndices(seq).second
        str16(w, "CEmbOne")
        repeat(8) { w.u16(0) }
        val tx = 350f + 1300f / 2 - width / 2f
        val ty = 100f + height + 1800f / 2 - height / 2f
        w.f32(1f).f32(0f).f32(0f).f32(1f).f32(tx).f32(ty)
        w.u16(1).u16(0).u16(0).u16(width).u16(height)
        repeat(8) { w.u8(0) }
        val sectionsPos = w.size
        w.u16(0)
        w.u16(0xFFFF).u16(0)
        str16(w, "CSewSeg")

        // command blocks: runs of the same command
        val segs = ArrayList<Seg>()
        var colorIdx = 0
        var colorCode = chartIdx.getOrElse(0) { 1 }
        colorIdx++
        var sx = 0; var sy = 0
        val adjX = ext[0]; val adjY = ext[3]
        var i = 0
        while (i < abs.size) {
            val cmd = abs[i].cmd
            var j = i
            while (j < abs.size && abs[j].cmd == cmd) j++
            when (cmd) {
                StitchCommand.JUMP -> {
                    val last = abs[j - 1]
                    segs.add(Seg(listOf(intArrayOf(sx - adjX, sy - adjY), intArrayOf(last.x - adjX, last.y - adjY)), colorCode, 1))
                }
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> {
                    colorCode = chartIdx.getOrElse(colorIdx) { colorCode }; colorIdx++
                }
                StitchCommand.STITCH -> {
                    val pts = ArrayList<IntArray>()
                    for (k in i until j) { sx = abs[k].x; sy = abs[k].y; pts.add(intArrayOf(sx - adjX, sy - adjY)) }
                    segs.add(Seg(pts, colorCode, 0))
                }
                else -> {}
            }
            // a jump moves the "stitched" position only through following stitches (matches reference behaviour)
            i = j
        }
        val colorLog = ArrayList<IntArray>()
        var prev = -1; var flag = -1
        var section = 0
        for (s in segs) {
            if (flag != -1) w.u16(0x8003)
            flag = s.flag
            if (prev != s.colorCode) { colorLog.add(intArrayOf(section, s.colorCode)); prev = s.colorCode }
            w.u16(s.flag).u16(s.colorCode).u16(s.points.size)
            for (p in s.points) { w.u16(p[0]); w.u16(p[1]) }
            section++
        }
        w.u16(colorLog.size)
        for (c in colorLog) { w.u16(c[0]); w.u16(c[1]) }
        w.patchU16(sectionsPos, section)
        w.u16(0).u16(0)
    }

    // ---- PEC ------------------------------------------------------------------------------------

    /** (unique chart index list for the header, chart index for every colour block). */
    private fun pecIndices(seq: List<com.topz.stitchdraw.embroidery.ThreadColor>): Pair<List<Int>, List<Int>> {
        val used = HashSet<Int>(); val byRgb = LinkedHashMap<Int, Int>()
        for (t in seq) if (t.rgb !in byRgb) {
            val idx = nearestChart(t.rgb, Charts.PEC, used)
            byRgb[t.rgb] = idx; used.add(idx)
        }
        val perBlock = seq.map { byRgb.getValue(it.rgb) }
        return perBlock to perBlock
    }

    private fun writePec(w: LeWriter, recs: List<Rec>, abs: List<AbsRec>, seq: List<com.topz.stitchdraw.embroidery.ThreadColor>, ext: IntArray, label: String) {
        val perBlock = pecIndices(seq).first
        if (perBlock.size > 254) throw ExportException("Too many colour changes for PEC (${perBlock.size})")
        val name = label.filter { it.code in 32..126 }.take(8)
        w.ascii("LA:" + name.padEnd(16) + "\r")
        w.spaces(12).u8(0xFF).u8(0x00)
        w.u8(48 / 8).u8(38)
        w.spaces(12)
        w.u8(perBlock.size - 1)
        for (p in perBlock) w.u8(p)
        w.spaces(463 - perBlock.size)

        val blockStart = w.size
        w.u8(0).u8(0)
        val lenPos = w.size
        w.u24(0)
        w.u8(0x31).u8(0xFF).u8(0xF0)
        w.u16(ext[2] - ext[0]).u16(ext[3] - ext[1]).u16(0x1E0).u16(0x1B0)
        encodeStitches(w, recs)
        w.patchU24(lenPos, w.size - blockStart)

        // graphics: whole design, then each colour block
        val blank = Charts.PEC_BLANK_ICON.map { it.toByte() }.toByteArray()
        val whole = blank.copyOf()
        val perColor = ArrayList<ByteArray>()
        var curPoints = ArrayList<AbsRec>()
        val allStitches = ArrayList<AbsRec>()
        for (r in abs) {
            when (r.cmd) {
                StitchCommand.STITCH -> { curPoints.add(r); allStitches.add(r) }
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> { perColor.add(icon(curPoints, ext)); curPoints = ArrayList() }
                else -> {}
            }
        }
        perColor.add(icon(curPoints, ext))
        drawScaled(ext, allStitches, whole, 6, 4)
        w.bytes(whole)
        for (p in perColor) w.bytes(p)
    }

    private fun icon(points: List<AbsRec>, ext: IntArray): ByteArray {
        val g = ByteArray(228)
        drawScaled(ext, points, g, 6, 5)
        return g
    }

    private fun drawScaled(ext: IntArray, points: List<AbsRec>, g: ByteArray, stride: Int, buffer: Int) {
        val dw = maxOf(1, ext[2] - ext[0]).toDouble(); val dh = maxOf(1, ext[3] - ext[1]).toDouble()
        val gw = stride * 8.0; val gh = g.size / stride.toDouble()
        val scale = minOf((gw - buffer) / dw, (gh - buffer) / dh)
        val cx = (ext[0] + ext[2]) / 2.0; val cy = (ext[1] + ext[3]) / 2.0
        val tx = -cx * scale + gw / 2; val ty = -cy * scale + gh / 2
        for (p in points) {
            val x = Math.floor(p.x * scale + tx).toInt(); val y = Math.floor(p.y * scale + ty).toInt()
            if (x < 0 || y < 0 || y * stride + x / 8 >= g.size || x >= stride * 8) continue
            g[y * stride + x / 8] = (g[y * stride + x / 8].toInt() or (1 shl (x % 8))).toByte()
        }
    }

    private fun value(w: LeWriter, v: Int, long: Boolean = false, flag: Int = 0) {
        if (!long && v > -64 && v < 63) w.u8(v and 0x7F)
        else {
            val x = (v and 0x0FFF) or 0x8000 or (flag shl 8)
            w.u8((x shr 8) and 0xFF).u8(x and 0xFF)
        }
    }

    private fun encodeStitches(w: LeWriter, recs: List<Rec>) {
        var colorTwo = true; var jumping = true; var init = true
        var pendingTrim = false
        for (r in recs) {
            when (r.cmd) {
                StitchCommand.STITCH -> {
                    if (jumping) { if (r.dx != 0 && r.dy != 0) { value(w, 0); value(w, 0) }; jumping = false }
                    value(w, r.dx); value(w, r.dy)
                }
                StitchCommand.JUMP -> {
                    jumping = true
                    val flag = if (init || !pendingTrim) 0x10 else 0x20
                    value(w, r.dx, true, flag); value(w, r.dy, true, flag)
                    pendingTrim = false
                }
                StitchCommand.TRIM -> pendingTrim = true
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> {
                    if (jumping) { value(w, 0); value(w, 0); jumping = false }
                    w.u8(0xFE).u8(0xB0).u8(if (colorTwo) 2 else 1); colorTwo = !colorTwo
                }
                StitchCommand.END -> { w.u8(0xFF) }
            }
            init = false
        }
    }
}
