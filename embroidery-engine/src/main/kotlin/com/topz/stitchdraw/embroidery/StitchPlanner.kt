package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Bounds
import com.topz.stitchdraw.vector.Polyline
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Machine-level options of the stitch generator. */
data class PlannerOptions(
    /** Jumps longer than this (mm) are preceded by a TRIM. */
    val trimDistanceMm: Float = 3.0f,
    /** Length of tie-in/tie-off lock stitches (mm). */
    val lockStitchMm: Float = 0.4f,
)

data class StitchStats(
    val stitchCount: Int,
    val jumpCount: Int,
    val trimCount: Int,
    val colorChanges: Int,
    val bounds: Bounds,
    val threadLengthMm: Float,
)

/** Final machine instruction list for the whole design. Pure data, consumed by renderer and exporters. */
data class StitchPlan(
    val stitches: List<StitchPoint>,
    val threads: List<ThreadColor>,
    /** Thread index in effect for each stitch (parallel to [stitches]). */
    val threadIndexOf: IntArray,
    /** Id of the design object that produced each stitch, or -1 for COLOR_CHANGE / END (parallel to [stitches]). */
    val objectOf: IntArray,
    val stats: StitchStats,
) {
    /** Number of real STITCH commands per object id, in sequence order. */
    fun stitchCountsInOrder(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in stitches.indices) {
            val id = objectOf[i]
            if (id < 0) continue
            if (out.isEmpty() || out.last().first != id) out.add(id to 0)
            if (stitches[i].cmd == StitchCommand.STITCH) out[out.lastIndex] = id to (out.last().second + 1)
        }
        return out
    }

    companion object { val EMPTY = StitchPlan(emptyList(), emptyList(), IntArray(0), IntArray(0), StitchStats(0, 0, 0, 0, Bounds.EMPTY, 0f)) }
}

object StitchGenerator {
    /** All stitch positions (mm) for one object in sewing order, without lock stitches. */
    fun generate(o: DesignObject): List<Vec2> = generateRuns(o).flatten()

    /**
     * Stitch positions grouped into runs. Consecutive runs are NOT connected by stitches: the planner
     * joins them with a jump (and a trim when long). Fills keep one run while the link stays inside the shape.
     * An EMPTY run is a machine STOP marker (used by appliqué to pause for fabric placement / trimming).
     */
    fun generateRuns(o: DesignObject): List<List<Vec2>> {
        val path = FillGeometry.dedupe(o.path)
        if (path.size < 2) return emptyList()
        return when (o.type) {
            StitchType.RUNNING -> listOf(Polyline.resample(path, o.params.stitchLengthMm))
            // Triple run: out, back, out along identical points; the thread lies three times on the line.
            StitchType.TRIPLE_RUNNING -> {
                val f = Polyline.resample(path, o.params.stitchLengthMm)
                listOf(f + f.reversed().drop(1) + f.drop(1))
            }
            StitchType.SATIN -> Satin.generate(path, o.params, underlay = o.params.underlay, pullMm = o.params.pullCompMm)
            StitchType.ZIGZAG -> Satin.generate(path, o.params, underlay = false, pullMm = 0f)
            StitchType.APPLIQUE ->
                if (path.size < 3) listOf(Polyline.resample(path, o.params.stitchLengthMm))
                else Applique.generate(path, o.params)
            StitchType.TATAMI ->
                if (path.size < 3) listOf(Polyline.resample(path, o.params.stitchLengthMm))
                else Tatami.generate(path, o.holes.map { FillGeometry.dedupe(it) }.filter { it.size >= 3 }, o.params)
        }
    }
}

object StitchPlanner {

