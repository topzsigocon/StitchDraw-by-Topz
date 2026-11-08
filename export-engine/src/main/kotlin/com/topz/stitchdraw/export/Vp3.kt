package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import java.io.ByteArrayOutputStream

/** Big-endian writer with 32-bit length back-patching (VP3 stores "bytes until end of block"). */
internal class BeWriter {
    private val bos = ByteArrayOutputStream()
    private val patches = ArrayList<Pair<Int, Int>>()
    val size get() = bos.size()
    fun u8(v: Int) = apply { bos.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
    fun u24(v: Int) = apply { u8(v shr 16); u8(v shr 8); u8(v) }
    fun u32(v: Int) = apply { u16(v ushr 16); u16(v) }
    fun raw(vararg b: Int) = apply { for (x in b) u8(x) }
    fun bytes(b: ByteArray) = apply { bos.write(b) }
    /** Length-prefixed UTF-16BE string (prefix = byte count). */
    fun str16(s: String) = apply { val b = s.toByteArray(Charsets.UTF_16BE); u16(b.size); bytes(b) }
    /** Length-prefixed UTF-8 string. */
    fun str8(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); u16(b.size); bytes(b) }
    /** Writes a 32-bit placeholder and returns a handle; [patch] fills it with the bytes written after it. */
    fun lengthSlot(): Int { val at = size; u32(0); return at }
    fun patch(slot: Int) { patches.add(slot to (size - slot - 4)) }
    fun toByteArray(): ByteArray {
        val a = bos.toByteArray()
        for ((p, v) in patches) { a[p] = (v ushr 24).toByte(); a[p + 1] = (v ushr 16).toByte(); a[p + 2] = (v ushr 8).toByte(); a[p + 3] = v.toByte() }
        return a
    }
}

/**
 * Pfaff / Viking VP3.
 *
 * Layout: "%vsm%\0", producer string, then a file block (extents, stitch count, colour block count), one
 * design block (centre, extents, notes, transform constants) and one colour block per thread. Each colour
 * block carries its start offset from the design centre, the thread (RGB + catalog/description/brand),
 * and the stitches: signed byte pairs, with 80 01 dx dy 80 02 for moves beyond +/-127 and 80 03 for a trim.
 * VP3 has no jump command; the long form is how a move between objects is expressed.
 * Header coordinates are x100 of the 0.1 mm stitch units, as read by the reference implementation.
 */
object Vp3Encoder : MachineEncoder {
    override val formatName = "Pfaff VP3"
    override val extension = "vp3"
    private const val PRODUCER = "Produced by     Software Ltd"

    private class Block(val recs: List<RecordBuilder.Abs>, val rgb: Int, val name: String)

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, 3200, nativeTrim = true, flipY = false)
        val abs = RecordBuilder.absolute(recs)
        val seq = colorSequence(plan)

        // split into colour blocks at every COLOR_CHANGE / STOP (the change record opens the next block)
        val blocks = ArrayList<Block>()
        var cur = ArrayList<RecordBuilder.Abs>(); var bi = 0
        for (r in abs) {
            if (r.cmd == StitchCommand.COLOR_CHANGE || r.cmd == StitchCommand.STOP) {
                blocks.add(mk(cur, seq, bi++)); cur = ArrayList()
            }
            cur.add(r)
        }
        blocks.add(mk(cur, seq, bi))

        var minX = 0; var maxX = 0; var minY = 0; var maxY = 0
        for (r in abs) if (r.cmd == StitchCommand.STITCH || r.cmd == StitchCommand.JUMP) {
            minX = minOf(minX, r.x); maxX = maxOf(maxX, r.x); minY = minOf(minY, r.y); maxY = maxOf(maxY, r.y)
        }
        val width = maxX - minX; val height = maxY - minY
        val cx = maxX - width / 2; val cy = maxY - height / 2
        val stitchCount = abs.count { it.cmd != StitchCommand.END }

        val w = BeWriter()
        w.bytes("%vsm%".toByteArray()).u8(0)
        w.str16(PRODUCER)
        w.raw(0x00, 0x02, 0x00)
        val fileSlot = w.lengthSlot()
        w.str16("")
        w.u32(maxX * 100).u32(-minY * 100).u32(minX * 100).u32(-maxY * 100)
        w.u32(stitchCount).u8(0).u8(blocks.size).u8(12).u8(0)
        w.u8(1)                                                    // one design
        w.raw(0x00, 0x03, 0x00)
        val designSlot = w.lengthSlot()
        w.u32((cx) * 100).u32(-cy * 100).u8(0).u8(0).u8(0)
        w.u32(-(width / 2) * 100).u32((width / 2) * 100).u32(-(height / 2) * 100).u32((height / 2) * 100)
        w.u32(width * 100).u32(height * 100)
        w.str16("")
        w.raw(0x64, 0x64).u32(4096).u32(0).u32(0).u32(4096)
        w.bytes("xxPP".toByteArray()).raw(0x01, 0x00)
        w.str16(PRODUCER)
        w.u16(blocks.size)
        var first = true
        for (b in blocks) { block(w, b, first, cx, cy); first = false }
        w.patch(designSlot)
        w.patch(fileSlot)
        return w.toByteArray()
    }

    private fun mk(recs: List<RecordBuilder.Abs>, seq: List<com.topz.stitchdraw.embroidery.ThreadColor>, i: Int): Block {
        val t = seq.getOrElse(i) { seq.last() }
        return Block(recs, t.rgb, "#%06X".format(t.rgb))
    }

    private fun block(w: BeWriter, b: Block, first: Boolean, cx: Int, cy: Int) {
        w.raw(0x00, 0x05, 0x00)
        val slot = w.lengthSlot()
        val rs = b.recs
        val moves = rs.filter { it.cmd == StitchCommand.STITCH || it.cmd == StitchCommand.JUMP }
        val fx = if (first || rs.isEmpty()) 0 else rs.first().x
        val fy = if (first || rs.isEmpty()) 0 else rs.first().y
        val lx = rs.lastOrNull()?.x ?: 0
        val ly = rs.lastOrNull()?.y ?: 0
        w.u32((fx - cx) * 100).u32(-(fy - cy) * 100)
        // thread: single colour, no transition
        w.raw(0x01, 0x00).u24(b.rgb).raw(0x00, 0x00, 0x00, 0x05, 0x28)
        w.str8("").str8(b.name).str8("")
        w.u32((lx - fx) * 100).u32(-(ly - fy) * 100)
        // stitch stream
        w.raw(0x00, 0x01, 0x00)
        val sSlot = w.lengthSlot()
        w.raw(0x0A, 0xF6, 0x00)
        var px = fx; var py = fy
        for (r in rs) {
            when (r.cmd) {
                StitchCommand.END, StitchCommand.TRIM -> { w.u8(0x80).u8(0x03); if (r.cmd == StitchCommand.END) break }
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP, StitchCommand.JUMP -> {}   // jumps are folded into the next stitch
                StitchCommand.STITCH -> {
                    val dx = r.x - px; val dy = r.y - py; px = r.x; py = r.y
                    if (dx in -127..127 && dy in -127..127) w.u8(dx).u8(dy)
                    else w.raw(0x80, 0x01).u16(dx).u16(dy).raw(0x80, 0x02)
                }
            }
        }
        w.patch(sSlot)
        w.u8(0)
        w.patch(slot)
    }
}
