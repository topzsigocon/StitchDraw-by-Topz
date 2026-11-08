package com.topz.stitchdraw.export

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan

/** One machine record as written to a file: relative move in 0.1 mm, machine axes (Y up). */
data class Rec(val dx: Int, val dy: Int, val cmd: StitchCommand)

class ExportException(message: String) : Exception(message)

interface MachineEncoder {
    val formatName: String
    val extension: String
    fun encode(plan: StitchPlan, label: String = "StitchDraw"): ByteArray
}

/**
 * Converts the planner's absolute, Y-down instruction list into relative, Y-up records that
 * respect the per-record limit of the target format.
 *
 *  - The design is re-centred on the middle of its stitch bounds (machine origin = design centre).
 *  - Y is negated (screen Y grows downward; machine Y grows upward).
 *  - Moves longer than [maxStep] are split into equal parts. A long STITCH becomes several
 *    stitches, a JUMP several jumps.
 *  - TRIM is emitted as [trimJumps] tiny alternating jump records when the format has no trim code.
 */
object RecordBuilder {
    fun build(plan: StitchPlan, maxStep: Int, trimJumps: Int = 0, nativeTrim: Boolean = false, flipY: Boolean = true): List<Rec> {
        if (plan.stitches.isEmpty()) throw ExportException("Design has no stitches to export")
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (s in plan.stitches) if (s.cmd == StitchCommand.STITCH || s.cmd == StitchCommand.JUMP) {
            minX = minOf(minX, s.x); maxX = maxOf(maxX, s.x); minY = minOf(minY, s.y); maxY = maxOf(maxY, s.y)
        }
        if (minX == Int.MAX_VALUE) throw ExportException("Design has no stitches to export")
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val out = ArrayList<Rec>(plan.stitches.size + 16)
        var px = 0; var py = 0
        for (s in plan.stitches) {
            when (s.cmd) {
                StitchCommand.STITCH, StitchCommand.JUMP -> {
                    val tx = s.x - cx; val ty = if (flipY) -(s.y - cy) else (s.y - cy)
                    split(px, py, tx, ty, maxStep, s.cmd, out)
                    px = tx; py = ty
                }
                StitchCommand.TRIM -> if (nativeTrim) out.add(Rec(0, 0, StitchCommand.TRIM)) else repeat(trimJumps) { i ->
                    // alternate +1/-1 so the net position is unchanged
                    val d = if (i % 2 == 0) 1 else -1
                    out.add(Rec(d, 0, StitchCommand.JUMP))
                    px += d
                }
                StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> out.add(Rec(0, 0, s.cmd))
                StitchCommand.END -> {}
            }
        }
        // tidy: odd number of trim jumps would leave a 1-unit offset; callers use even counts, but fix anyway
        out.add(Rec(0, 0, StitchCommand.END))
        return out
    }

    private fun split(px: Int, py: Int, tx: Int, ty: Int, maxStep: Int, cmd: StitchCommand, out: MutableList<Rec>) {
        val dx = tx - px; val dy = ty - py
        val n = maxOf(1, (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) + maxStep - 1) / maxStep)
        var ax = px; var ay = py
        for (i in 1..n) {
            val nx = px + dx * i / n; val ny = py + dy * i / n
            out.add(Rec(nx - ax, ny - ay, cmd)); ax = nx; ay = ny
        }
    }

    /** Absolute (centred) position after each record. */
    class Abs(val x: Int, val y: Int, val cmd: StitchCommand)

    fun absolute(recs: List<Rec>): List<Abs> {
        var x = 0; var y = 0
        return recs.map { x += it.dx; y += it.dy; Abs(x, y, it.cmd) }
    }

    /** Extents in 0.1 mm relative to the start: (+x, -x, +y, -y) all non-negative. */
    fun extents(recs: List<Rec>): IntArray {
        var x = 0; var y = 0; var pX = 0; var nX = 0; var pY = 0; var nY = 0
        for (r in recs) { x += r.dx; y += r.dy; pX = maxOf(pX, x); nX = maxOf(nX, -x); pY = maxOf(pY, y); nY = maxOf(nY, -y) }
        return intArrayOf(pX, nX, pY, nY)
    }
}

/** Colour blocks in sewing order: the thread used by each block (first block + one per COLOR_CHANGE). */
fun colorSequence(plan: StitchPlan): List<com.topz.stitchdraw.embroidery.ThreadColor> {
    val out = ArrayList<com.topz.stitchdraw.embroidery.ThreadColor>()
    var needNew = true
    for (i in plan.stitches.indices) {
        when (plan.stitches[i].cmd) {
            StitchCommand.COLOR_CHANGE, StitchCommand.STOP -> needNew = true
            StitchCommand.END, StitchCommand.TRIM -> {}
            else -> if (needNew) { out.add(plan.threads[plan.threadIndexOf[i].coerceIn(0, plan.threads.size - 1)]); needNew = false }
        }
    }
    return out
}

/** Nearest chart entry by the "redmean" colour distance. [exclude] entries are skipped. Index 0 is never chosen unless [first] is 0 (HUS). */
fun nearestChart(rgb: Int, chart: IntArray, exclude: Set<Int> = emptySet(), first: Int = 1): Int {
    val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
    var best = -1; var bestD = Long.MAX_VALUE
    for (i in first until chart.size) {
        if (i in exclude) continue
        val c = chart[i]
        val r2 = (c shr 16) and 0xFF; val g2 = (c shr 8) and 0xFF; val b2 = c and 0xFF
        val rm = Math.round((r + r2) / 2.0).toInt()
        val dr = r - r2; val dg = g - g2; val db = b - b2
        val d = (((512 + rm) * dr * dr) shr 8) + 4L * dg * dg + (((767 - rm) * db * db) shr 8)
        if (d <= bestD) { bestD = d; best = i }
    }
    return best
}