    fun plan(design: EmbroideryDesign, options: PlannerOptions = PlannerOptions()): StitchPlan {
        val out = ArrayList<StitchPoint>()
        val threadAt = ArrayList<Int>()
        val objectAt = ArrayList<Int>()
        var curObj = -1
        var curX = 0
        var curY = 0
        var curThread = -1
        var jumps = 0
        var trims = 0
        var colorChanges = 0
        var haveStitched = false

        fun emit(x: Int, y: Int, cmd: StitchCommand, thread: Int, objId: Int = curObj) {
            out.add(StitchPoint(x, y, cmd)); threadAt.add(thread); objectAt.add(objId); curX = x; curY = y
        }

        val hiddenLayers = design.layers.filter { !it.visible }.map { it.id }.toSet()
        for (obj in design.objects) {
            if (obj.layerId in hiddenLayers || obj.path.size < 2) continue
            val thread = obj.threadIndex.coerceIn(0, maxOf(0, design.threads.size - 1))
            val runs = StitchGenerator.generateRuns(obj)
                .map { run -> dedupeUnits(run.map { it.toUnits() }) }
                .filter { it.isEmpty() || it.size >= 2 }
            if (runs.none { it.isNotEmpty() } || runs.first().isEmpty()) continue
            val pts = runs.first()

            if (curThread != -1 && thread != curThread) {
                emit(curX, curY, StitchCommand.COLOR_CHANGE, curThread, -1); colorChanges++
            }
            curThread = thread
            curObj = obj.id

            val prevObj = objectAt.lastOrNull { it >= 0 } ?: obj.id
            val (sx, sy) = pts.first()
            val dist = hypot((sx - curX).toDouble(), (sy - curY).toDouble()) / UNITS_PER_MM
            if (dist > 0.0) {
                if (haveStitched && dist > options.trimDistanceMm) { emit(curX, curY, StitchCommand.TRIM, thread, prevObj); trims++ }
                emit(sx, sy, StitchCommand.JUMP, thread); jumps++
            }

            val lock = (options.lockStitchMm * UNITS_PER_MM).roundToInt()
            if (obj.params.tieIn && lock > 0) {
                val (dx, dy) = unitToward(pts[0], pts[1], lock)
                emit(sx + dx, sy + dy, StitchCommand.STITCH, thread)
                emit(sx, sy, StitchCommand.STITCH, thread)
                emit(sx + dx, sy + dy, StitchCommand.STITCH, thread)
            }
            for (i in 1 until pts.size) emit(pts[i].first, pts[i].second, StitchCommand.STITCH, thread)
            // further runs of the same object (fills that cannot be linked inside the shape)
            for (r in runs.drop(1)) {
                if (r.isEmpty()) { emit(curX, curY, StitchCommand.STOP, thread); continue }
                val d = hypot((r[0].first - curX).toDouble(), (r[0].second - curY).toDouble()) / UNITS_PER_MM
                if (d > options.trimDistanceMm) { emit(curX, curY, StitchCommand.TRIM, thread); trims++ }
                if (d > 0.0) { emit(r[0].first, r[0].second, StitchCommand.JUMP, thread); jumps++ }
                for (i in 1 until r.size) emit(r[i].first, r[i].second, StitchCommand.STITCH, thread)
            }
            val tail = runs.last { it.isNotEmpty() }
            if (obj.params.tieOut && lock > 0) {
                val last = tail.last(); val prev = tail[tail.size - 2]
                val (dx, dy) = unitToward(last, prev, lock)
                emit(last.first + dx, last.second + dy, StitchCommand.STITCH, thread)
                emit(last.first, last.second, StitchCommand.STITCH, thread)
                emit(last.first + dx, last.second + dy, StitchCommand.STITCH, thread)
                emit(last.first, last.second, StitchCommand.STITCH, thread)
            }
            haveStitched = true
        }
        if (out.isEmpty()) return StitchPlan.EMPTY
        emit(curX, curY, StitchCommand.END, curThread, -1)
        return StitchPlan(out, design.threads, threadAt.toIntArray(), objectAt.toIntArray(), stats(out, colorChanges, jumps, trims))
    }

    /** Drops consecutive identical machine positions (zero-length stitches). */
    private fun dedupeUnits(p: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(p.size)
        for (v in p) if (out.isEmpty() || out.last() != v) out.add(v)
        return out
    }

    private fun Vec2.toUnits() = Pair((x * UNITS_PER_MM).roundToInt(), (y * UNITS_PER_MM).roundToInt())

    /** Integer offset of ~[len] units from [from] toward [to] (never zero-length unless points coincide). */
    private fun unitToward(from: Pair<Int, Int>, to: Pair<Int, Int>, len: Int): Pair<Int, Int> {
        val dx = (to.first - from.first).toDouble()
        val dy = (to.second - from.second).toDouble()
        val d = hypot(dx, dy)
        if (d == 0.0) return 0 to 0
        val l = minOf(len.toDouble(), d / 2.0).coerceAtLeast(1.0)
        return (dx / d * l).roundToInt() to (dy / d * l).roundToInt()
    }

    private fun stats(list: List<StitchPoint>, colors: Int, jumps: Int, trims: Int): StitchStats {
        var b = Bounds.EMPTY
        var len = 0.0
        var stitches = 0
        var px = 0
        var py = 0
        for (s in list) {
            if (s.cmd == StitchCommand.STITCH) {
                stitches++
                len += hypot((s.x - px).toDouble(), (s.y - py).toDouble())
            }
            if (s.cmd == StitchCommand.STITCH || s.cmd == StitchCommand.JUMP) b = b.include(s.x / UNITS_PER_MM, s.y / UNITS_PER_MM)
            px = s.x; py = s.y
        }
        return StitchStats(stitches, jumps, trims, colors, b, (len / UNITS_PER_MM).toFloat())
    }
}
