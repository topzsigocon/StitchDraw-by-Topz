package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan

/**
 * Tajima DST. 512-byte ASCII header (LA/ST/CO/+X/-X/+Y/-Y/AX/AY/MX/MY/PD, CR separated, 0x1A, space padded)
 * followed by 3-byte records. Each axis is a balanced-ternary sum of 1,3,9,27,81 (max +/-121 per record).
 * Byte 3 flags: 0x03 stitch, 0x83 jump, 0xC3 colour change, 0xF3 end.
 */
object DstEncoder : MachineEncoder {
    override val formatName = "Tajima DST"
    override val extension = "dst"
    const val MAX = 121
    private const val TRIM_JUMPS = 4

    override fun encode(plan: StitchPlan, label: String): ByteArray {
        val recs = RecordBuilder.build(plan, MAX, trimJumps = TRIM_JUMPS)
        val body = java.io.ByteArrayOutputStream(recs.size * 3)
        for (r in recs) body.write(record(r))
        val ext = RecordBuilder.extents(recs)
        var ax = 0; var ay = 0
        for (r in recs) { ax += r.dx; ay += r.dy }
        val colors = recs.count { it.cmd == StitchCommand.COLOR_CHANGE || it.cmd == StitchCommand.STOP }
        val head = StringBuilder()
        head.append("LA:").append(label.filter { it.code in 32..126 && it != '\r' }.take(16).padEnd(16)).append('\r')
        head.append("ST:").append("%7d".format(recs.size)).append('\r')
        head.append("CO:").append("%3d".format(colors)).append('\r')
        head.append("+X:").append("%5d".format(ext[0])).append('\r')
        head.append("-X:").append("%5d".format(ext[1])).append('\r')
        head.append("+Y:").append("%5d".format(ext[2])).append('\r')
        head.append("-Y:").append("%5d".format(ext[3])).append('\r')
        head.append("AX:").append("%+6d".format(ax)).append('\r')
        head.append("AY:").append("%+6d".format(ay)).append('\r')
        head.append("MX:").append("%+6d".format(0)).append('\r')
        head.append("MY:").append("%+6d".format(0)).append('\r')
        head.append("PD:******\r")
        head.append(0x1A.toChar())
        val header = ByteArray(512) { 0x20 }
        head.toString().toByteArray(Charsets.ISO_8859_1).copyInto(header)
        return header + body.toByteArray()
    }

    fun record(r: Rec): ByteArray {
        var b0 = 0; var b1 = 0; var b2 = when (r.cmd) {
            StitchCommand.JUMP -> 0x83
            StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> 0xC3
            StitchCommand.END -> 0xF3
            else -> 0x03
        }
        require(kotlin.math.abs(r.dx) <= MAX && kotlin.math.abs(r.dy) <= MAX) { "DST record out of range" }
        var x = r.dx; var y = r.dy
        // Balanced ternary per axis. X lives in the low nibbles of b0/b1 and bits 2-3 of b2;
        // Y lives in the high nibbles of b0/b1 and bits 4-5 of b2.
        if (x > 40) { b2 = b2 or 0x04; x -= 81 } else if (x < -40) { b2 = b2 or 0x08; x += 81 }
        if (y > 40) { b2 = b2 or 0x20; y -= 81 } else if (y < -40) { b2 = b2 or 0x10; y += 81 }
        if (x > 13) { b1 = b1 or 0x04; x -= 27 } else if (x < -13) { b1 = b1 or 0x08; x += 27 }
        if (y > 13) { b1 = b1 or 0x20; y -= 27 } else if (y < -13) { b1 = b1 or 0x10; y += 27 }
        if (x > 4) { b0 = b0 or 0x04; x -= 9 } else if (x < -4) { b0 = b0 or 0x08; x += 9 }
        if (y > 4) { b0 = b0 or 0x20; y -= 9 } else if (y < -4) { b0 = b0 or 0x10; y += 9 }
        if (x > 1) { b1 = b1 or 0x01; x -= 3 } else if (x < -1) { b1 = b1 or 0x02; x += 3 }
        if (y > 1) { b1 = b1 or 0x80; y -= 3 } else if (y < -1) { b1 = b1 or 0x40; y += 3 }
        if (x > 0) { b0 = b0 or 0x01; x -= 1 } else if (x < 0) { b0 = b0 or 0x02; x += 1 }
        if (y > 0) { b0 = b0 or 0x80; y -= 1 } else if (y < 0) { b0 = b0 or 0x40; y += 1 }
        check(x == 0 && y == 0)
        return byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte())
    }

    /** Reader used for validation: returns the records, or throws on a malformed file. */
    fun decode(bytes: ByteArray): List<Rec> {
        if (bytes.size < 512 + 3 || (bytes.size - 512) % 3 != 0) throw ExportException("DST: bad size")
        if (String(bytes, 0, 3, Charsets.ISO_8859_1) != "LA:") throw ExportException("DST: missing header")
        val out = ArrayList<Rec>()
        var i = 512
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF; val b1 = bytes[i + 1].toInt() and 0xFF; val b2 = bytes[i + 2].toInt() and 0xFF
            i += 3
            if (b2 and 0x03 != 0x03) throw ExportException("DST: bad record flags")
            var x = 0; var y = 0
            fun bit(v: Int, m: Int) = v and m != 0
            if (bit(b0, 0x01)) x += 1; if (bit(b0, 0x02)) x -= 1; if (bit(b0, 0x04)) x += 9; if (bit(b0, 0x08)) x -= 9
            if (bit(b1, 0x01)) x += 3; if (bit(b1, 0x02)) x -= 3; if (bit(b1, 0x04)) x += 27; if (bit(b1, 0x08)) x -= 27
            if (bit(b2, 0x04)) x += 81; if (bit(b2, 0x08)) x -= 81
            if (bit(b0, 0x80)) y += 1; if (bit(b0, 0x40)) y -= 1; if (bit(b0, 0x20)) y += 9; if (bit(b0, 0x10)) y -= 9
            if (bit(b1, 0x80)) y += 3; if (bit(b1, 0x40)) y -= 3; if (bit(b1, 0x20)) y += 27; if (bit(b1, 0x10)) y -= 27
            if (bit(b2, 0x20)) y += 81; if (bit(b2, 0x10)) y -= 81
            val cmd = when {
                b2 and 0xF3 == 0xF3 -> StitchCommand.END
                b2 and 0xC3 == 0xC3 -> StitchCommand.COLOR_CHANGE
                b2 and 0x83 == 0x83 -> StitchCommand.JUMP
                else -> StitchCommand.STITCH
            }
            out.add(Rec(x, y, cmd))
            if (cmd == StitchCommand.END) break
        }
        if (out.last().cmd != StitchCommand.END) throw ExportException("DST: missing end record")
        return out
    }
}
