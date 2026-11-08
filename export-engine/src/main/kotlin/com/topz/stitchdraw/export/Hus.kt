package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import java.io.ByteArrayOutputStream

/** MSB-first bit packer. */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var cur = 0; private var n = 0
    fun bits(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            cur = (cur shl 1) or ((value shr i) and 1); n++
            if (n == 8) { out.write(cur); cur = 0; n = 0 }
        }
    }
    fun toByteArray(): ByteArray { if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 }; return out.toByteArray() }
}

/**
 * Greenleaf-style LZ/Huffman container used by HUS, written with a fixed, valid Huffman model and literals only
 * (no back references): every byte costs 9 bits and the stream ends with an explicit end token.
 *
 * Block = u16 token count, character-length tree, character tree, distance tree, tokens. The model used here:
 *  - code-length alphabet: symbol 11 ("length 9") = '0', symbol 2 ("skip run") = '10', symbol 3 ("length 1") = '11'
 *  - character alphabet: END (510) = '0', literal b = '1' + 8 bits of b
 *  - distance tree: single value (unused)
 */
object HusCompress {
    private const val MAX_TOKENS = 0xFFFF

    fun compress(data: ByteArray): ByteArray {
        val w = BitWriter()
        var pos = 0
        val total = data.size + 1                       // + END token
        var remaining = total
        while (remaining > 0) {
            val inBlock = minOf(MAX_TOKENS, remaining)
            w.bits(inBlock, 16)
            header(w)
            val literals = if (inBlock == remaining) inBlock - 1 else inBlock
            repeat(literals) { w.bits(0x100 or (data[pos++].toInt() and 0xFF), 9) }
            if (inBlock == remaining) w.bits(0, 1)       // END
            remaining -= inBlock
        }
        return w.toByteArray()
    }

    private fun header(w: BitWriter) {
        // character-length tree: 12 symbols; lengths for symbols 0..11 = 0,0,2,2,0,...,0,1
        w.bits(12, 5)
        val lens = intArrayOf(0, 0, 2, 2, 0, 0, 0, 0, 0, 0, 0, 1)
        var i = 0
        while (i < lens.size) {
            if (i == 3) w.bits(0, 2)                    // "skip" field read when index == 3, we skip none
            w.bits(lens[i], 3)                           // all lengths < 7 -> plain 3-bit form
            i++
        }
        // character tree: 511 entries
        w.bits(511, 9)
        repeat(256) { w.bits(0b0, 1) }                   // symbol 11 -> literal length 9
        w.bits(0b10, 2); w.bits(234, 9)                  // symbol 2 -> skip 20 + 234 = 254 unused symbols (256..509)
        w.bits(0b11, 2)                                  // symbol 3 -> END has length 1
        // distance tree: single value
        w.bits(0, 5); w.bits(0, 5)
    }
}

/**
 * Husqvarna Viking HUS: header (magic 0x00C8AF5B, counts, extents, three stream offsets, 8-char name,
 * Husqvarna chart index per colour) followed by three compressed streams: commands, X, Y.
 * Commands: 0x80 stitch, 0x81 jump, 0x84 colour change, 0x88 trim, 0x90 end. Moves are signed bytes (Y up).
 */
object HusEncoder : MachineEncoder {
    override val formatName = "Husqvarna HUS"
    override val extension = "hus"
    const val MAX = 127

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, nativeTrim = true)
        val seq = colorSequence(plan)
        val cmd = ByteArray(recs.size); val xs = ByteArray(recs.size); val ys = ByteArray(recs.size)
        recs.forEachIndexed { i, r ->
            cmd[i] = when (r.cmd) {
                StitchCommand.STITCH -> 0x80
                StitchCommand.JUMP -> 0x81
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> 0x84
                StitchCommand.TRIM -> 0x88
                StitchCommand.END -> 0x90
            }.toByte()
            xs[i] = r.dx.toByte(); ys[i] = r.dy.toByte()
        }
        val ext = RecordBuilder.extents(recs)
        val cc = HusCompress.compress(cmd); val cx = HusCompress.compress(xs); val cy = HusCompress.compress(ys)

        val used = ArrayList<Int>(); var last = -1
        for (t in seq) {
            var idx = nearestChart(t.rgb, Charts.HUS, first = 0)
            if (idx == last) idx = nearestChart(t.rgb, Charts.HUS, setOf(idx), first = 0)
            used.add(idx); last = idx
        }
        val headerSize = 0x2A + 2 * used.size
        val w = LeWriter()
        w.u32(0x00C8AF5B).u32(recs.size).u32(used.size)
        w.u16(ext[0]).u16(ext[2]).u16(ext[1]).u16(ext[3])        // +x, +y, -x, -y
        w.u32(headerSize).u32(headerSize + cc.size).u32(headerSize + cc.size + cx.size)
        repeat(8) { w.u8(0) }
        w.u16(0)
        for (u in used) w.u16(u)
        check(w.size == headerSize)
        w.bytes(cc).bytes(cx).bytes(cy)
        return w.toByteArray()
    }
}
